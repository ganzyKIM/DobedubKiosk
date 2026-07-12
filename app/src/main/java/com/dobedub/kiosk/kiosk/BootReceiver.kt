package com.dobedub.kiosk.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dobedub.kiosk.MainActivity

/**
 * 재부팅 후 자동으로 키오스크 화면(MainActivity)을 띄운다.
 * persistent preferred HOME 설정이 있어도, 일부 기기에서 첫 화면 전환이 지연되는 경우를 보완한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }
}
