@file:Suppress("DEPRECATION")

package com.dobedub.kiosk.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
