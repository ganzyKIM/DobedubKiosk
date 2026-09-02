package com.dobedub.kiosk.admin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 기기의 공인 IP. 기기 이동을 알아채는 용도다.
 *
 * 좌표는 납품 기기(NLP 꺼짐)에서 실내 갱신이 안 돼 첫 장소 값이 남지만, 공인 IP 는 망을
 * 옮기면 반드시 바뀐다. 체크인이 NetBird 를 경유해 서버는 오버레이 주소만 보므로 기기가
 * 직접 알아내 보고한다.
 *
 * LocationHelper 와 같은 원칙: 체크인을 지연시키지 않는다. 캐시를 즉시 돌려주고 갱신은
 * 백그라운드에서만 한다.
 */
object PublicIpHelper {

    private const val TAG = "PublicIpHelper"
    private const val REFRESH_AFTER_MS = 30 * 60 * 1000L
    /** IP 문자열만 평문으로 돌려주는 엔드포인트. */
    private const val ECHO_URL = "https://api.ipify.org"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    @Volatile private var cached: String? = null
    @Volatile private var fetchedAtMs = 0L

    fun lastKnown(): String? = cached

    /** 오래됐으면 갱신을 걸어둔다. 결과는 기다리지 않는다. */
    fun refreshInBackground() {
        if (cached != null && System.currentTimeMillis() - fetchedAtMs < REFRESH_AFTER_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ip = fetch()
                if (ip != null) {
                    if (ip != cached) Log.i(TAG, "공인 IP: ${cached ?: "(없음)"} → $ip")
                    cached = ip
                    fetchedAtMs = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.w(TAG, "공인 IP 조회 실패: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun fetch(): String? {
        val conn = (URL(ECHO_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            // 오류 페이지나 리다이렉트 본문을 IP 로 오인하지 않게 형식만 확인한다.
            body.takeIf { it.length in 7..45 && it.all { c -> c.isDigit() || c == '.' || c == ':' || c in 'a'..'f' || c in 'A'..'F' } }
        } finally {
            conn.disconnect()
        }
    }
}
