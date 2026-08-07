package com.dobedub.kiosk.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 아동용 동글동글 액션 버튼. 홈 화면의 KidCard 와 같은 톤(진한 색 그림자 + 누르면 쪼그라듦)을
 * 작은 크기로 재현한다.
 *
 * `label` 을 주면 알약(pill) 모양, 주지 않으면 원형이 된다 — 상단바에서 "홈으로"처럼 글자가
 * 필요한 버튼과 아이콘만 있는 버튼을 같은 컴포넌트로 처리하기 위함.
 *
 * 키오스크는 아이들이 서서 쓰므로 터치 영역을 크게 잡는다(기본 64dp — Material 기본
 * IconButton 48dp 보다 크고, 손가락이 작아도 빗나가지 않는다).
 */
@Composable
fun KidActionButton(
    icon: ImageVector,
    contentDescription: String,
    face: Color,
    shade: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: androidx.compose.ui.unit.Dp = 64.dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "kid-action-press"
    )

    val shape = CircleShape
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.5f)
        )
    }

    val base = modifier
        .height(size)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        // 아래쪽에 진한 색을 살짝 깔아 입체감을 준다(홈 버튼과 같은 어법).
        .shadow(elevation = 4.dp, shape = shape, spotColor = shade, ambientColor = shade)
        .clip(shape)
        .background(face)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)

    if (label == null) {
        Box(modifier = base.width(size), contentAlignment = Alignment.Center) { content() }
    } else {
        Row(
            modifier = base.padding(horizontal = size * 0.34f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = (size.value * 0.31f).sp, color = Color.White, maxLines = 1)
        }
    }
}
