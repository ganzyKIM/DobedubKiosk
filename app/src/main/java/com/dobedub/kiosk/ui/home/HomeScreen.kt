package com.dobedub.kiosk.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dobedub.kiosk.R
import com.dobedub.kiosk.ui.theme.AccentVideo
import com.dobedub.kiosk.ui.theme.AccentVoice
import com.dobedub.kiosk.ui.theme.AccentWeb
import com.dobedub.kiosk.ui.theme.CardBorder
import com.dobedub.kiosk.ui.theme.CardSurface
import com.dobedub.kiosk.ui.theme.HomeBgBottom
import com.dobedub.kiosk.ui.theme.HomeBgTop
import com.dobedub.kiosk.ui.theme.LabelNormal
import com.dobedub.kiosk.ui.theme.LabelSecondary

private const val HIDDEN_ADMIN_TAP_COUNT = 5

// 이용안내 이미지(userManual.png)를 메모리 안전하게 표시하기 위해 세로 타일로 분할해둔 리소스.
private val MANUAL_TILES = listOf(
    R.drawable.user_manual_00, R.drawable.user_manual_01, R.drawable.user_manual_02,
    R.drawable.user_manual_03, R.drawable.user_manual_04, R.drawable.user_manual_05,
    R.drawable.user_manual_06, R.drawable.user_manual_07, R.drawable.user_manual_08,
    R.drawable.user_manual_09, R.drawable.user_manual_10, R.drawable.user_manual_11
)

@Composable
fun HomeScreen(
    onOpenVideos: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenMyVoice: () -> Unit,
    onAdminUnlockRequested: () -> Unit,
    institutionLabel: String = ""
) {
    var logoTapCount by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HomeBgTop, HomeBgBottom)))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 고정 영역: 헤더 + 액션 버튼 + 섹션 라벨(스크롤하지 않음) ──
            Column(modifier = Modifier.padding(horizontal = 28.dp)) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "보이스툰 도서관",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = LabelNormal,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        logoTapCount++
                        if (logoTapCount >= HIDDEN_ADMIN_TAP_COUNT) {
                            logoTapCount = 0
                            onAdminUnlockRequested()
                        }
                    }
                )
                Text(
                    text = if (institutionLabel.isNotBlank()) institutionLabel else "듣고 보고 말하고, 함께 만드는 웹툰",
                    style = MaterialTheme.typography.titleMedium,
                    color = LabelSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionCard(
                        title = "동영상 보기",
                        subtitle = "보이스툰 영상",
                        icon = Icons.Filled.PlayCircle,
                        accent = AccentVideo,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenVideos
                    )
                    ActionCard(
                        title = "도서관 웹사이트",
                        subtitle = "보이스툰 감상",
                        icon = Icons.Filled.Public,
                        accent = AccentWeb,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenWebsite
                    )
                    ActionCard(
                        title = "마이보이스",
                        subtitle = "내 목소리로",
                        icon = Icons.Filled.Mic,
                        accent = AccentVoice,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenMyVoice
                    )
                }

                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(width = 4.dp, height = 20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "이용 안내",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LabelNormal
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── 스크롤 영역: 이용안내 이미지(세로 타일)만 스크롤 ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 40.dp)
            ) {
                items(MANUAL_TILES) { tile ->
                    val painter = painterResource(id = tile)
                    val intrinsic = painter.intrinsicSize
                    val ratio = if (intrinsic.height > 0f) intrinsic.width / intrinsic.height else 1000f / 839f
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .aspectRatio(ratio)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CardSurface)
            .border(1.dp, CardBorder, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 24.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LabelNormal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
