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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    data class Manifest(
        val update: Boolean,
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val sha256: String = "",
        val size: Long = 0,
        /** 관리자가 이 기기에 "업데이트 하시겠어요?" 확인창을 요청했는지(백오피스 강제 알림). */
        val promptUpdate: Boolean = false
    )

    sealed class Result {
        data class UpToDate(val serverVersion: Int) : Result()
        data class Updating(val toVersion: Int) : Result()
        data class Deferred(val toVersion: Int) : Result()   // 새 버전 있으나 지금 설치는 미룸(사용 중)
        /** 관리자가 강제 알림을 요청한 새 버전 — 화면에 확인창을 띄워 사용자 동의를 받아야 한다. */
        data class NeedsConfirmation(val manifest: Manifest) : Result()
        data class Failed(val reason: String) : Result()
        object NoServer : Result()
    }

    /**
     * 체크인 + 필요 시 업데이트까지 한 번 수행. UI/스케줄러에서 호출.
     * @param canInstallNow 새 버전을 지금 설치해도 되는지(예: 홈 화면 유휴 상태). false면 설치를 다음 주기로 미룬다.
     *                      수동(관리자 버튼) 실행 시엔 항상 true를 넘긴다.
     */
    suspend fun runOnce(canInstallNow: () -> Boolean = { true }): Result = withContext(Dispatchers.IO) {
        val s = settings.currentSettings()
        val baseUrl = s.fleetServerUrl.ifBlank { BuildConfig.FLEET_SERVER_URL }.trimEnd('/')
        if (baseUrl.isBlank()) return@withContext Result.NoServer

        val manifest = try {
            checkIn(baseUrl, s)
        } catch (e: Exception) {
            Log.w(TAG, "check-in 실패: ${e.message}")
            return@withContext Result.Failed("서버 접속 실패: ${e.message}")
        }

        if (!manifest.update || manifest.versionCode <= BuildConfig.VERSION_CODE) {
            return@withContext Result.UpToDate(manifest.versionCode)
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
        return@withContext performInstall(manifest)
    }

    /** 확인창에서 사용자가 "지금 업데이트"를 눌렀을 때 호출 — 이미 받아둔 manifest로 바로 설치를 진행한다. */
    suspend fun installConfirmed(manifest: Manifest): Result = withContext(Dispatchers.IO) {
        performInstall(manifest)
    }

    private fun performInstall(manifest: Manifest): Result = try {
        val apk = downloadApk(manifest)
        if (!verifySha256(apk, manifest.sha256)) {
            apk.delete()
            Result.Failed("APK 체크섬 불일치 — 설치 중단")
        } else {
            installApk(apk)
            Result.Updating(manifest.versionCode)
        }
    } catch (e: Exception) {
        Log.e(TAG, "업데이트 실패", e)
        Result.Failed("업데이트 실패: ${e.message}")
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
        val toPush = json.optJSONArray("pushVideos")
        if (toPush != null) {
            for (i in 0 until toPush.length()) {
                val item = toPush.optJSONObject(i) ?: continue
                val name = item.optString("name", "")
                val url = item.optString("url", "")
                if (name.isBlank() || url.isBlank()) continue
                try {
                    downloadVideo(url, name, item.optString("sha256", ""))
                } catch (e: Exception) {
                    Log.w(TAG, "영상 다운로드 실패($name): ${e.message}")
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
            promptUpdate = json.optBoolean("promptUpdate", false)
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
    private fun downloadVideo(url: String, name: String, expectedSha256: String) {
        if (videoRepo.inventory().any { it.first == name }) return

        val partial = videoRepo.beginVideoDownload(name) ?: return
        if (partial.exists()) partial.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("영상 다운로드 HTTP $code")
            conn.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }

        if (expectedSha256.isNotBlank() && !verifySha256(partial, expectedSha256)) {
            partial.delete()
            throw RuntimeException("영상 체크섬 불일치: $name")
        }
        if (!videoRepo.commitVideoDownload(partial, name)) {
            partial.delete()
            throw RuntimeException("영상 저장 실패: $name")
        }
        Log.i(TAG, "영상 다운로드 완료: $name")
    }

    private fun downloadApk(manifest: Manifest): File {
        val url = manifest.apkUrl
        val out = File(context.cacheDir, "update-${manifest.versionCode}.apk")
        if (out.exists()) out.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            if (DEVICE_TOKEN.isNotBlank()) setRequestProperty("X-Kiosk-Token", DEVICE_TOKEN)
        }
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); throw RuntimeException("APK 다운로드 HTTP $code") }
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        Log.i(TAG, "APK 다운로드 완료: ${out.length()} bytes")
        return out
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
