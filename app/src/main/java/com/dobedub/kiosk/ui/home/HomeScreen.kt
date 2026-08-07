package com.dobedub.kiosk.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.dobedub.kiosk.R
import com.dobedub.kiosk.ui.theme.KidBgBottom
import com.dobedub.kiosk.ui.theme.KidBgTop
import com.dobedub.kiosk.ui.theme.KidBlue
import com.dobedub.kiosk.ui.theme.KidBlueDark
import com.dobedub.kiosk.ui.theme.KidBubble
import com.dobedub.kiosk.ui.theme.KidGreen
import com.dobedub.kiosk.ui.theme.KidGreenDark
import com.dobedub.kiosk.ui.theme.KidInk
import com.dobedub.kiosk.ui.theme.KidInkSoft
import com.dobedub.kiosk.ui.theme.KidPurple
import com.dobedub.kiosk.ui.theme.KidPurpleDark
import com.dobedub.kiosk.ui.theme.KidSunny
import compose.icons.TablerIcons
import compose.icons.tablericons.Book
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.Movie
import kotlin.math.roundToInt

private const val HIDDEN_ADMIN_TAP_COUNT = 5

// 이용안내 이미지(userManual.png)를 메모리 안전하게 표시하기 위해 세로 타일로 분할해둔 리소스.
private val MANUAL_TILES = listOf(
    R.drawable.user_manual_00, R.drawable.user_manual_01, R.drawable.user_manual_02,
    R.drawable.user_manual_03, R.drawable.user_manual_04, R.drawable.user_manual_05,
    R.drawable.user_manual_06, R.drawable.user_manual_07, R.drawable.user_manual_08,
    R.drawable.user_manual_09, R.drawable.user_manual_10, R.drawable.user_manual_11
)

/**
 * 아동 교육앱(엘리하이·핑크퐁) 톤의 키오스크 홈.
 * 상단(제목 + 큰 버튼 3개 + 섹션 라벨)은 고정, 이용안내 이미지 영역만 스크롤한다.
 * 화면 위에는 드래그로 옮길 수 있는 캐릭터와 말풍선이 떠 있다.
 */
@Composable
fun HomeScreen(
    onOpenVideos: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenMyVoice: () -> Unit,
    onAdminUnlockRequested: () -> Unit,
    institutionLabel: String = ""
) {
    var logoTapCount by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(KidBgTop, KidBgBottom)))
    ) {
        val parentW = constraints.maxWidth
        val parentH = constraints.maxHeight

        Column(modifier = Modifier.fillMaxSize()) {
            // ── 고정 영역 ──
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "보이스툰 도서관",
                        fontSize = 30.sp,
                        color = KidInk,
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
                    if (institutionLabel.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(institutionLabel, fontSize = 17.sp, color = KidInkSoft)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    KidCard("동영상", TablerIcons.Movie, KidGreen, KidGreenDark, Modifier.weight(1f), onOpenVideos)
                    KidCard("도서관", TablerIcons.Book, KidBlue, KidBlueDark, Modifier.weight(1f), onOpenWebsite)
                    KidCard("마이보이스", TablerIcons.Microphone, KidPurple, KidPurpleDark, Modifier.weight(1f), onOpenMyVoice)
                }

                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(KidSunny))
                    Spacer(Modifier.width(8.dp))
                    Text("이용 안내", fontSize = 20.sp, color = KidInk)
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── 스크롤 영역: 이용안내 이미지만 ──
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).aspectRatio(ratio)
                    )
                }
            }
        }

        DraggableMascot(parentW = parentW, parentH = parentH, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** 큼직한 3D 느낌 버튼: 아래에 진한 색 그림자를 깔고, 누르면 눌린 만큼 내려간다. */
@Composable
private fun KidCard(
    label: String,
    icon: ImageVector,
    face: Color,
    shade: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dy by animateDpAsState(if (pressed) 7.dp else 0.dp, label = "press")

    Box(modifier = modifier.height(168.dp)) {
        // 그림자(입체감)
        Box(
            Modifier
                .fillMaxWidth().height(161.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(30.dp))
                .background(shade)
        )
        // 윗면
        Column(
            modifier = Modifier
                .fillMaxWidth().height(161.dp)
                .offset(y = dy)
                .clip(RoundedCornerShape(30.dp))
                .background(face)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 22.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/**
 * 드래그로 위치를 옮길 수 있는 캐릭터 + 말풍선.
 * 캐릭터는 `res/drawable/character.*` 한 장으로 교체한다 — 움직이는 WebP를 넣으면 Coil의
 * ImageDecoderDecoder가 그대로 애니메이션 재생한다(파일만 교체, 코드 수정 불필요).
 *
 * ponytail: 위치는 세션 메모리에만 둔다. 재부팅 시 기본 위치로 돌아가는 편이 키오스크엔 안전하다.
 * 영구 저장이 필요하면 DataStore에 x/y 키를 추가할 것.
 */
@Composable
private fun DraggableMascot(parentW: Int, parentH: Int, modifier: Modifier = Modifier) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var selfW by remember { mutableIntStateOf(0) }
    var selfH by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    // 움직이는 WebP/GIF 재생에 필요(API 28+, 이 앱 minSdk 29).
    val gifLoader = remember {
        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onSizeChanged { selfW = it.width; selfH = it.height }
            .padding(start = 18.dp, bottom = 18.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    // 화면 밖으로 나가지 않도록 가둔다(좌하단 정렬 기준).
                    offsetX = (offsetX + drag.x).coerceIn(0f, (parentW - selfW).coerceAtLeast(0).toFloat())
                    offsetY = (offsetY + drag.y).coerceIn(-(parentH - selfH).coerceAtLeast(0).toFloat(), 0f)
                }
            }
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(KidBubble)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("무엇을 해볼까?", fontSize = 17.sp, color = KidInk)
        }
        Spacer(Modifier.height(6.dp))
        AsyncImage(
            model = ImageRequest.Builder(context).data(R.drawable.character).build(),
            imageLoader = gifLoader,
            contentDescription = null,
            modifier = Modifier.size(132.dp)
        )
    }
}
