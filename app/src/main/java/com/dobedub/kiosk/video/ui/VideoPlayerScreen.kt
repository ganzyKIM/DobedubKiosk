package com.dobedub.kiosk.video.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.dobedub.kiosk.MediaPlaybackState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.dobedub.kiosk.ui.components.KidActionButton
import com.dobedub.kiosk.ui.theme.KidBlue
import com.dobedub.kiosk.ui.theme.KidBlueDark
import com.dobedub.kiosk.ui.theme.KidGreen
import com.dobedub.kiosk.ui.theme.KidGreenDark
import com.dobedub.kiosk.ui.theme.KidPurple
import com.dobedub.kiosk.ui.theme.KidPurpleDark
import com.dobedub.kiosk.video.VideoItem
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ChevronsLeft
import compose.icons.tablericons.ChevronsRight
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.Rotate
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val SEEK_STEP_MS = 5_000L

/**
 * 전체화면 동영상 재생. 컨트롤은 재생/일시정지, 처음부터, ±5초 탐색, 목록으로 버튼 + 시크바를 제공하고
 * 3초 무조작 시 자동으로 숨긴다(§4.2). 화면을 탭하면 컨트롤 표시만 토글되고, 재생/일시정지는
 * 컨트롤의 버튼으로만 한다.
 */
@Composable
fun VideoPlayerScreen(
    video: VideoItem,
    onPlaybackEnded: () -> Unit,
    onBackToList: () -> Unit,
    onUserInteraction: () -> Unit
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // 무조작 홈 복귀가 "감상 중"을 알 수 있게 재생 상태를 공유한다(검토 §1-1).
                MediaPlaybackState.videoPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 다음 영상 자동재생 여부(관리자 설정)는 상위 네비게이션이 판단해 처리한다.
                if (playbackState == Player.STATE_ENDED) {
                    onPlaybackEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            MediaPlaybackState.videoPlaying = false   // 화면을 떠나면 반드시 해제
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) {
                onUserInteraction()
                // 화면 탭은 컨트롤을 보이고/숨기기만 한다. 재생·일시정지는 아래 버튼으로만 —
                // 탭이 곧 일시정지였을 때 이용자가 화면을 스치기만 해도 영상이 서 버렸다.
                // 단 일시정지 중에는 숨기지 않는다. 다시 재생할 버튼이 화면에서 사라지면 안 된다.
                controlsVisible = !isPlaying || !controlsVisible
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible) {
            // 상단바: 목록으로 돌아가기 — 앱 공통 어법(KidActionButton 알약)으로.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                KidActionButton(
                    icon = TablerIcons.ArrowLeft,
                    contentDescription = "목록으로",
                    face = KidBlue,
                    shade = KidBlueDark,
                    label = "목록",
                    size = 56.dp
                ) {
                    onUserInteraction()
                    onBackToList()
                }
                Text(video.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                // 컨트롤 버튼: 홈 화면 버튼과 같은 동글동글 어법. 가운데 재생/일시정지가
                // 가장 크고(88dp), 좌우 탐색이 그다음(64dp) — 아이 손가락 기준 큼직하게.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)
                ) {
                    KidActionButton(
                        icon = TablerIcons.Rotate,
                        contentDescription = "처음부터",
                        face = KidPurple,
                        shade = KidPurpleDark,
                        size = 64.dp
                    ) {
                        onUserInteraction()
                        player.seekTo(0)
                        player.play()
                    }

                    KidActionButton(
                        icon = TablerIcons.ChevronsLeft,
                        contentDescription = "5초 뒤로",
                        face = KidBlue,
                        shade = KidBlueDark,
                        size = 64.dp
                    ) {
                        onUserInteraction()
                        player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
                    }

                    KidActionButton(
                        icon = if (isPlaying) TablerIcons.PlayerPause else TablerIcons.PlayerPlay,
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        face = KidGreen,
                        shade = KidGreenDark,
                        size = 88.dp
                    ) {
                        onUserInteraction()
                        if (player.isPlaying) player.pause() else player.play()
                    }

                    KidActionButton(
                        icon = TablerIcons.ChevronsRight,
                        contentDescription = "5초 앞으로",
                        face = KidBlue,
                        shade = KidBlueDark,
                        size = 64.dp
                    ) {
                        onUserInteraction()
                        val target = player.currentPosition + SEEK_STEP_MS
                        player.seekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(56.dp)
                    )
                    Slider(
                        value = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction ->
                            onUserInteraction()
                            val target = (fraction * durationMs).toLong()
                            player.seekTo(target)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = KidGreen,
                            activeTrackColor = KidGreen,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(
                        text = formatDuration(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
