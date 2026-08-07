package com.dobedub.kiosk.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.dobedub.kiosk.ui.theme.KidTitle
import compose.icons.TablerIcons
import compose.icons.tablericons.Book
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.Movie
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

private const val HIDDEN_ADMIN_TAP_COUNT = 5

// 이용안내 이미지(userManual.png)를 메모리 안전하게 표시하기 위해 세로 타일로 분할해둔 리소스.
private val MANUAL_TILES = listOf(
    R.drawable.user_manual_00, R.drawable.user_manual_01, R.drawable.user_manual_02,
    R.drawable.user_manual_03, R.drawable.user_manual_04, R.drawable.user_manual_05,
    R.drawable.user_manual_06, R.drawable.user_manual_07, R.drawable.user_manual_08,
    R.drawable.user_manual_09, R.drawable.user_manual_10, R.drawable.user_manual_11
)

// 캐릭터 움짤(터치할 때마다 다른 표정으로 랜덤 전환).
private val CHARACTER_IMAGES = listOf(
    R.drawable.character_mic,
    R.drawable.character_encourage,
    R.drawable.character_surprise,
    R.drawable.character_wow_center,
    R.drawable.character_wow_right
)

// 말풍선 대사(터치할 때마다 랜덤 전환). 서비스(보이스툰·마이보이스) 톤 + 캐릭터 "빠삐뿌" 자기소개.
private val BUBBLE_LINES = listOf(
    "오늘은 어떤 이야기를 들려줄까?",
    "네 목소리로 주인공이 되어보자!",
    "책 속 친구들이 기다리고 있어요",
    "마이보이스로 더빙해볼래?",
    "우와, 정말 재미있겠다!",
    "귀 기울여 들어볼까?",
    "너의 목소리는 특별해!",
    "함께 읽고, 함께 말해요",
    "다음엔 어떤 캐릭터가 될까?",
    "잘하고 있어, 계속 해보자!",
    "오늘도 신나는 하루!",
    "이야기 속으로 풍덩!",
    "무엇을 해볼까?",
    "나는 빠삐뿌야!",
    "빠삐뿌와 놀자!",
    "안녕, 난 빠삐뿌야!",
    "빠삐뿌가 도와줄게!",
    "빠삐뿌랑 같이 볼까?",
    "오늘은 빠삐뿌랑 신나게!"
)

private fun <T> List<T>.randomIndexExcept(current: Int): Int {
    if (size <= 1) return 0
    var next: Int
    do { next = Random.nextInt(size) } while (next == current)
    return next
}

/**
 * 아동 교육앱(엘리하이·핑크퐁) 톤의 키오스크 홈.
 * 상단(제목 + 큰 버튼 3개)은 고정, 이용안내 이미지 영역만 스크롤한다.
 * 화면 위에는 드래그로 옮길 수 있는 캐릭터와 말풍선이 떠 있고, 터치할 때마다 표정·대사가 바뀐다.
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
                        color = KidTitle,
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

                Spacer(Modifier.height(20.dp))
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

/** 몽글몽글한 3D 버튼: 반짝이는 하이라이트 + 아래 진한 그림자, 누르면 눌리며 살짝 쪼그라든다. */
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
    val dy by animateDpAsState(
        if (pressed) 8.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press-dy"
    )
    val scale by animateDpAsState(
        if (pressed) 96.dp else 100.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press-scale"
    )

    Box(modifier = modifier.height(172.dp)) {
        // 그림자(입체감)
        Box(
            Modifier
                .fillMaxWidth().height(161.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(44.dp))
                .background(shade)
        )
        // 윗면
        Column(
            modifier = Modifier
                .fillMaxWidth().height(161.dp)
                .offset(y = dy)
                .graphicsLayer { scaleX = scale.value / 100f; scaleY = scale.value / 100f }
                .clip(RoundedCornerShape(44.dp))
                .background(face)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(74.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 22.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
        }
        // 반짝이는 하이라이트: 카드의 좌상단 둥근 모서리와 같은 중심을 공유하되, 반지름을 살짝
        // 줄인 동심원 호로 그린다 — 테두리에 딱 붙지 않고 안쪽에 살짝 뜬 광택처럼 보인다.
        Canvas(
            modifier = Modifier
                .fillMaxWidth().height(161.dp)
                .offset(y = dy)
                .graphicsLayer { scaleX = scale.value / 100f; scaleY = scale.value / 100f }
        ) {
            val r = 44.dp.toPx()
            val stroke = 8.dp.toPx()
            val gap = 12.dp.toPx() // 테두리에서 띄우는 간격
            val inset = stroke / 2f + gap
            drawArc(
                color = Color.White.copy(alpha = 0.55f),
                startAngle = 180f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(2 * r - stroke - 2 * gap, 2 * r - stroke - 2 * gap),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 드래그로 위치를 옮길 수 있는 캐릭터("빠삐뿌") + 말풍선. 터치하면 표정(이미지)과 대사가
 * 랜덤으로 바뀌고, 통통 튀는 바운스 애니메이션이 재생된다 — 매번 다른 반응처럼 느껴지도록.
 *
 * 캐릭터는 `res/drawable/character_*.webp` 로 교체/추가한다 — Coil의 ImageDecoderDecoder가
 * 움직이는 WebP를 그대로 애니메이션 재생한다.
 *
 * 터치/드래그 히트 영역은 캐릭터 이미지(330dp)보다 훨씬 작은 원(180dp)으로 좁혀, 웹피의
 * 투명 여백이 아니라 실제 캐릭터 몸통을 만졌을 때만 반응하게 한다.
 *
 * ponytail: 위치·표정은 세션 메모리에만 둔다. 재부팅 시 기본값으로 돌아가는 편이 키오스크엔
 * 안전하다. 영구 저장이 필요하면 DataStore에 x/y 키를 추가할 것.
 */
@Composable
private fun DraggableMascot(parentW: Int, parentH: Int, modifier: Modifier = Modifier) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var selfW by remember { mutableIntStateOf(0) }
    var selfH by remember { mutableIntStateOf(0) }
    var charIndex by remember { mutableIntStateOf(Random.nextInt(CHARACTER_IMAGES.size)) }
    var lineIndex by remember { mutableIntStateOf(Random.nextInt(BUBBLE_LINES.size)) }

    val scope = rememberCoroutineScope()
    val bounce = remember { Animatable(1f) }
    val wiggle = remember { Animatable(0f) }

    val context = LocalContext.current
    // 움직이는 WebP/GIF 재생에 필요(API 28+, 이 앱 minSdk 29).
    val gifLoader = remember {
        ImageLoader.Builder(context).components { add(ImageDecoderDecoder.Factory()) }.build()
    }

    fun react() {
        charIndex = CHARACTER_IMAGES.randomIndexExcept(charIndex)
        lineIndex = BUBBLE_LINES.randomIndexExcept(lineIndex)
        val tilt = if (Random.nextBoolean()) -10f else 10f
        scope.launch {
            bounce.snapTo(0.75f)
            bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
        }
        scope.launch {
            wiggle.snapTo(tilt)
            wiggle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    // ImageRequest를 charIndex가 바뀔 때만 새로 만든다. 매번 새로 build()하면(예: 드래그로
    // 인한 잦은 recomposition마다) Coil이 "새 요청"으로 보고 움짤을 처음부터 다시 디코딩/재생해
    // 캐릭터가 덜덜 떠는 것처럼 보이는 원인이 됐다.
    val imageRequest = remember(charIndex) {
        ImageRequest.Builder(context).data(CHARACTER_IMAGES[charIndex]).build()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onSizeChanged { selfW = it.width; selfH = it.height }
            .padding(start = 18.dp, bottom = 18.dp)
    ) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(KidBubble)
                .padding(horizontal = 26.dp, vertical = 18.dp)
        ) {
            Text(BUBBLE_LINES[lineIndex], fontSize = 22.sp, color = KidInk, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.size(330.dp), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = imageRequest,
                imageLoader = gifLoader,
                contentDescription = "빠삐뿌",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = bounce.value; scaleY = bounce.value
                        rotationZ = wiggle.value
                    }
            )
            // 히트 영역: 이미지 전체(330dp)가 아니라 실제 캐릭터 몸통 크기에 가까운 원만 반응한다.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(180.dp)
                    // 드래그와 탭을 한 제스처 블록에서 판별한다: 이동거리가 터치슬롭을 넘으면
                    // 드래그로 전환하고, 넘기지 않고 손을 떼면 탭으로 본다. (별도 pointerInput
                    // 2개로 나누면 drag 디텍터가 down 이벤트를 선점해 tap 이 실기기에서 씹혔음)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var dragging = false
                            // 프레임워크의 change.positionChange()(직전 이벤트 대비 델타)는
                            // 실기기에서 첫 이벤트에 이상값을 준 적이 있어(단순 탭인데 드래그로
                            // 오판), 다운 지점 기준 절대 변위를 우리가 직접 추적한다.
                            var lastPos = down.position
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!dragging) {
                                    val totalDisplacement = change.position - down.position
                                    if (totalDisplacement.getDistance() > viewConfiguration.touchSlop) dragging = true
                                }
                                if (dragging) {
                                    change.consume()
                                    val delta = change.position - lastPos
                                    offsetX = (offsetX + delta.x)
                                        .coerceIn(0f, (parentW - selfW).coerceAtLeast(0).toFloat())
                                    offsetY = (offsetY + delta.y)
                                        .coerceIn(-(parentH - selfH).coerceAtLeast(0).toFloat(), 0f)
                                }
                                lastPos = change.position
                            } while (event.changes.any { it.pressed })
                            if (!dragging) react()
                        }
                    }
            )
        }
    }
}
