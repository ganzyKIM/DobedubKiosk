package com.dobedub.kiosk.video.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.dobedub.kiosk.ui.components.BackTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.dobedub.kiosk.ui.theme.LabelNormal
import com.dobedub.kiosk.ui.theme.SurfaceTint
import com.dobedub.kiosk.video.VideoItem
import com.dobedub.kiosk.video.VideoThumbnailLoader

@Composable
fun VideoListScreen(
    videos: List<VideoItem>,
    onOpenVideo: (VideoItem) -> Unit,
    onBackToHome: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        BackTopBar(label = "홈으로", onBack = onBackToHome)

        Text(
            text = "동영상 보기",
            style = MaterialTheme.typography.headlineMedium,
            color = LabelNormal
        )

        Spacer(Modifier.height(12.dp))

        if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 등록된 동영상이 없어요.", color = LabelNormal)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos) { video ->
                    VideoCard(video = video, onClick = { onOpenVideo(video) })
                }
            }
        }
    }
}

@Composable
private fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    var thumbnail by remember(video.file.path) { mutableStateOf<Bitmap?>(null) }

    androidx.compose.runtime.LaunchedEffect(video.file.path) {
        thumbnail = VideoThumbnailLoader.loadFirstFrame(video.file)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceTint),
            contentAlignment = Alignment.Center
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("▶", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Text(
            text = video.title,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp),
            color = LabelNormal
        )
    }
}
