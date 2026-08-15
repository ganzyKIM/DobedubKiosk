package com.dobedub.kiosk.update

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dobedub.kiosk.BuildConfig
import com.dobedub.kiosk.admin.LocationHelper
import com.dobedub.kiosk.admin.WifiHelper
import com.dobedub.kiosk.data.KioskSettings
import com.dobedub.kiosk.data.KioskSettingsRepository
import com.dobedub.kiosk.manual.ManualRepository
import com.dobedub.kiosk.video.VideoRepository
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 함대 관리 서버와 통신해 (1) 기기 상태를 체크인하고 (2) 새 버전이 있으면 조용히(또는
 * 관리자가 요청했다면 확인창을 띄운 뒤) 설치하고 (3) 백오피스가 지시한 영상 삭제/배포를
 * 수행한다.
 *
 * 무인 설치는 이 앱이 **Device Owner** 이기 때문에 가능하다(PackageInstaller commit 시 사용자 확인창 없음).
 * 자기 자신을 덮어 설치하면 프로세스가 종료되지만, Device Owner 의 영속 HOME 설정 덕분에
 * 시스템이 곧바로 키오스크 홈(MainActivity)을 다시 띄운다.
 */
class AppUpdater(private val context: Context) {

    private val settings = KioskSettingsRepository(context)
    private val videoRepo = VideoRepository(context)
    private val manualRepo = ManualRepository(context)
    private val kiosk by lazy { com.dobedub.kiosk.kiosk.KioskManager(context) }

    /** 확인/설치/수신이 겹쳐 돌지 않게 하는 재진입 가드(연타·자동주기와 수동 버튼 충돌 방지). */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    data class Manifest(
        val update: Boolean,
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val sha256: String = "",
        val size: Long = 0,
        /** 관리자가 이 기기에 "업데이트 하시겠어요?" 확인창을 요청했는지(백오피스 강제 알림). */
        val promptUpdate: Boolean = false,
        /** 관리자의 명시적 "즉시 업데이트" — 사용(재생) 중이어도 바로 설치한다. promptUpdate 보다 우선. */
        val forceUpdate: Boolean = false,
        /** "기기에서 물어보고 받기"로 온 영상들 — 화면에서 동의를 받은 뒤에만 내려받는다. */
        val askVideos: List<PendingVideo> = emptyList()
    )

    /** 백오피스가 배포 지시한 영상 하나. */
    data class PendingVideo(val name: String, val url: String, val sha256: String, val size: Long)

    sealed class Result {
        data class UpToDate(val serverVersion: Int) : Result()
        data class Updating(val toVersion: Int) : Result()
        data class Deferred(val toVersion: Int) : Result()   // 새 버전 있으나 지금 설치는 미룸(사용 중)
        /** 관리자가 강제 알림을 요청한 새 버전 — 화면에 확인창을 띄워 사용자 동의를 받아야 한다. */
        data class NeedsConfirmation(val manifest: Manifest) : Result()
        data class Failed(val reason: String) : Result()
        object NoServer : Result()
    }

    private suspend fun baseUrlOrNull(): String? {
        val s = settings.currentSettings()
        return s.fleetServerUrl.ifBlank { BuildConfig.FLEET_SERVER_URL }.trimEnd('/').ifBlank { null }
    }

    /**
     * 체크인 + 필요 시 업데이트까지 한 번 수행. UI/스케줄러에서 호출.
     * @param canInstallNow 새 버전을 지금 설치해도 되는지(예: 홈 화면 유휴 상태). false면 설치를 다음 주기로 미룬다.
     *                      수동(관리자 버튼) 실행 시엔 항상 true를 넘긴다.
     * @param onVideosAwaitingConsent "물어보고 받기"로 온 영상이 있을 때 호출 — 호출부가 화면에
     *                      확인창을 띄우고, 동의하면 downloadVideosConfirmed() 를 부른다.
     */
    suspend fun runOnce(
        canInstallNow: () -> Boolean = { true },
        onVideosAwaitingConsent: (List<PendingVideo>) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        // 재진입 가드. 관리자 화면의 "업데이트 확인"을 연타하면(실사용에서 실제로 벌어졌다)
        // 이전 시도가 아직 APK/영상을 받는 중에 새 시도가 같은 임시 파일을 지우고 다시 쓰기
        // 시작해 서로를 망가뜨린다 — 체크섬 불일치로 전부 실패하는 악순환. 자동 주기 루프와
        // 수동 버튼이 겹치는 경우도 같다. 한 번에 하나만 돌게 막는다.
        if (!busy.compareAndSet(false, true)) {
            return@withContext Result.Failed("이미 확인/설치가 진행 중입니다. 잠시만 기다려주세요.")
        }
        try {
        val s = settings.currentSettings()
        val baseUrl = s.fleetServerUrl.ifBlank { BuildConfig.FLEET_SERVER_URL }.trimEnd('/')
        if (baseUrl.isBlank()) return@withContext Result.NoServer

        val manifest = try {
            checkIn(baseUrl, s)
        } catch (e: Exception) {
            Log.w(TAG, "check-in 실패: ${e.message}")
            return@withContext Result.Failed("서버 접속 실패: ${e.message}")
        }

        if (manifest.askVideos.isNotEmpty()) onVideosAwaitingConsent(manifest.askVideos)

        if (!manifest.update || manifest.versionCode <= BuildConfig.VERSION_CODE) {
            return@withContext Result.UpToDate(manifest.versionCode)
        }

        // 관리자의 명시적 "즉시 업데이트" — 사용 중 보호(canInstallNow)와 확인창(promptUpdate)
        // 둘 다 건너뛴다. 재생을 끊는다는 사실을 대시보드 confirm 에서 이미 고지받고 눌렀다.
        if (manifest.forceUpdate) {
            Log.i(TAG, "관리자 강제 지시로 즉시 설치 — code ${manifest.versionCode}")
            return@withContext performInstall(manifest, baseUrl)
        }

        if (!canInstallNow()) {
            Log.i(TAG, "새 버전 code ${manifest.versionCode} 있으나 사용 중이라 설치 보류")
            return@withContext Result.Deferred(manifest.versionCode)
        }

        // 관리자가 이 기기에 강제 알림을 요청했다면 조용히 깔지 않고 화면에 확인창을 띄운다 —
        // 사용자가 동의해야 installConfirmed()가 호출되어 실제 설치가 진행된다.
        if (manifest.promptUpdate) {
            Log.i(TAG, "관리자가 업데이트 확인창 요청 — code ${manifest.versionCode}")
            return@withContext Result.NeedsConfirmation(manifest)
        }

        Log.i(TAG, "새 버전 발견: code ${manifest.versionCode} (현재 ${BuildConfig.VERSION_CODE})")
        return@withContext performInstall(manifest, baseUrl)
        } finally {
            busy.set(false)
        }
    }

    /** 확인창에서 사용자가 "지금 업데이트"를 눌렀을 때 호출 — 이미 받아둔 manifest로 바로 설치를 진행한다. */
    suspend fun installConfirmed(manifest: Manifest): Result = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) {
            return@withContext Result.Failed("이미 확인/설치가 진행 중입니다. 잠시만 기다려주세요.")
        }
        try {
            performInstall(manifest, baseUrlOrNull() ?: return@withContext Result.NoServer)
        } finally {
            busy.set(false)
        }
    }

    /** "물어보고 받기" 확인창에서 동의했을 때 호출 — 해당 영상들을 바로 내려받는다. */
    suspend fun downloadVideosConfirmed(videos: List<PendingVideo>): Unit = withContext(Dispatchers.IO) {
        val base = baseUrlOrNull() ?: return@withContext
        // 체크인(runOnce)과 겹치면 같은 영상을 두 시도가 동시에 받을 수 있다 — 재진입 가드가
        // 풀릴 때까지 잠깐 기다렸다 진행한다(다운로드 자체는 항목별 중복 검사가 있다).
        var waited = 0L
        while (!busy.compareAndSet(false, true)) {
            delay(500)
            waited += 500
            if (waited >= 60_000) { Log.w(TAG, "다른 작업이 오래 안 끝나 영상 수신을 포기"); return@withContext }
        }
        try {
            for (v in videos) {
                try {
                    downloadVideo(base, v)
                } catch (e: Exception) {
                    Log.w(TAG, "영상 다운로드 실패(${v.name}): ${e.message}")
                }
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * 다음 체크인까지 대기. 단순 delay 가 아니라 서버 long-poll(/api/poke)에 매달려 있어서,
     * 관리자가 "지금 바로" 지시(영상 push·즉시 업데이트)를 내리면 몇 초 안에 깨어나
     * 즉시 체크인하게 된다. 지시 내용 자체는 여전히 체크인 응답으로만 받는다.
     * 구서버(404)나 네트워크 오류면 남은 시간만큼 그냥 잔다 — 기존 주기로 자연 폴백.
     */
    suspend fun waitForWake(maxWaitMs: Long): Unit = withContext(Dispatchers.IO) {
        val base = baseUrlOrNull()
        if (base == null) { delay(maxWaitMs); return@withContext }
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) return@withContext
            try {
                val id = URLEncoder.encode(deviceId(), "UTF-8")
                val conn = (URL("$base/api/poke?deviceId=$id").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 65000   // 서버가 최대 50초 붙잡는다 — 그보다 넉넉히
                    if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
                }
                val code = conn.responseCode
                if (code == 404) {   // 구버전 서버 — long-poll 없음
                    conn.disconnect()
                    delay(remain)
                    return@withContext
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (JSONObject(body).optBoolean("checkinNow", false)) {
                    Log.i(TAG, "서버가 즉시 체크인 요청 — 깨어남")
                    return@withContext
                }
            } catch (e: Exception) {
                // 네트워크 오류/일시 단절 — 잠깐 쉬고 재시도. 남은 시간이 다 되면 정규 체크인.
                delay(minOf(30_000L, remain))
            }
        }
    }

    private fun performInstall(manifest: Manifest, baseUrl: String): Result = try {
        val apk = downloadApk(manifest, baseUrl)
        if (!verifySha256(apk, manifest.sha256)) {
            apk.delete()
            reportProgress(baseUrl, "apk", "v${manifest.versionName}", 0, manifest.size, "failed", "체크섬 불일치")
            Result.Failed("APK 체크섬 불일치 — 설치 중단")
        } else {
            reportProgress(baseUrl, "apk", "v${manifest.versionName}", manifest.size, manifest.size, "done", null)
            installApk(apk)
            Result.Updating(manifest.versionCode)
        }
    } catch (e: Exception) {
        Log.e(TAG, "업데이트 실패", e)
        reportProgress(baseUrl, "apk", "v${manifest.versionName}", 0, manifest.size, "failed", e.message)
        Result.Failed("업데이트 실패: ${e.message}")
    } finally {
        DownloadState.finish("apk", "v${manifest.versionName}")
    }

    private suspend fun checkIn(baseUrl: String, s: KioskSettings): Manifest {
        val videosJson = org.json.JSONArray().apply {
            videoRepo.inventory().forEach { (name, size) ->
                put(JSONObject().apply { put("name", name); put("size", size) })
            }
        }
        val payload = JSONObject().apply {
            put("deviceId", deviceId())
            put("model", Build.MODEL)
            put("serial", serialOrNull() ?: JSONObject.NULL)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("versionName", BuildConfig.VERSION_NAME)
            put("battery", batteryPercent())
            put("kioskLocked", isLockTaskActive())
            put("startUrl", s.startUrl)
            put("appLabel", s.institutionLabel)
            put("videos", videosJson)
            // 아래 두 값은 백오피스가 "지시가 실제로 먹혔는지" 확인하는 데 쓴다.
            // 이게 없으면 지시를 보낸 뒤 반영 여부를 알 길이 없어, 응답이 유실돼도 모른 채 지나간다.
            put("contactInfo", s.contactInfo)
            put("hasCustomPin", s.hasPinConfigured)
            // 이 기기의 체크인 주기. 서버가 "접속 중" 판정을 기기별로 하게 해서, 주기가
            // 다른 버전이 섞여 있어도 각자 올바르게 표시된다(전에는 전역 상수라 롤아웃
            // 중에 멀쩡한 구버전 기기가 전부 미접속으로 보였다).
            put("checkinIntervalMs", CHECKIN_INTERVAL_MS)
            // 현재 쓰는 함대 서버 주소 — setFleetUrl 원격 지시의 완료 판정 근거(서버는 이
            // 값이 override 와 같아질 때까지 지시를 계속 내린다).
            put("fleetUrl", baseUrl)
            // 재부팅 후 원격 관리가 살아 돌아올지를 미리 알려주는 유일한 신호.
            // 지정이 안 돼 있으면(null) 그 기기는 다음 재부팅에 연락이 끊긴다 — 재부팅
            // 전에 대시보드에서 보이게 하려고 보고한다(v2.5.2 사고의 재발 방지).
            put("alwaysOnVpn", kiosk.alwaysOnVpnPackage() ?: JSONObject.NULL)
            // 이 태블릿이 어느 도서관에 있는지 가려내기 위한 정보.
            // 접속 AP 는 항상 잡히고, 좌표는 NLP(Google 위치 정확도)가 켜진 기기에서만 나온다.
            WifiHelper.connectedAp(context)?.let { ap ->
                put("apSsid", ap.ssid)
                put("apBssid", ap.bssid)
            }
            LocationHelper.lastKnown(context)?.let { fix ->
                put("lat", fix.lat)
                put("lng", fix.lng)
                put("locAccuracy", fix.accuracyM.toDouble())
                put("locatedAt", fix.atMillis)
            }
        }
        // 다음 체크인이 최신 좌표를 싣도록 갱신만 걸어둔다(결과는 기다리지 않는다).
        LocationHelper.refreshInBackground(context)

        val conn = (URL("$baseUrl/api/checkin").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code")

        val json = JSONObject(body)

        // 백오피스가 지시한 문의 연락처 덮어쓰기. 값이 이미 같으면 DataStore 를 건드리지 않는다
        // (체크인마다 쓰기가 발생하면 settingsFlow 가 매번 방출돼 화면이 불필요하게 재구성된다).
        val setContact = json.optString("setContact", "")
        if (setContact.isNotBlank() && setContact != s.contactInfo) {
            settings.setContactInfo(setContact)
            Log.i(TAG, "백오피스 지시로 문의 연락처 변경: $setContact")
        }

        // 관리자가 PIN을 분실했을 때의 원격 초기화. 해시를 지우면 기본값 0000 으로 되돌아간다.
        // 서버는 다음 체크인의 hasCustomPin=false 를 보고 지시가 먹혔음을 확인하고 플래그를 내린다.
        if (json.optBoolean("resetPin", false) && s.hasPinConfigured) {
            settings.clearAdminPin()
            Log.i(TAG, "백오피스 지시로 관리자 PIN 초기화(0000)")
        }

        // 백오피스가 지시한 함대 서버 주소 변경(기존 태블릿을 NetBird 주소로 이관하는 용도).
        // 새 주소의 /health 가 실제로 응답할 때만 저장한다 — 오타 주소를 그대로 믿고 저장하면
        // 그 순간부터 기기가 영영 연락 두절이 된다. 완료 판정은 report 기반: 다음 체크인
        // payload 의 fleetUrl 이 override 와 같아지면 서버가 지시를 내린 것으로 처리한다.
        val newFleetUrl = json.optString("setFleetUrl", "")
        if (newFleetUrl.isNotBlank() && newFleetUrl != baseUrl) {
            if (FleetServerDiscovery.probeUrl(newFleetUrl)) {
                settings.setFleetServerUrl(newFleetUrl)
                Log.i(TAG, "백오피스 지시로 함대 서버 주소 변경: $newFleetUrl")
            } else {
                Log.w(TAG, "서버 주소 변경 지시 무시 — 새 주소가 응답하지 않음: $newFleetUrl")
            }
        }

        // 원격 재부팅 — 설치 직후 검은 화면(문서화된 간헐 현상)을 현장 방문 없이 복구하는 용도.
        // 다른 지시와 달리 1회성(fire-and-forget)이다: 재부팅 여부를 기기가 보고로 증명할
        // 방법이 없고, 플래그가 남아 있으면 재부팅 무한 루프가 된다. 서버는 응답에 싣는 즉시
        // 플래그를 내리고, 함께 온 다른 지시들은 report 기반이라 재부팅 후 다시 내려온다.
        if (json.optBoolean("reboot", false)) {
            Log.i(TAG, "백오피스 지시로 재부팅")
            kiosk.rebootDevice()   // dpm.reboot — 여기서 프로세스가 끝난다
        }

        // 백오피스가 지시한 영상 삭제 실행(원격 영상 관리).
        val toDelete = json.optJSONArray("deleteVideos")
        if (toDelete != null) {
            for (i in 0 until toDelete.length()) {
                val name = toDelete.optString(i, "")
                if (name.isNotBlank() && videoRepo.deleteVideo(name)) {
                    Log.i(TAG, "백오피스 지시로 영상 삭제: $name")
                }
            }
        }

        // 백오피스가 지시한 영상 배포(다운로드) 실행. 파일 하나가 실패해도 나머지와
        // 이어지는 버전 체크에 영향 없도록 항목별로 예외를 흡수한다 — 영상은 크기가
        // 커서(수 GB) 실패 확률이 APK보다 높다.
        // ask=true("물어보고 받기")인 항목은 여기서 받지 않고 모아서 돌려준다 — 호출부가
        // 화면에 확인창을 띄우고, 동의하면 downloadVideosConfirmed()로 내려받는다.
        val toPush = json.optJSONArray("pushVideos")
        val askVideos = mutableListOf<PendingVideo>()
        if (toPush != null) {
            for (i in 0 until toPush.length()) {
                val item = toPush.optJSONObject(i) ?: continue
                val v = PendingVideo(
                    name = item.optString("name", ""),
                    url = item.optString("url", ""),
                    sha256 = item.optString("sha256", ""),
                    size = item.optLong("size", 0)
                )
                if (v.name.isBlank() || v.url.isBlank()) continue
                if (item.optBoolean("ask", false)) {
                    // 이미 보유 중이면 물어볼 것도 없다(서버가 다음 체크인에 확정 처리한다).
                    if (videoRepo.inventory().none { it.first == v.name }) askVideos += v
                } else {
                    try {
                        downloadVideo(baseUrl, v)
                    } catch (e: Exception) {
                        Log.w(TAG, "영상 다운로드 실패(${v.name}): ${e.message}")
                    }
                }
            }
        }

        // 홈 화면 이용안내 이미지를 백오피스가 지정한 세트와 똑같이 맞춘다.
        // 배열이 아예 없으면(구버전 서버) 손대지 않고, 빈 배열이면 지우고 내장본으로 돌아간다 —
        // 이 둘을 구분하지 않으면 서버를 되돌렸을 때 기기 이미지가 통째로 날아간다.
        val manualArr = json.optJSONArray("manualImages")
        if (manualArr != null) {
            val wanted = LinkedHashMap<String, Long>()
            for (i in 0 until manualArr.length()) {
                val item = manualArr.optJSONObject(i) ?: continue
                val name = item.optString("name", "")
                if (name.isNotBlank()) wanted[name] = item.optLong("size", -1)
            }
            val urls = (0 until manualArr.length()).mapNotNull { i ->
                manualArr.optJSONObject(i)?.let { it.optString("name", "") to it.optString("url", "") }
            }.toMap()
            try {
                val changed = manualRepo.sync(wanted) { name, dest ->
                    val url = urls[name].orEmpty()
                    if (url.isBlank()) throw RuntimeException("URL 없음")
                    downloadSmallFile(url, dest)
                }
                if (changed) Log.i(TAG, "이용안내 이미지 갱신됨 (${wanted.size}장)")
            } catch (e: Exception) {
                Log.w(TAG, "이용안내 이미지 동기화 실패: ${e.message}")
            }
        }

        // 백오피스에서 등록한 썸네일 동기화. 서버가 "이 기기가 보유한 영상 중 썸네일이 있는
        // 것"만 보내주므로, 여기서는 없는 것과 크기가 달라진 것(=교체됨)만 내려받는다.
        // 몇십 KB짜리라 sha 검증까지는 하지 않는다 — 깨져도 다음 체크인에 크기가 안 맞아 재수신.
        val thumbs = json.optJSONArray("thumbs")
        if (thumbs != null) {
            for (i in 0 until thumbs.length()) {
                val item = thumbs.optJSONObject(i) ?: continue
                val name = item.optString("name", "")
                val url = item.optString("url", "")
                if (name.isBlank() || url.isBlank()) continue
                val dest = videoRepo.thumbFileFor(name) ?: continue
                if (dest.exists() && dest.length() == item.optLong("size", -1)) continue
                try {
                    downloadSmallFile(url, dest)
                    // 화면이 열려 있어도 새 썸네일이 바로 보이게 epoch 를 올린다 — 목록
                    // 화면이 이를 구독해 재구성되고, VideoCard 가 파일 서명 변화를 보고
                    // 다시 읽는다. (update+finish 틱은 StateFlow conflation 에 삼켜졌다)
                    DownloadState.noteThumbChanged()
                    Log.i(TAG, "썸네일 내려받음: $name")
                } catch (e: Exception) {
                    Log.w(TAG, "썸네일 다운로드 실패($name): ${e.message}")
                }
            }
        }

        return Manifest(
            update = json.optBoolean("update", false),
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", ""),
            sha256 = json.optString("sha256", ""),
            size = json.optLong("size", 0),
            promptUpdate = json.optBoolean("promptUpdate", false),
            forceUpdate = json.optBoolean("forceUpdate", false),
            askVideos = askVideos
        )
    }

    /** 썸네일처럼 작은 파일 하나를 임시 파일로 받은 뒤 원자적으로 제자리에 놓는다. */
    private fun downloadSmallFile(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val tmp = File(dest.parentFile, "${dest.name}.downloading")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // 교체(덮어쓰기) rename 이 실패하는 파일시스템 대비 폴백
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw RuntimeException("rename 실패")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 이미 보유 중이면(=백오피스가 아직 확정 처리 전 재전송한 경우 등) 다시 받지 않는다. */
    private fun downloadVideo(baseUrl: String, v: PendingVideo) {
        if (videoRepo.inventory().any { it.first == v.name }) return

        val partial = videoRepo.beginVideoDownload(v.name) ?: return
        if (partial.exists()) partial.delete()

        try {
            val conn = (URL(v.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException("영상 다운로드 HTTP $code")
                val total = if (v.size > 0) v.size else conn.contentLengthLong
                copyWithProgress(conn.inputStream, partial, "video", v.name, total, baseUrl)
            } finally {
                conn.disconnect()
            }

            if (v.sha256.isNotBlank() && !verifySha256(partial, v.sha256)) {
                partial.delete()
                throw RuntimeException("영상 체크섬 불일치: ${v.name}")
            }
            if (!videoRepo.commitVideoDownload(partial, v.name)) {
                partial.delete()
                throw RuntimeException("영상 저장 실패: ${v.name}")
            }
            reportProgress(baseUrl, "video", v.name, v.size, v.size, "done", null)
            Log.i(TAG, "영상 다운로드 완료: ${v.name}")
        } catch (e: Exception) {
            reportProgress(baseUrl, "video", v.name, 0, v.size, "failed", e.message)
            throw e
        } finally {
            DownloadState.finish("video", v.name)
        }
    }

    private fun downloadApk(manifest: Manifest, baseUrl: String): File {
        val url = manifest.apkUrl
        val out = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (out.exists()) out.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("APK 다운로드 HTTP $code")
            val total = if (manifest.size > 0) manifest.size else conn.contentLengthLong
            copyWithProgress(conn.inputStream, out, "apk", "v${manifest.versionName}", total, baseUrl)
        } finally {
            conn.disconnect()
        }
        Log.i(TAG, "APK 다운로드 완료: ${out.length()} bytes")
        return out
    }

    /**
     * 스트림을 복사하면서 진행률을 DownloadState(기기 화면)와 서버(/api/progress, 대시보드)에
     * 보고한다. 보고는 1.5초 또는 5%p 마다 — 매 버퍼마다 보내면 다운로드 자체가 느려진다.
     * 서버 보고 실패는 무시한다(어차피 다운로드가 그 서버에서 오고 있다).
     */
    private fun copyWithProgress(
        input: InputStream, dest: File,
        kind: String, name: String, total: Long, baseUrl: String
    ) {
        val buf = ByteArray(64 * 1024)
        var received = 0L
        var lastAt = 0L
        var lastPct = -1
        DownloadState.update(kind, name, 0, total)
        input.use { src ->
            dest.outputStream().use { out ->
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    val now = SystemClock.elapsedRealtime()
                    val pct = if (total > 0) (received * 100 / total).toInt() else -1
                    if (now - lastAt >= 1500 || (pct >= 0 && pct >= lastPct + 5)) {
                        lastAt = now
                        lastPct = pct
                        DownloadState.update(kind, name, received, total)
                        reportProgress(baseUrl, kind, name, received, total, "downloading", null)
                    }
                }
            }
        }
    }

    /** 다운로드 진행률을 서버에 알린다(대시보드 표시용). 실패해도 다운로드에는 영향 없음. */
    private fun reportProgress(
        baseUrl: String, kind: String, name: String,
        received: Long, total: Long, status: String, error: String?
    ) {
        try {
            val conn = (URL("$baseUrl/api/progress").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
            }
            val payload = JSONObject().apply {
                put("deviceId", deviceId())
                put("kind", kind)
                put("name", name)
                put("received", received)
                put("total", total)
                put("status", status)
                if (error != null) put("error", error.take(200))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
        } catch (e: Exception) {
            // 진행률 보고는 부가 기능 — 조용히 넘어간다
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true // 서버가 해시를 주지 않으면 검증 생략
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(8192); var n = s.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = s.read(buf) }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        val ok = actual.equals(expected, ignoreCase = true)
        if (!ok) Log.e(TAG, "sha256 불일치: 기대 $expected / 실제 $actual")
        return ok
    }

    /** Device Owner 권한으로 사용자 확인 없이 APK 설치. */
    private fun installApk(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("kiosk.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "설치 세션 commit 완료 (session $sessionId) — 곧 앱이 재시작됩니다")
    }

    // ---------- 기기 정보 ----------

    private fun deviceId(): String =
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) { "unknown" }

    private fun serialOrNull(): String? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
        } catch (e: Exception) { null }?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }

    private fun batteryPercent(): Int =
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }

    private fun isLockTaskActive(): Boolean =
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } catch (e: Exception) { false }

    companion object {
        private const val TAG = "AppUpdater"
        /** 서버가 X-Kiosk-Token 을 요구하도록 설정했다면 여기에 같은 값을 넣는다(선택). */
        private const val DEVICE_TOKEN = ""

        /**
         * 체크인 주기. 체크인 payload 로 서버에 함께 보고하므로 **여기가 유일한 출처**다 —
         * 예전엔 앱과 서버에 같은 숫자를 따로 박아두고 "반드시 같이 고칠 것"이라고 주석만
         * 달아뒀는데, 실제로 어긋나서 멀쩡한 기기가 계속 "대기"로 표시된 적이 있다.
         * 이제 서버는 기기가 보고한 값으로 기기별 판정을 하므로, 주기가 서로 다른 버전이
         * 섞여 있어도 각자 올바르게 표시된다.
         *
         * 화면이 꺼져 있으면 doze 로 실제 간격은 이보다 길어진다(절전이지 고장이 아니다).
         * 자는 기기까지 정확히 맞추려면 AlarmManager.setExactAndAllowWhileIdle 로 가야 한다.
         */
        const val CHECKIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
