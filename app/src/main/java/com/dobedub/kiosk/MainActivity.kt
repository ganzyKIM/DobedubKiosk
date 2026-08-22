package com.dobedub.kiosk

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.dobedub.kiosk.nav.KioskNavHost
import com.dobedub.kiosk.nav.Routes
import com.dobedub.kiosk.ui.components.StatusOverlay
import com.dobedub.kiosk.ui.theme.DobedubKioskTheme
import com.dobedub.kiosk.update.AppUpdater
import com.dobedub.kiosk.web.clearWebSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5
private const val DEFAULT_VOLUME_MAX_PERCENT = 100

// 체크인 주기는 AppUpdater.CHECKIN_INTERVAL_MS 가 단일 출처다 — 그 값을 서버에도 함께
// 보고하므로, 주기를 바꿀 때 서버 상수를 따로 맞출 필요가 없다.
private const val UPDATE_INITIAL_DELAY_MS = 20_000L

/** 프로비저닝 인텐트 엑스트라 키(태블릿 세팅 스크립트에서 도서관 주소/기관명 전달). */
private const val EXTRA_START_URL = "kiosk_start_url"
private const val EXTRA_LABEL = "kiosk_label"

class MainActivity : ComponentActivity() {

    private val app get() = application as KioskApplication
    private val audioManager get() = getSystemService(AUDIO_SERVICE) as AudioManager
    private val updater by lazy { AppUpdater(applicationContext) }

    private var navController: NavHostController? = null
    // 관리자가 "업데이트 알림 보내기"를 요청한 기기에서만 채워진다 — 평소엔 조용히
    // 백그라운드로 자동 설치되고, 이 값이 있을 때만 화면에 확인창을 띄운다.
    private var pendingUpdateConfirmation by mutableStateOf<AppUpdater.Manifest?>(null)
    // 관리자가 "기기 화면에서 물어보고 받기"로 보낸 영상들 — 동의해야 내려받는다.
    private var pendingVideoConsent by mutableStateOf<List<AppUpdater.PendingVideo>?>(null)
    private val idleHandler = Handler(Looper.getMainLooper())
    @Volatile private var idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MINUTES * 60_000L
    @Volatile private var volumeMaxPercent = DEFAULT_VOLUME_MAX_PERCENT
    @Volatile private var kioskLockEnabled = true
    private val idleRunnable = Runnable { returnToHomeIfIdle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        handleProvisioningIntent(intent)
        startPeriodicUpdateChecks()
        // 재부팅 시 NetBird VPN 이 시스템 차원에서 자동으로 올라오게 — 부팅 직후 원격 관리
        // 끊김 방지(QA-4). NetBird 미설치/Device Owner 아님이면 조용히 아무것도 안 한다.
        app.kioskManager.ensureAlwaysOnVpn()

        setContent {
            DobedubKioskTheme {
                val navController = rememberNavController()
                this.navController = navController

                val settings by app.settingsRepository.settingsFlow.collectAsState(
                    initial = com.dobedub.kiosk.data.KioskSettings()
                )
                idleTimeoutMillis = settings.idleTimeoutMinutes * 60_000L
                volumeMaxPercent = settings.volumeMax
                kioskLockEnabled = settings.kioskLockEnabled

                LaunchedEffect(settings.brightness) {
                    val attributes = window.attributes
                    attributes.screenBrightness = settings.brightness.coerceIn(10, 100) / 100f
                    window.attributes = attributes
                }

                LaunchedEffect(settings.volumeMax) {
                    clampVolumeToMax()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    KioskNavHost(
                        navController = navController,
                        settingsRepository = app.settingsRepository,
                        onUserInteraction = ::resetIdleTimer,
                        onExitKiosk = {
                            app.kioskManager.exitKioskMode(this@MainActivity)
                            kioskLockEnabled = false
                            lifecycleScope.launch { app.settingsRepository.setKioskLockEnabled(false) }
                        },
                        onReenterKiosk = {
                            kioskLockEnabled = true
                            app.kioskManager.enterKioskMode(this@MainActivity)
                            lifecycleScope.launch { app.settingsRepository.setKioskLockEnabled(true) }
                        },
                        onReboot = { app.kioskManager.rebootDevice() },
                        onReleaseDeviceOwner = { app.kioskManager.clearDeviceOwner(this@MainActivity) }
                    )

                    StatusOverlay(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                    )

                    pendingUpdateConfirmation?.let { manifest ->
                        AlertDialog(
                            onDismissRequest = { pendingUpdateConfirmation = null },
                            title = { Text("새 업데이트가 있어요") },
                            text = {
                                Text("버전 ${manifest.versionName} (code ${manifest.versionCode})으로 업데이트할까요?\n적용하면 앱이 잠시 재시작됩니다.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingUpdateConfirmation = null
                                    lifecycleScope.launch { updater.installConfirmed(manifest) }
                                }) { Text("지금 업데이트") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingUpdateConfirmation = null }) { Text("나중에") }
                            }
                        )
                    }

                    // 관리자가 "물어보고 받기"로 보낸 영상 — 동의해야 다운로드가 시작된다.
                    // "나중에"를 누르면 대기열에 남아 다음 체크인(10분) 때 다시 물어본다.
                    pendingVideoConsent?.let { videos ->
                        AlertDialog(
                            onDismissRequest = { pendingVideoConsent = null },
                            title = { Text("새 영상이 도착했어요") },
                            text = {
                                val totalMb = videos.sumOf { it.size } / (1024 * 1024)
                                Text(
                                    "관리자가 영상 ${videos.size}개(${totalMb}MB)를 보냈어요. 지금 받을까요?\n\n" +
                                        videos.joinToString("\n") { "· ${it.name}" }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    pendingVideoConsent = null
                                    lifecycleScope.launch { updater.downloadVideosConfirmed(videos) }
                                }) { Text("지금 받기") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingVideoConsent = null }) { Text("나중에") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProvisioningIntent(intent)
    }

    /**
     * 프로비저닝(태블릿 세팅 스크립트)에서 넘긴 도서관 주소/기관명을 이 기기 설정에 반영한다.
     *   am start ... --es kiosk_start_url "https://splib.dobedub.com/home" --es kiosk_label "splib"
     * 도서관마다 서브도메인이 다르므로 기기별로 이 값을 심는다. 허용 도메인은 URL 호스트에서 자동 도출.
     */
    private fun handleProvisioningIntent(intent: android.content.Intent?) {
        val url = intent?.getStringExtra(EXTRA_START_URL)?.trim()
        val label = intent?.getStringExtra(EXTRA_LABEL)?.trim()
        if (url.isNullOrBlank() && label.isNullOrBlank()) return
        lifecycleScope.launch {
            if (!url.isNullOrBlank()) {
                app.settingsRepository.setStartUrl(url)
                android.net.Uri.parse(url).authority?.takeIf { it.isNotBlank() }?.let { host ->
                    app.settingsRepository.setAllowedDomains(listOf(host))
                }
            }
            if (!label.isNullOrBlank()) {
                app.settingsRepository.setInstitutionLabel(label)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (kioskLockEnabled) {
            app.kioskManager.enterKioskMode(this)
        }
        resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(idleRunnable)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    /** 관리자 설정의 최대 볼륨(%)을 넘지 못하도록 볼륨 버튼을 직접 가로챈다. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            resetIdleTimer()
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cap = (volumeMaxPercent.coerceIn(0, 100) / 100f * maxVolume).toInt()
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            val target = (current + delta).coerceIn(0, cap)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun clampVolumeToMax() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cap = (volumeMaxPercent.coerceIn(0, 100) / 100f * maxVolume).toInt()
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current > cap) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
        }
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeoutMillis)
    }

    private fun returnToHomeIfIdle() {
        // 감상 중(동영상 재생, 웹뷰 오디오 재생)은 무조작으로 치지 않는다 — 이 판정이 터치만
        // 봐서, 무조작 시간(기본 5분)보다 긴 영상을 얌전히 보던 아이가 재생 도중 홈으로
        // 튕겼다(서비스_완성도_검토.md §1-1). 타이머만 다시 걸고 물러난다.
        if (MediaPlaybackState.isWatching()) {
            resetIdleTimer()
            return
        }
        val nav = navController ?: return
        if (nav.currentDestination?.route != Routes.HOME) {
            clearWebSession()
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = true }
            }
        }
        resetIdleTimer()
    }

    /**
     * 시작 직후 1회 + AppUpdater.CHECKIN_INTERVAL_MS 마다 서버에 체크인하고, 새 버전이 있으면 홈 화면 유휴 상태일 때만 설치한다
     * (웹툰/영상 재생 중 갑작스러운 재시작 방지). 설치가 시작되면 프로세스가 재시작되며 키오스크 홈으로 복귀한다.
     */
    private fun startPeriodicUpdateChecks() {
        lifecycleScope.launch {
            delay(UPDATE_INITIAL_DELAY_MS)
            while (true) {
                try {
                    val result = updater.runOnce(
                        canInstallNow = { navController?.currentDestination?.route == Routes.HOME },
                        // Compose 스냅샷 상태는 어느 스레드에서 써도 안전하다(IO 디스패처에서 호출됨).
                        onVideosAwaitingConsent = { videos -> pendingVideoConsent = videos }
                    )
                    if (result is AppUpdater.Result.NeedsConfirmation) {
                        pendingUpdateConfirmation = result.manifest
                    }
                } catch (_: Exception) {
                    // 네트워크 오류 등은 다음 주기에 재시도
                }
                // 단순 delay 가 아니라 서버 long-poll 에 매달린 대기 — 관리자가 "지금 바로"
                // 지시를 내리면 주기를 기다리지 않고 몇 초 안에 다음 체크인이 일어난다.
                // 구서버/네트워크 오류면 내부에서 남은 시간만큼 자고 나온다(기존 동작과 동일).
                updater.waitForWake(AppUpdater.CHECKIN_INTERVAL_MS)
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
