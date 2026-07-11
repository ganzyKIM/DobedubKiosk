package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.components.BackTopBar

/** 기기 설정: 밝기, 최대 볼륨, 무조작 복귀 시간, 다음 영상 자동재생. */
@Composable
fun AdminDeviceScreen(
    settings: com.dobedub.kiosk.data.KioskSettings,
    onIdleTimeoutChange: (Int) -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("기기 설정", style = MaterialTheme.typography.headlineMedium)

        Text("무조작 복귀 시간: ${settings.idleTimeoutMinutes}분")
        Slider(
            value = settings.idleTimeoutMinutes.toFloat(),
            onValueChange = { onIdleTimeoutChange(it.toInt().coerceIn(1, 30)) },
            valueRange = 1f..30f,
            steps = 28,
            modifier = Modifier.fillMaxWidth()
        )

        Text("최대 볼륨: ${settings.volumeMax}%")
        Slider(
            value = settings.volumeMax.toFloat(),
            onValueChange = { onVolumeChange(it.toInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("화면 밝기: ${settings.brightness}%")
        Slider(
            value = settings.brightness.toFloat(),
            onValueChange = { onBrightnessChange(it.toInt().coerceIn(10, 100)) },
            valueRange = 10f..100f,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("영상 종료 후 다음 영상 자동재생")
            Switch(
                checked = settings.autoPlayNext,
                onCheckedChange = onAutoPlayChange,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}
