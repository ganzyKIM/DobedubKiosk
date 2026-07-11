package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.components.BackTopBar

/**
 * 키오스크 잠금 해제/재진입/재부팅/Device Owner 완전 해제(반납용)를 제공한다.
 * 실제 DevicePolicyManager 호출은 Activity 컨텍스트가 있는 호출부(MainActivity)에서 수행하고,
 * 이 화면은 콜백만 트리거한다.
 */
@Composable
fun AdminKioskScreen(
    onExitKiosk: () -> Unit,
    onReenterKiosk: () -> Unit,
    onReboot: () -> Unit,
    onReleaseDeviceOwner: () -> Unit,
    onBack: () -> Unit
) {
    var showReleaseConfirm by remember { mutableStateOf(false) }
    var showRebootConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackTopBar(onBack = onBack)

        Text("키오스크 관리", style = MaterialTheme.typography.headlineMedium)

        Button(onClick = onExitKiosk) {
            Text("키오스크 모드 해제 (일반 태블릿으로 전환)")
        }
        OutlinedButton(onClick = onReenterKiosk) {
            Text("키오스크 모드 재진입")
        }
        OutlinedButton(onClick = { showRebootConfirm = true }) {
            Text("기기 재부팅")
        }
        OutlinedButton(onClick = { showReleaseConfirm = true }) {
            Text("Device Owner 완전 해제 (반납용)")
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
