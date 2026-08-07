package com.dobedub.kiosk.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.theme.KidGreen
import com.dobedub.kiosk.ui.theme.KidGreenDark
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft

/**
 * 모든 화면 공통으로 쓰는 뒤로가기 상단바.
 * 시스템 백 제스처/버튼에 의존하지 않고, 화면 안에 항상 보이는 큰 터치 영역을 제공한다.
 *
 * 아이들이 서서 쓰는 키오스크라 작은 아이콘 버튼 대신 글자가 붙은 큰 알약 버튼으로 둔다.
 */
@Composable
fun BackTopBar(
    label: String = "뒤로",
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KidActionButton(
            icon = TablerIcons.ArrowLeft,
            contentDescription = label,
            label = label,
            face = KidGreen,
            shade = KidGreenDark,
            onClick = onBack
        )
    }
}
