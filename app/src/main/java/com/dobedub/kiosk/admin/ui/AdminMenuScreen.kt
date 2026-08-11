package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.AccentRed
import com.dobedub.kiosk.ui.theme.LabelSecondary
import com.dobedub.kiosk.update.AppUpdater
import kotlinx.coroutines.launch

data class AdminMenuEntry(val title: String, val subtitle: String, val onClick: () -> Unit)

/**
 * 관리자 메뉴. 자주 쓰는 동작(키오스크 해제/재진입/재부팅/완전해제, Wi-Fi, 업데이트 확인)은
 * 하위 화면에 묻지 않고 이 화면에 바로 버튼으로 둔다 — 현장에서 매번 한 단계씩 더 들어가는
 * 게 불편했다. 나머지 설정만 아래 목록에 남긴다.
 */
@Composable
fun AdminMenuScreen(
    onOpenInfo: () -> Unit,
    onOpenContent: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
    onExitKiosk: () -> Unit,
    onReenterKiosk: () -> Unit,
    onReboot: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdater(context.applicationContext) }

    var showRebootConfirm by remember { mutableStateOf(false) }
    var showReleaseConfirm by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    val entries = listOf(
        AdminMenuEntry("관리자 정보", "PIN 변경, 연락처", onOpenInfo),
        AdminMenuEntry("콘텐츠 설정", "시작 URL, 허용 도메인", onOpenContent),
        AdminMenuEntry("기기 설정", "밝기, 볼륨, 무조작 시간", onOpenDevice),
        AdminMenuEntry("원격 관리", "서버 주소, 기관명", onOpenUpdate),
        AdminMenuEntry("정보", "앱/기기 정보", onOpenAbout)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BackTopBar(label = "닫고 홈으로", onBack = onExit)

        Text("관리자 메뉴", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onExitKiosk, modifier = Modifier.weight(1f)) {
                Text("키오스크 해제")
            }
            Button(onClick = onReenterKiosk, modifier = Modifier.weight(1f)) {
                Text("키오스크 재진입")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = onOpenWifi, modifier = Modifier.weight(1f)) {
                Text("Wi-Fi 설정")
            }
            Button(
                enabled = !checking,
                modifier = Modifier.weight(1f),
                onClick = {
                    checking = true
                    updateStatus = "서버에 확인 중…"
                    scope.launch {
                        updateStatus = runUpdateCheck(updater)
                        checking = false
                    }
                }
            ) { Text(if (checking) "확인 중…" else "업데이트 확인") }
        }

        updateStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        entries.forEach { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .clickable(onClick = entry.onClick)
                    .padding(20.dp)
            ) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(entry.subtitle, color = LabelSecondary)
            }
        }

        // 재부팅과 완전 해제는 화면 맨 아래. 다른 버튼을 누르러 왔다가 스쳐 누를 일이 없는
        // 자리에 두고, 둘 다 확인창을 한 번 거치게 한다.
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { showRebootConfirm = true },
                modifier = Modifier.weight(1f)
            ) { Text("기기 재부팅") }
            OutlinedButton(
                onClick = { showReleaseConfirm = true },
                modifier = Modifier.weight(1f)
            ) { Text("키오스크 완전 해제", color = AccentRed) }
        }
    }

    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text("기기를 재부팅할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    showRebootConfirm = false
                    onReboot()
                }) { Text("재부팅") }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) { Text("취소") }
            }
        )
    }

    if (showReleaseConfirm) {
        AlertDialog(
            onDismissRequest = { showReleaseConfirm = false },
            title = { Text("Device Owner를 완전히 해제할까요?") },
            text = { Text("이 작업은 되돌릴 수 없습니다. 태블릿을 도서관에 반납하거나 다른 용도로 전환할 때만 사용하세요. 다시 키오스크로 쓰려면 공장초기화 후 재설정이 필요합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showReleaseConfirm = false
                    onReleaseDeviceOwner()
                }) { Text("해제") }
            },
            dismissButton = {
                TextButton(onClick = { showReleaseConfirm = false }) { Text("취소") }
            }
        )
    }
}

/**
 * 관리자가 이 버튼을 눌렀다는 것 자체가 이미 설치 의사이므로, 서버가 확인창을 요청한
 * 경우에도 별도 팝업 없이 바로 설치한다(홈 화면 팝업은 원격 강제 알림 전용).
 */
private suspend fun runUpdateCheck(updater: AppUpdater): String =
    when (val result = updater.runOnce(canInstallNow = { true })) {
        is AppUpdater.Result.UpToDate -> "최신 상태입니다. (서버 버전 code ${result.serverVersion})"
        is AppUpdater.Result.Updating -> "새 버전(code ${result.toVersion}) 설치를 시작합니다. 곧 앱이 재시작됩니다."
        is AppUpdater.Result.Deferred -> "새 버전이 있습니다(code ${result.toVersion})."
        is AppUpdater.Result.NeedsConfirmation ->
            when (val installed = updater.installConfirmed(result.manifest)) {
                is AppUpdater.Result.Updating -> "새 버전(code ${installed.toVersion}) 설치를 시작합니다. 곧 앱이 재시작됩니다."
                is AppUpdater.Result.Failed -> installed.reason
                else -> "업데이트 처리 중입니다."
            }
        is AppUpdater.Result.Failed -> result.reason
        AppUpdater.Result.NoServer -> "서버 주소가 설정되지 않았습니다."
    }
