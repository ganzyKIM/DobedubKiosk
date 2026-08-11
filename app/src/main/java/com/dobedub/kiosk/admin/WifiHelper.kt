@file:Suppress("DEPRECATION")

package com.dobedub.kiosk.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import com.dobedub.kiosk.kiosk.AdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device Owner 권한으로 Wi-Fi를 관리한다.
 * 일반 앱은 Android 10+에서 이 API들이 막혀 있지만, Device Owner 앱은 예외적으로 허용된다.
 * 키오스크 잠금 중에는 DISALLOW_CONFIG_WIFI 제한이 걸려 있으므로, 연결 시도 직전에만 잠깐 풀었다가
 * 시도 후 원래 상태로 되돌린다.
 *
 * **모든 공개 함수는 suspend 이고 내부에서 IO 디스패처로 넘어간다.** WifiManager/DevicePolicyManager
 * 호출은 전부 시스템 프로세스로 나가는 binder IPC 라 메인 스레드에서 부르면 화면이 그대로 멈춘다
 * (특히 `setLocationEnabled`, `startScan`). 호출부가 실수로 메인에서 부를 수 없도록 여기서 막는다.
 */
object WifiHelper {

    suspend fun currentStatus(context: Context): String = withContext(Dispatchers.IO) {
        val wifiManager = wifiManager(context)
        if (!wifiManager.isWifiEnabled) return@withContext "Wi-Fi 꺼짐"
        @Suppress("DEPRECATION")
        val ssid = wifiManager.connectionInfo?.ssid?.trim('"')
        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "연결 안 됨" else "연결됨: $ssid"
    }

    @Suppress("DEPRECATION")
    suspend fun setWifiEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        wifiManager(context).isWifiEnabled = enabled
        Unit
    }

    /** 스캔 결과 한 줄. [secured] 가 false 면 비밀번호 없이 붙는 개방형 네트워크. */
    data class Network(val ssid: String, val level: Int, val secured: Boolean)

    /** 현재 붙어 있는 AP. [bssid] 는 AP의 MAC 이라 같은 SSID가 여러 곳에 있어도 구분된다. */
    data class ConnectedAp(val ssid: String, val bssid: String)

    /**
     * 백오피스가 "이 태블릿이 어느 도서관에 있는지" 가려내는 데 쓰는 값.
     *
     * 좌표(GPS/NLP)보다 이쪽이 실무적으로 더 확실하다 — 납품 태블릿은 Google 위치 정확도(NLP)가
     * 꺼진 채로 나가서 실내에서는 좌표가 아예 안 잡히는데, 접속 AP는 항상 있다.
     */
    @Suppress("DEPRECATION")
    suspend fun connectedAp(context: Context): ConnectedAp? = withContext(Dispatchers.IO) {
        val info = runCatching { wifiManager(context).connectionInfo }.getOrNull()
            ?: return@withContext null
        val ssid = info.ssid?.trim('"').orEmpty()
        if (ssid.isEmpty() || ssid == "<unknown ssid>") return@withContext null
        val bssid = info.bssid.orEmpty()
        // 연결이 끊기는 중이면 BSSID 가 이 자리표시자로 온다 — 저장해봐야 쓸모없다.
        if (bssid.isEmpty() || bssid == "02:00:00:00:00:00") return@withContext null
        ConnectedAp(ssid, bssid)
    }

    /**
     * 주변 Wi-Fi 목록. Android 10+에서는 위치 권한 + 위치 서비스가 모두 켜져 있어야
     * `scanResults` 가 채워진다 — 둘 중 하나만 빠져도 예외 없이 **빈 목록**이 와서
     * "주변에 AP가 없다"와 구분이 안 된다. 그래서 읽기 전에 [ensureScanPrerequisites] 로
     * Device Owner 권한을 써서 두 조건을 직접 갖춰둔다.
     *
     * 같은 SSID의 여러 AP(2.4/5GHz, 메시)는 신호가 가장 센 것 하나로 접는다.
     */
    suspend fun scan(context: Context): List<Network> = withContext(Dispatchers.IO) {
        ensureScanPrerequisites(context)
        val wifiManager = wifiManager(context)
        if (!wifiManager.isWifiEnabled) return@withContext emptyList()
        runCatching { wifiManager.startScan() }   // 스로틀링되면 직전 캐시 결과가 그대로 온다
        val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
        results
            .mapNotNull { r ->
                val ssid = r.SSID?.trim()?.trim('"').orEmpty()
                if (ssid.isEmpty()) null   // 숨김 SSID 는 목록에 띄워도 고를 수가 없다
                else Network(ssid, r.level, isSecured(r.capabilities.orEmpty()))
            }
            .groupBy { it.ssid }
            .map { (_, dupes) -> dupes.maxBy { it.level } }
            .sortedByDescending { it.level }
    }

    /** WPA/WEP 등 어떤 형태로든 키가 필요한지. 아무것도 없으면 개방형. */
    private fun isSecured(capabilities: String): Boolean =
        listOf("WPA", "WEP", "PSK", "EAP", "SAE").any { capabilities.contains(it) }

    /**
     * 스캔이 실제로 결과를 내도록 (1) 위치 권한 (2) 위치 서비스를 Device Owner 권한으로 갖춘다.
     * 일반 앱이면 사용자에게 권한 팝업을 띄워야 하지만, 키오스크는 잠금 중이라 팝업을 띄울 수
     * 없다 — Device Owner 만 이렇게 조용히 자기 자신에게 부여할 수 있다.
     *
     * 이 두 호출은 각각 수백 ms 걸리는 데다 한 번 켜두면 계속 유지되므로 프로세스당 한 번만 한다.
     * 매 스캔마다 하면 "새로고침"을 누를 때마다 그만큼 기다리게 된다.
     */
    private fun ensureScanPrerequisites(context: Context) {
        if (!prerequisitesDone.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(appContext.packageName)) return
        val admin = AdminReceiver.componentName(appContext)
        runCatching {
            dpm.setPermissionGrantState(
                admin, appContext.packageName, Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
        // setLocationEnabled 는 API 30(Android 11)부터. 납품 기종(TB-J606F)이 여기 해당한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setLocationEnabled(admin, true) }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun connect(
        context: Context,
        ssid: String,
        password: String,
        isOpenNetwork: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val wifiManager = wifiManager(appContext)
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(appContext)
        val isDeviceOwner = dpm.isDeviceOwnerApp(appContext.packageName)

        val wasRestricted = isDeviceOwner &&
            dpm.getUserRestrictions(admin).containsKey(UserManager.DISALLOW_CONFIG_WIFI)
        if (wasRestricted) {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
        }

        try {
            if (!wifiManager.isWifiEnabled) {
                wifiManager.isWifiEnabled = true
            }

            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (isOpenNetwork) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }

            val netId = wifiManager.addNetwork(config)
            val success = netId != -1
            if (success) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
            }
            return@withContext success
        } finally {
            if (wasRestricted) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            }
        }
    }

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val prerequisitesDone = AtomicBoolean(false)
}
