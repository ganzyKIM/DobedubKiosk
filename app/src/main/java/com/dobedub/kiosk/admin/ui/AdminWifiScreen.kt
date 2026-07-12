package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.admin.WifiHelper
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.LabelSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 키오스크 잠금을 해제하지 않고도 관리자가 Wi-Fi를 연결할 수 있는 화면.
 * Device Owner 권한으로 legacy WifiManager API를 사용한다(§9 리스크 6 대응).
 */
@Composable
fun AdminWifiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(WifiHelper.currentStatus(context)) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isOpenNetwork by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        status = WifiHelper.currentStatus(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("Wi-Fi 설정", style = MaterialTheme.typography.headlineMedium)
        Text("현재 상태: $status", color = LabelSecondary)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi 켜기")
            Switch(
                checked = status != "Wi-Fi 꺼짐",
                onCheckedChange = { enabled ->
                    WifiHelper.setWifiEnabled(context, enabled)
                    refreshStatus()
                },
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("네트워크 이름 (SSID)") },
            modifier = Modifier.fillMaxWidth()
        )

        if (!isOpenNetwork) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("비밀번호") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("보안 없음 (개방형 네트워크)")
            Switch(
                checked = isOpenNetwork,
                onCheckedChange = { isOpenNetwork = it },
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        Button(
            enabled = ssid.isNotBlank() && !isConnecting,
            onClick = {
                isConnecting = true
                resultMessage = null
                scope.launch {
                    val success = WifiHelper.connect(context, ssid.trim(), password, isOpenNetwork)
                    delay(3000)
                    refreshStatus()
                    resultMessage = if (success) {
                        "연결을 시도했습니다. 위 상태를 확인하세요."
                    } else {
                        "연결 시도에 실패했습니다. SSID/비밀번호를 확인해주세요."
                    }
                    isConnecting = false
                }
            }
        ) {
            Text(if (isConnecting) "연결 시도 중..." else "연결")
        }

        resultMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
