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
import com.dobedub.kiosk.data.KioskSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 함대 관리 서버와 통신해 (1) 기기 상태를 체크인하고 (2) 새 버전이 있으면 조용히 설치한다.
 *
 * 무인 설치는 이 앱이 **Device Owner** 이기 때문에 가능하다(PackageInstaller commit 시 사용자 확인창 없음).
 * 자기 자신을 덮어 설치하면 프로세스가 종료되지만, Device Owner 의 영속 HOME 설정 덕분에
 * 시스템이 곧바로 키오스크 홈(MainActivity)을 다시 띄운다.
 */
class AppUpdater(private val context: Context) {

    private val settings = KioskSettingsRepository(context)

    data class Manifest(
        val update: Boolean,
        val versionCode: Int = 0,
        val versionName: String = "",
        val apkUrl: String = "",
        val sha256: String = "",
        val size: Long = 0
    )

    sealed class Result {
        data class UpToDate(val serverVersion: Int) : Result()
        data class Updating(val toVersion: Int) : Result()
        data class Deferred(val toVersion: Int) : Result()   // 새 버전 있으나 지금 설치는 미룸(사용 중)
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
            checkIn(baseUrl, s.institutionLabel, s.startUrl)
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

        Log.i(TAG, "새 버전 발견: code ${manifest.versionCode} (현재 ${BuildConfig.VERSION_CODE})")
        return@withContext try {
            val apk = downloadApk(manifest, baseUrl)
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
    }

    private fun checkIn(baseUrl: String, label: String, startUrl: String): Manifest {
        val payload = JSONObject().apply {
            put("deviceId", deviceId())
            put("model", Build.MODEL)
            put("serial", serialOrNull() ?: JSONObject.NULL)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("versionName", BuildConfig.VERSION_NAME)
            put("battery", batteryPercent())
            put("kioskLocked", isLockTaskActive())
            put("startUrl", startUrl)
            put("appLabel", label)
        }

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
        return Manifest(
            update = json.optBoolean("update", false),
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            apkUrl = json.optString("apkUrl", ""),
            sha256 = json.optString("sha256", ""),
            size = json.optLong("size", 0)
        )
    }

    private fun downloadApk(manifest: Manifest, baseUrl: String): File {
        val url = manifest.apkUrl.ifBlank { "$baseUrl/download/app.apk" }
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
    }
}
