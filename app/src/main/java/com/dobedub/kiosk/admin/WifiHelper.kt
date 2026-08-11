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

/**
 * Device Owner 권한으로 Wi-Fi를 관리한다.
 * 일반 앱은 Android 10+에서 이 API들이 막혀 있지만, Device Owner 앱은 예외적으로 허용된다.
 * 키오스크 잠금 중에는 DISALLOW_CONFIG_WIFI 제한이 걸려 있으므로, 연결 시도 직전에만 잠깐 풀었다가
 * 시도 후 원래 상태로 되돌린다.
 */
object WifiHelper {

    fun currentStatus(context: Context): String {
        val wifiManager = wifiManager(context)
        if (!wifiManager.isWifiEnabled) return "Wi-Fi 꺼짐"
        @Suppress("DEPRECATION")
        val ssid = wifiManager.connectionInfo?.ssid?.trim('"')
        return if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") "연결 안 됨" else "연결됨: $ssid"
    }

    @Suppress("DEPRECATION")
    fun setWifiEnabled(context: Context, enabled: Boolean) {
        wifiManager(context).isWifiEnabled = enabled
    }

    /** 스캔 결과 한 줄. [secured] 가 false 면 비밀번호 없이 붙는 개방형 네트워크. */
    data class Network(val ssid: String, val level: Int, val secured: Boolean)

    /**
     * 주변 Wi-Fi 목록. Android 10+에서는 위치 권한 + 위치 서비스가 모두 켜져 있어야
     * `scanResults` 가 채워진다 — 둘 중 하나만 빠져도 예외 없이 **빈 목록**이 와서
     * "주변에 AP가 없다"와 구분이 안 된다. 그래서 읽기 전에 [ensureScanPrerequisites] 로
     * Device Owner 권한을 써서 두 조건을 직접 갖춰둔다.
     *
     * 같은 SSID의 여러 AP(2.4/5GHz, 메시)는 신호가 가장 센 것 하나로 접는다.
     */
    fun scan(context: Context): List<Network> {
        ensureScanPrerequisites(context)
        val wifiManager = wifiManager(context)
        if (!wifiManager.isWifiEnabled) return emptyList()
        runCatching { wifiManager.startScan() }   // 스로틀링되면 직전 캐시 결과가 그대로 온다
        val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
        return results
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
     */
    private fun ensureScanPrerequisites(context: Context) {
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
    fun connect(context: Context, ssid: String, password: String, isOpenNetwork: Boolean): Boolean {
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
            return success
        } finally {
            if (wasRestricted) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
            }
        }
    }

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
}
