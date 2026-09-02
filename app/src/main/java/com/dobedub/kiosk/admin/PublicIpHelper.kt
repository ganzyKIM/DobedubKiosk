package com.dobedub.kiosk.admin

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 이 기기의 **공인 IP**. "태블릿이 어디에 있나"를 알려주는, 실내에서도 반드시 바뀌는 신호다.
 *
 * 왜 필요한가: 좌표(GPS/NLP)는 납품 태블릿에서 거의 갱신되지 않는다 — 구글 위치 정확도가
 * 꺼진 채 나가고 GPS 는 하늘이 보여야 잡히므로, 한 번 주운 좌표가 몇 날이고 그대로 남는다.
 * 그래서 기기를 다른 장소로 옮겨도 지도가 안 움직인다(실측: 집에서 잡은 좌표가 외출 후에도
 * 유지됨). 공인 IP 는 망이 바뀌면 반드시 바뀌므로 "옮겨졌다"를 확실히 드러낸다.
 *
 * 서버는 이 값을 볼 수 없다 — 체크인이 NetBird 를 지나오므로 서버에는 100.x 오버레이
 * 주소만 보인다. 그래서 기기가 직접 알아내 보고해야 한다.
 *
 * 설계 원칙:
 *  - **체크인을 절대 지연시키지 않는다.** 캐시된 값을 즉시 돌려주고 갱신은 백그라운드로만.
 *    (좌표에서 같은 원칙을 쓴다 — LocationHelper 참조)
 *  - 조회처는 IP 문자열만 돌려주는 곳을 쓴다. 위치 조회는 서버/운영자 쪽에서 한다.
 */
object PublicIpHelper {

    private const val TAG = "PublicIpHelper"

    /** 이 시간이 지나면 다시 확인한다. 망을 옮기면 그만큼 안에 새 값이 잡힌다. */
    private const val REFRESH_AFTER_MS = 30 * 60 * 1000L

    /** IP 문자열만 평문으로 돌려주는 엔드포인트. 위치 정보는 여기서 받지 않는다. */
    private const val ECHO_URL = "https://api.ipify.org"

    @Volatile private var cached: String? = null
    @Volatile private var fetchedAtMs: Long = 0
    private val inFlight = AtomicBoolean(false)

    /** 마지막으로 확인한 공인 IP. 아직 한 번도 못 받았으면 null. */
    fun lastKnown(): String? = cached

    /**
     * 오래됐으면 갱신을 걸어둔다. 결과를 기다리지 않는다 — 받아둔 값은 다음 체크인이 싣는다.
     * 겹쳐 도는 것을 막아 체크인마다 요청이 쌓이지 않게 한다.
     */
    fun refreshInBackground() {
        if (cached != null && System.currentTimeMillis() - fetchedAtMs < REFRESH_AFTER_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        Thread {
            try {
                val conn = (URL(ECHO_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                conn.disconnect()
                // 돌려받은 것이 정말 IP 인지만 확인한다(오류 페이지·리다이렉트 본문 방지).
                if (body.isNotEmpty() && body.length <= 45 && body.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) {
                    if (body != cached) Log.i(TAG, "공인 IP 변경: ${cached ?: "(없음)"} → $body")
                    cached = body
                    fetchedAtMs = System.currentTimeMillis()
                } else {
                    Log.w(TAG, "공인 IP 응답 형식이 이상해 무시: ${body.take(40)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "공인 IP 조회 실패: ${e.message}")   // 인터넷이 없을 수도 있다. 다음에 다시.
            } finally {
                inFlight.set(false)
            }
        }.start()
    }
}
