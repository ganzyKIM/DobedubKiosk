package com.dobedub.kiosk.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dobedub.kiosk.ui.theme.LabelNormal
import com.dobedub.kiosk.ui.theme.PrimaryHeavy
import com.dobedub.kiosk.ui.theme.PrimaryNormal
import com.dobedub.kiosk.ui.theme.SurfaceTint

private const val HIDDEN_ADMIN_TAP_COUNT = 5

@Composable
fun HomeScreen(
    onOpenVideos: () -> Unit,
    onOpenWebsite: () -> Unit,
    onAdminUnlockRequested: () -> Unit
) {
    var logoTapCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "두비덥 도서관",
            style = MaterialTheme.typography.headlineMedium,
            color = LabelNormal,
            modifier = Modifier.clickable {
                logoTapCount++
                if (logoTapCount >= HIDDEN_ADMIN_TAP_COUNT) {
                    logoTapCount = 0
                    onAdminUnlockRequested()
                }
            }
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            HomeTile(
                title = "동영상 보기",
                subtitle = "보이스툰 영상을 재생해요",
                background = SurfaceTint,
                titleColor = PrimaryHeavy,
                modifier = Modifier.weight(1f),
                onClick = onOpenVideos
            )
            HomeTile(
                title = "도서관 웹사이트",
                subtitle = "보이스툰 도서관 접속",
                background = PrimaryNormal,
                titleColor = LabelNormal,
                modifier = Modifier.weight(1f),
                onClick = onOpenWebsite
            )
        }
    }
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    background: Color,
    titleColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .clip(RoundedCornerShape(28.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Text(
                text = subtitle,
                fontSize = 16.sp,
                color = LabelNormal
            )
        }
    }
}
