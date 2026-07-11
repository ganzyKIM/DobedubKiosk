package com.dobedub.kiosk.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner 지정 대상이 되는 관리자 리시버.
 * `adb shell dpm set-device-owner com.dobedub.kiosk/.kiosk.AdminReceiver` 로 지정한다.
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }
}
