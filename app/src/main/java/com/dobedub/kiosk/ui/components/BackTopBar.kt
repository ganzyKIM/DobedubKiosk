package com.dobedub.kiosk.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.theme.LabelNormal

/**
 * 모든 화면 공통으로 쓰는 뒤로가기 상단바.
 * 시스템 백 제스처/버튼에 의존하지 않고, 화면 안에 항상 보이는 큰 터치 영역을 제공한다.
 */
@Composable
fun BackTopBar(
    label: String = "뒤로",
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = label, tint = LabelNormal)
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = LabelNormal)
    }
}
