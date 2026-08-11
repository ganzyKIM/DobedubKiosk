package com.dobedub.kiosk.update

import android.util.Log
import com.dobedub.kiosk.data.KioskSettingsRepository.Companion.DEFAULT_FLEET_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * 같은 공유기(LAN) 안에서 함대 서버를 자동으로 찾는다.
 *
 * 현장에서 가장 큰 불편이 "태블릿마다 긴 서버 주소를 손으로 치는 것"이었다. 같은 와이파이만
 * 쓰는 동안은 칠 필요가 없게, 기기 자신의 IP에서 /24 서브넷을 뽑아 전 대역에 `GET /health`를
 * 병렬로 던지고 `ok` 로 답하는 호스트를 서버로 잡는다(보통 2~3초).
 *
 * 한계: 같은 서브넷에서만 동작한다. 도서관에 나간 태블릿은 공인 주소가 필요하다.
 */
object FleetServerDiscovery {

    private const val TAG = "FleetDiscovery"
    private const val TIMEOUT_MS = 700

    /** 이 기기의 사설 IPv4 (예: 192.168.0.42). 못 찾으면 null. */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "로컬 IP 조회 실패: ${e.message}"); null
    }

    /** "192.168.0.42" → "192.168.0" (마지막 한 자리만 입력받기 위한 프리픽스) */
    fun subnetPrefix(): String? =
        localIpv4()?.substringBeforeLast('.', "")?.takeIf { it.count { c -> c == '.' } == 2 }

    /**
     * 서브넷 전체를 훑어 함대 서버를 찾는다. 찾으면 `http://<ip>:<port>` 형태로 반환.
     * 여러 대가 응답하면 첫 번째를 쓴다(현장에 서버가 둘일 일은 없다).
     */
    suspend fun discover(port: Int = DEFAULT_FLEET_PORT): String? = withContext(Dispatchers.IO) {
        val prefix = subnetPrefix() ?: return@withContext null
        Log.i(TAG, "서브넷 $prefix.0/24 검색 시작")

        val found = (1..254).map { host ->
            async {
                val ip = "$prefix.$host"
                if (probe(ip, port)) ip else null
            }
        }.awaitAll().filterNotNull()

        found.firstOrNull()?.let { "http://$it:$port" }
            .also { Log.i(TAG, if (it != null) "발견: $it" else "서브넷에서 서버를 못 찾음") }
    }

    /** 해당 호스트가 우리 함대 서버인지 확인 — /health 가 정확히 "ok" 를 돌려줘야 한다. */
    private fun probe(ip: String, port: Int): Boolean = try {
        val conn = (URL("http://$ip:$port/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }
        val ok = conn.responseCode == 200 &&
            conn.inputStream.bufferedReader().use { it.readText() }.trim() == "ok"
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false   // 대부분은 그냥 아무것도 없는 IP — 조용히 넘어간다
    }
}
