package com.dobedub.kiosk.admin

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/**
 * 기기가 어느 도서관에 있는지 백오피스에서 확인하기 위한 위치 보고용 헬퍼.
 *
 * 새 의존성(play-services-location)을 붙이지 않고 플랫폼 [LocationManager] 만 쓴다.
 * 실내 고정 설치라 GPS는 거의 안 잡히고, 실제로 값을 주는 건 Wi-Fi 기반 network provider다.
 *
 * 체크인을 위치 확보가 될 때까지 붙잡아두지 않는다 — 마지막으로 알려진 좌표를 즉시 보내고,
 * 갱신은 백그라운드로 걸어둬 **다음 체크인**이 최신값을 싣게 한다. 30분 주기라 이 정도면
 * 충분하고, 위치가 안 잡히는 곳에서 체크인 자체가 지연되는 일이 없다.
 */
object LocationHelper {

    private const val TAG = "LocationHelper"

    /** 이보다 오래된 좌표는 갱신을 한 번 더 걸어둔다(고정 설치라 자주 움직이지 않는다). */
    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Float, val atMillis: Long)

    /** 각 provider의 마지막 좌표 중 가장 최근 것. 권한이 없거나 아직 한 번도 못 잡았으면 null. */
    fun lastKnown(context: Context): Fix? {
        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // passive 도 읽는다 — 다른 앱이 받아둔 좌표가 여기 남아 있는 경우가 많다.
        val best = enabledProviders(lm).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime) ?: return null
        return Fix(best.latitude, best.longitude, best.accuracy, best.time)
    }

    /**
     * 좌표가 없거나 오래됐으면 단발성 갱신을 걸어둔다. 결과를 기다리지 않고 바로 반환한다 —
     * 받은 값은 시스템이 last known 으로 보관하므로 다음 체크인이 알아서 집어간다.
     */
    @Suppress("DEPRECATION") // requestSingleUpdate: API 30에서 deprecated 지만 minSdk 29 라 이게 가장 단순하다
    fun refreshInBackground(context: Context) {
        val current = lastKnown(context)
        if (current != null && System.currentTimeMillis() - current.atMillis < STALE_AFTER_MS) return

        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        for (provider in enabledProviders(lm)) {
            // passive 는 스스로 측위하지 않는다 — 여기에 요청해봐야 아무 일도 안 일어난다.
            if (provider == LocationManager.PASSIVE_PROVIDER) continue
            runCatching {
                lm.requestSingleUpdate(provider, { loc ->
                    Log.i(TAG, "위치 갱신됨($provider): ${loc.latitude}, ${loc.longitude} ±${loc.accuracy}m")
                }, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "위치 갱신 요청 실패($provider): ${it.message}") }
        }
    }

    /**
     * 이 기기에서 실제로 쓸 수 있는 provider 목록을 시스템에 물어본다.
     *
     * 상수를 박아두면 안 된다 — TB-J606F 에는 `network` provider 가 **등록조차 안 돼 있고**
     * Play 서비스가 `fused` 로 대신한다. 처음에 `network`/`gps` 만 하드코딩했다가 요청이
     * 전부 GPS 로만 나갔고, 실내라 영원히 fix 가 안 잡혔다.
     */
    private fun enabledProviders(lm: LocationManager): List<String> =
        runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
}
