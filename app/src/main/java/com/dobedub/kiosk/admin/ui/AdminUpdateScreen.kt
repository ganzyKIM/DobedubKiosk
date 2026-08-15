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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.BuildConfig
import com.dobedub.kiosk.admin.AdminViewModel
import com.dobedub.kiosk.data.KioskSettingsRepository
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.update.FleetServerDiscovery
import kotlinx.coroutines.launch

/**
 * 원격 관리/업데이트 설정: 함대 서버 주소·기관 라벨 편집, 현재 버전 표시, 수동 업데이트 확인.
 * 평상시엔 서버 응답에 따라 6시간마다 자동으로 체크인/업데이트가 이뤄진다.
 */
@Composable
fun AdminUpdateScreen(viewModel: AdminViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var serverUrl by remember(settings.fleetServerUrl) { mutableStateOf(settings.fleetServerUrl) }
    var label by remember(settings.institutionLabel) { mutableStateOf(settings.institutionLabel) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
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

        Text("함대 서버 주소")
        val lanPrefix = remember { FleetServerDiscovery.subnetPrefix() }
        Text(
            "넷버드에 등록된 태블릿은 비워두면 됩니다(기본값이 넷버드 주소). " +
                if (lanPrefix != null)
                    "임시로 같은 와이파이의 로컬 서버를 쓸 때만 마지막 자리 입력 (예: 5 → $lanPrefix.5:8090)"
                else "http(s):// 는 생략해도 됩니다",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            placeholder = { Text("비움 = ${BuildConfig.FLEET_SERVER_URL}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled = !scanning,
            onClick = {
                scanning = true
                updateStatus = "서버를 찾는 중…"
                scope.launch {
                    // 현재 입력값(축약 입력은 펼쳐서)과 기본 주소(NetBird)를 먼저 두드리고,
                    // 없으면 LAN 스캔. NetBird 에 등록된 기기는 어느 망에 있든 여기서 잡힌다.
                    val found = FleetServerDiscovery.discoverSmart(
                        listOf(
                            KioskSettingsRepository.normalizeFleetUrl(serverUrl, lanPrefix),
                            BuildConfig.FLEET_SERVER_URL
                        )
                    )
                    if (found != null) {
                        serverUrl = found
                        viewModel.updateFleetServerUrl(found)
                        updateStatus = "서버를 찾았습니다: $found"
                    } else {
                        updateStatus = "서버를 찾지 못했습니다(넷버드·와이파이 모두). 주소를 직접 입력하세요."
                    }
                    scanning = false
                }
            }
        ) { Text(if (scanning) "찾는 중…" else "서버 자동 찾기 (넷버드/와이파이)") }

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
        updateStatus?.let { Text(it) }

        // "지금 업데이트 확인"은 관리자 메뉴 첫 화면으로 옮겼다 — 가장 자주 누르는 버튼인데
        // 서버 주소 설정 화면 안에 묻혀 있어서 매번 한 단계 더 들어가야 했다.
    }
}
