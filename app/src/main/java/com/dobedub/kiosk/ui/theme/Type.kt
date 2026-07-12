package com.dobedub.kiosk.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Pretendard가 번들되지 않은 경우 시스템 기본 SansSerif로 대체된다.
// TODO: app/src/main/res/font/ 에 Pretendard 폰트 파일을 추가하고 FontFamily로 교체할 것
val PretendardFamily = FontFamily.SansSerif

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
