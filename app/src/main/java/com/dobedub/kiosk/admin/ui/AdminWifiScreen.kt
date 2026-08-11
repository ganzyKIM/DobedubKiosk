package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 *
 * 안드로이드 기본 설정처럼 **주변 네트워크 목록**을 띄우고 눌러서 붙는다. 예전에는 SSID를
 * 손으로 쳐야 했는데, 현장에서 오타 한 글자 때문에 연결이 안 되는 일이 반복됐다.
 */
@Composable
fun AdminWifiScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 상태 문자열도 binder IPC 로 읽어오므로 초기값을 여기서 동기로 구하지 않는다.
    var status by remember { mutableStateOf("확인 중…") }
    var networks by remember { mutableStateOf<List<WifiHelper.Network>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var pendingNetwork by remember { mutableStateOf<WifiHelper.Network?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val wifiOn = status != "Wi-Fi 꺼짐"

    fun rescan() {
        scanning = true
        scope.launch {
            networks = WifiHelper.scan(context)
            status = WifiHelper.currentStatus(context)
            scanning = false
        }
    }

    /** 연결 시도 후 상태가 갱신될 때까지 잠깐 기다렸다가 결과를 알린다. */
    fun connect(ssid: String, password: String, open: Boolean) {
        isConnecting = true
        resultMessage = null
        scope.launch {
            val ok = WifiHelper.connect(context, ssid, password, open)
            delay(3000)
            status = WifiHelper.currentStatus(context)
            resultMessage = if (ok) "'$ssid' 에 연결을 시도했습니다. 위 상태를 확인하세요."
                            else "'$ssid' 연결에 실패했습니다. 비밀번호를 확인해주세요."
            isConnecting = false
            networks = WifiHelper.scan(context)
        }
    }

    // 화면에 들어오면 바로 한 번 훑는다 — 관리자가 버튼을 또 누르게 하지 않는다.
    LaunchedEffect(Unit) { rescan() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("Wi-Fi 설정", style = MaterialTheme.typography.headlineMedium)
        Text("현재 상태: $status", color = LabelSecondary)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi 켜기")
            Switch(
                checked = wifiOn,
                onCheckedChange = { enabled ->
                    scope.launch {
                        WifiHelper.setWifiEnabled(context, enabled)
                        status = WifiHelper.currentStatus(context)
                        if (enabled) rescan() else networks = emptyList()
                    }
                },
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("주변 네트워크", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = wifiOn && !scanning, onClick = { rescan() }) {
                Text(if (scanning) "검색 중…" else "새로고침")
            }
        }

        when {
            !wifiOn -> Text("Wi-Fi를 켜면 주변 네트워크를 검색합니다.", color = LabelSecondary)
            scanning && networks.isEmpty() -> Text("검색 중…", color = LabelSecondary)
            networks.isEmpty() -> Text("주변에서 네트워크를 찾지 못했습니다.", color = LabelSecondary)
            else -> networks.forEach { net ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .clickable(enabled = !isConnecting) {
                            if (net.secured) {
                                manualPassword = ""
                                pendingNetwork = net
                            } else {
                                connect(net.ssid, "", true)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(net.ssid, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${if (net.secured) "🔒 " else ""}${signalBars(net.level)}",
                        color = LabelSecondary
                    )
                }
            }
        }

        if (isConnecting) Text("연결 시도 중…", color = MaterialTheme.colorScheme.primary)
        resultMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        // 목록에 안 뜨는 숨김 SSID 용 수동 입력. 목록으로 해결되는 게 대부분이라 아래에 둔다.
        Text("숨김 네트워크 직접 입력", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = manualSsid,
            onValueChange = { manualSsid = it },
            label = { Text("네트워크 이름 (SSID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = manualPassword,
            onValueChange = { manualPassword = it },
            label = { Text("비밀번호 (없으면 비워두세요)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = manualSsid.isNotBlank() && !isConnecting,
            onClick = { connect(manualSsid.trim(), manualPassword, manualPassword.isEmpty()) }
        ) { Text("연결") }
    }

    pendingNetwork?.let { net ->
        AlertDialog(
            onDismissRequest = { pendingNetwork = null },
            title = { Text(net.ssid) },
            text = {
                OutlinedTextField(
                    value = manualPassword,
                    onValueChange = { manualPassword = it },
                    label = { Text("비밀번호") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = manualPassword.isNotEmpty(),
                    onClick = {
                        pendingNetwork = null
                        connect(net.ssid, manualPassword, false)
                    }
                ) { Text("연결") }
            },
            dismissButton = {
                TextButton(onClick = { pendingNetwork = null }) { Text("취소") }
            }
        )
    }
}

/** RSSI(dBm)를 눈으로 읽히는 막대로. 안드로이드 기본 설정의 4단계 아이콘과 같은 기준. */
private fun signalBars(rssi: Int): String = when {
    rssi >= -55 -> "▮▮▮▮"
    rssi >= -66 -> "▮▮▮▯"
    rssi >= -77 -> "▮▮▯▯"
    else -> "▮▯▯▯"
}
