package com.dobedub.kiosk.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 상태바를 완전히 숨긴 키오스크 화면에서도 시간/배터리를 확인할 수 있도록,
 * 모든 화면 위에 항상 떠 있는 작은 오버레이. 화면 우측 상단에 고정한다.
 */
@Composable
fun StatusOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var timeText by remember { mutableStateOf(formatCurrentTime()) }
    var batteryPercent by remember { mutableIntStateOf(readBatteryPercent(context)) }
    var isCharging by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            timeText = formatCurrentTime()
            delay(30_000)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPercent = level * 100 / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = timeText, color = Color.White, fontSize = 12.sp)
        Icon(
            imageVector = batteryIconFor(batteryPercent, isCharging),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(start = 8.dp).size(14.dp)
        )
        Text(
            text = "$batteryPercent%",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

private fun formatCurrentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

private fun readBatteryPercent(context: Context): Int {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
}

private fun batteryIconFor(percent: Int, charging: Boolean) = when {
    charging -> Icons.Filled.BatteryChargingFull
    percent >= 90 -> Icons.Filled.BatteryFull
    percent >= 60 -> Icons.Filled.Battery6Bar
    percent >= 40 -> Icons.Filled.Battery5Bar
    percent >= 20 -> Icons.Filled.Battery3Bar
    percent >= 10 -> Icons.Filled.Battery1Bar
    else -> Icons.Filled.Battery0Bar
}
