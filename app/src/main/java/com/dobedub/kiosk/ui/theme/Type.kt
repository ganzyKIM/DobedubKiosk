package com.dobedub.kiosk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dobedub.kiosk.R

/**
 * 아동용 둥근 한글 서체 **Jua(주아체)** — Google Fonts, SIL OFL(상업적 사용 가능).
 * 굵고 동글동글해 어린이 교육 앱 톤에 맞고, 멀리서도 잘 읽혀 키오스크에 적합하다.
 * 단일 굵기(Regular)라 위계는 크기·색으로 만든다.
 */
val JuaFamily = FontFamily(Font(R.font.jua_regular))

// 기존 코드 호환용 별칭(본문/보조 라벨도 같은 서체로 통일).
val PretendardFamily = JuaFamily

/**
 * 학교안심 둥근미소(Bold) — 학교안전공제중앙회 배포, 상업적 사용 가능한 둥근 한글 서체.
 * 홈 화면의 로고 타이틀·버튼 글자처럼 가장 먼저 눈에 띄어야 하는 곳에만 쓴다(Jua보다
 * 더 통통하고 진해 포인트 용도로 적합 — 본문까지 전부 바꾸면 과할 수 있어 범위를 좁혔다).
 */
val DunggeunmisoBoldFamily = FontFamily(Font(R.font.hakgyoansim_dunggeunmiso_bold))

// 피그마 토큰: Body2/Normal-Medium (칩, 본문)
val Body2Medium = TextStyle(
    fontFamily = PretendardFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.14.sp
)

// 피그마 토큰: Label1/Normal-Medium (보조 라벨)
val Label1Medium = TextStyle(
    fontFamily = PretendardFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.2.sp
)

// 키오스크 대형 타일/제목용 확장 스케일 (피그마에 없어 임의 생성)
val KioskTileTitle = TextStyle(
    fontFamily = PretendardFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 32.sp,
    lineHeight = 40.sp
)

val KioskHeadline = TextStyle(
    fontFamily = PretendardFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 32.sp
)

val KioskAppTypography = Typography(
    bodyLarge = Body2Medium,
    labelLarge = Label1Medium,
    headlineMedium = KioskHeadline,
    displaySmall = KioskTileTitle
)
