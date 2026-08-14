package com.dobedub.kiosk.video.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dobedub.kiosk.ui.components.BackTopBar
import com.dobedub.kiosk.ui.theme.KidBgBottom
import com.dobedub.kiosk.ui.theme.KidBgTop
import com.dobedub.kiosk.ui.theme.KidGreenSoft
import com.dobedub.kiosk.ui.theme.KidInk
import com.dobedub.kiosk.update.DownloadState
import com.dobedub.kiosk.video.VideoItem
import com.dobedub.kiosk.video.VideoThumbnailLoader
import compose.icons.TablerIcons
import compose.icons.tablericons.X

/**
 * 보이스툰 영상은 세로 2:3 비율이다(실측: 기기에 든 영상의 첫 프레임이 98×148).
 * 썸네일 박스를 이 비율로 잡아야 레터박스(위아래 빈 공간) 없이 꽉 찬다.
 */
private const val THUMB_ASPECT = 2f / 3f

@Composable
fun VideoListScreen(
    videos: List<VideoItem>,
    onOpenVideo: (VideoItem) -> Unit,
    onBackToHome: () -> Unit
) {
    // 관리자가 원격으로 보내는 중인 영상 — 다 받은 것과 똑같은 카드 자리에 진행률을 띄워
    // "지금 오고 있다"는 걸 보여준다. 완료되면 DownloadState 에서 빠지고 목록에 실물이 뜬다.
    val transfers by DownloadState.transfers.collectAsState()
    val incoming = transfers.values
        .filter { it.kind == "video" }
        .sortedBy { it.name }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(KidBgTop, KidBgBottom)))
            .padding(horizontal = 24.dp)
    ) {
        BackTopBar(label = "닫기", icon = TablerIcons.X, onBack = onBackToHome)

        // 제목 글자를 없앤 자리만큼 위 버튼과 아래 영상 카드 사이에 여백을 둔다.
        Spacer(Modifier.height(28.dp))

        if (videos.isEmpty() && incoming.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 등록된 동영상이 없어요.", fontSize = 24.sp, color = KidInk)
            }
        } else {
            // 4열은 태블릿에서 너무 작았다. 3열로 줄여 카드 자체를 크게 키운다.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos) { video ->
                    VideoCard(video = video, onClick = { onOpenVideo(video) })
                }
                items(incoming, key = { "incoming-${it.name}" }) { transfer ->
                    IncomingVideoCard(transfer)
                }
            }
        }
    }
}

/** 받는 중인 영상 자리 카드 — 진행률 원과 퍼센트를 보여주고, 탭해도 반응하지 않는다. */
@Composable
private fun IncomingVideoCard(transfer: DownloadState.Transfer) {
    val pct = if (transfer.total > 0) (transfer.received * 100 / transfer.total).toInt() else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_ASPECT)
                .clip(RoundedCornerShape(20.dp))
                .background(KidGreenSoft),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (pct != null) {
                    CircularProgressIndicator(
                        progress = { pct / 100f },
                        color = KidInk,
                        trackColor = Color.White
                    )
                    Text("$pct%", fontSize = 24.sp, color = KidInk, modifier = Modifier.padding(top = 10.dp))
                } else {
                    CircularProgressIndicator(color = KidInk, trackColor = Color.White)
                }
                Text("받는 중이에요", fontSize = 16.sp, color = KidInk, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Text(
            text = transfer.name.substringBeforeLast('.'),
            fontSize = 20.sp,
            color = KidInk.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    var thumbnail by remember(video.file.path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.file.path) {
        thumbnail = VideoThumbnailLoader.load(video.file)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_ASPECT)
                .clip(RoundedCornerShape(20.dp))
                .background(KidGreenSoft),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = video.title,
                    // Crop: 비율이 다른 영상이 섞여 들어와도 빈 공간 없이 꽉 채운다.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("▶", fontSize = 44.sp, color = KidInk)
            }
        }
        // maxLines 를 두지 않는다 — 제목이 길어도 전부 보여야 한다.
        Text(
            text = video.title,
            fontSize = 20.sp,
            color = KidInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp, bottom = 4.dp)
        )
    }
}
