package com.dobedub.kiosk.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.LabelSecondary

data class AdminMenuEntry(val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
fun AdminMenuScreen(
    onOpenKiosk: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenContent: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit
) {
    val entries = listOf(
        AdminMenuEntry("키오스크 관리", "해제 / 재진입 / 재부팅", onOpenKiosk),
        AdminMenuEntry("관리자 정보", "PIN 변경, 연락처", onOpenInfo),
        AdminMenuEntry("콘텐츠 설정", "시작 URL, 허용 도메인", onOpenContent),
        AdminMenuEntry("기기 설정", "밝기, 볼륨, 무조작 시간", onOpenDevice),
        AdminMenuEntry("Wi-Fi 설정", "무선 네트워크 연결", onOpenWifi),
        AdminMenuEntry("원격 관리 / 업데이트", "서버 주소, 기관명, 업데이트 확인", onOpenUpdate),
        AdminMenuEntry("정보", "앱/기기 정보", onOpenAbout)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BackTopBar(label = "닫고 홈으로", onBack = onExit)

        Text("관리자 메뉴", style = MaterialTheme.typography.headlineMedium)

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
    }
}
