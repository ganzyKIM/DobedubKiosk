package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.BuildConfig
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.data.KioskSettingsRepository
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.update.AppUpdater
import com.dobedub.kiosk.update.FleetServerDiscovery
import kotlinx.coroutines.launch

/**
 * 원격 관리/업데이트 설정: 함대 서버 주소·기관 라벨 편집, 현재 버전 표시, 수동 업데이트 확인.
 * 평상시엔 서버 응답에 따라 6시간마다 자동으로 체크인/업데이트가 이뤄진다.
 */
@Composable
fun AdminUpdateScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context.applicationContext) }

    var serverUrl by remember(settings.fleetServerUrl) { mutableStateOf(settings.fleetServerUrl) }
    var label by remember(settings.institutionLabel) { mutableStateOf(settings.institutionLabel) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("원격 관리 / 업데이트", style = MaterialTheme.typography.headlineMedium)

        Text("현재 앱 버전: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        Text(
            "기본 서버(빌드값): ${BuildConfig.FLEET_SERVER_URL}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text("함대 서버 주소 (비우면 기본값 사용)")
        val lanPrefix = remember { FleetServerDiscovery.subnetPrefix() }
        Text(
            if (lanPrefix != null)
                "같은 와이파이면 마지막 자리만 입력해도 됩니다 (예: 5 → $lanPrefix.5:8090)"
            else "https:// 는 생략해도 됩니다",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            placeholder = { Text(lanPrefix?.let { "5" } ?: BuildConfig.FLEET_SERVER_URL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !scanning,
            onClick = {
                scanning = true
                updateStatus = "같은 와이파이에서 서버를 찾는 중…"
                scope.launch {
                    val found = FleetServerDiscovery.discover()
                    if (found != null) {
                        serverUrl = found
                        viewModel.updateFleetServerUrl(found)
                        updateStatus = "서버를 찾았습니다: $found"
                    } else {
                        updateStatus = "같은 와이파이에서 서버를 찾지 못했습니다. 주소를 직접 입력하세요."
                    }
                    scanning = false
                }
            }
        ) { Text(if (scanning) "찾는 중…" else "이 와이파이에서 서버 자동 찾기") }

        Text("기관/도서관 이름 (백오피스 식별용)")
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = {
            // "5" 같은 축약 입력을 이 기기의 서브넷 기준으로 펼친 뒤 저장한다.
            val normalized = KioskSettingsRepository.normalizeFleetUrl(serverUrl, lanPrefix)
            viewModel.updateFleetServerUrl(normalized)
            viewModel.updateInstitutionLabel(label.trim())
            serverUrl = normalized
            savedMessage = if (normalized.isBlank()) "기본 서버를 사용합니다."
                           else "저장되었습니다: $normalized"
        }) { Text("저장") }
        savedMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Button(
            enabled = !checking,
            onClick = {
                checking = true
                updateStatus = "서버에 확인 중…"
                scope.launch {
                    val result = updater.runOnce(canInstallNow = { true })
                    updateStatus = when (result) {
                        is AppUpdater.Result.UpToDate -> "최신 상태입니다. (서버 버전 code ${result.serverVersion})"
                        is AppUpdater.Result.Updating -> "새 버전(code ${result.toVersion}) 설치를 시작합니다. 곧 앱이 재시작됩니다."
                        is AppUpdater.Result.Deferred -> "새 버전이 있습니다(code ${result.toVersion})."
                        // 관리자가 이 화면에서 직접 버튼을 눌렀다는 것 자체가 이미 확인 의사이므로
                        // 별도 확인창 없이 바로 설치한다(홈 화면에서만 뜨는 팝업은 원격 알림 전용).
                        is AppUpdater.Result.NeedsConfirmation -> {
                            when (val installed = updater.installConfirmed(result.manifest)) {
                                is AppUpdater.Result.Updating -> "새 버전(code ${installed.toVersion}) 설치를 시작합니다. 곧 앱이 재시작됩니다."
                                is AppUpdater.Result.Failed -> installed.reason
                                else -> "업데이트 처리 중입니다."
                            }
                        }
                        is AppUpdater.Result.Failed -> result.reason
                        AppUpdater.Result.NoServer -> "서버 주소가 설정되지 않았습니다."
                    }
                    checking = false
                }
            }
        ) { Text(if (checking) "확인 중…" else "지금 업데이트 확인") }
        updateStatus?.let { Text(it) }
    }
}
