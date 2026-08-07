package com.dobedub.kiosk.ui.theme

import androidx.compose.ui.graphics.Color

// 피그마 "두비덥 도서관" 파일(WicfHebMZ40Kp4lgmmMqzQ, node 29:7962)에서 추출한 토큰
val PrimaryNormal = Color(0xFFACF537)
val PrimaryHeavy = Color(0xFF8AC42C)
val BackgroundNormal = Color(0xFFFFFFFF)
val LabelNormal = Color(0xFF171719)
val LabelNormalDark = Color(0xFF0F0F10)
val LineNeutral = Color(0x2970737C) // rgba(112,115,124,0.16)
val AccentRed = Color(0xFFCC2222)

// 피그마에 없어 테마에 맞춰 임의 생성한 보조 토큰
val SurfaceTint = Color(0xFFEAF6D2) // 히어로/배너 배경용 연한 라임
val LabelSecondary = Color(0xFF70737C)
val ChipTintBackground = Color(0x0DACF537) // Primary 5%
val ChipBorder = Color(0x6EACF537) // Primary 43%

// ── 아동 교육앱 톤 팔레트 (엘리하이/핑크퐁 계열: 높은 채도 + 파스텔 배경 + 진한 그림자) ──
// 카드마다 [진한색(그림자·테두리), 본색(면), 연한색(배경 얼룩)] 3단으로 입체감을 만든다.
val KidBgTop = Color(0xFF8FE0FF)      // 선명한 하늘빛 (상단)
val KidBgBottom = Color(0xFFFFC9EC)   // 선명한 살구핑크빛 (하단)

val KidGreen = Color(0xFF57C84D)      // 동영상
val KidGreenDark = Color(0xFF3D9E35)
val KidGreenSoft = Color(0xFFDCF5D8)

val KidBlue = Color(0xFF3AA8F0)       // 도서관 웹사이트
val KidBlueDark = Color(0xFF2382C4)
val KidBlueSoft = Color(0xFFD7EEFD)

val KidPurple = Color(0xFF9B6BFF)     // 마이보이스
val KidPurpleDark = Color(0xFF7647D6)
val KidPurpleSoft = Color(0xFFE9E0FF)

val KidInk = Color(0xFF3A3226)        // 본문 글자(순검정 대신 따뜻한 갈색빛)
val KidInkSoft = Color(0xFF8A8172)
val KidBubble = Color(0xFFFFFFFF)     // 말풍선 면
val KidSunny = Color(0xFFFFC93C)      // 포인트(별·강조)

// 모던 키오스크 UI 보조 토큰 (홈 재설계)
val HomeBgTop = Color(0xFFFFFFFF)      // 상단 배경(흰색)
val HomeBgBottom = Color(0xFFF1F8E4)   // 하단 배경(아주 연한 민트)
val CardSurface = Color(0xFFFFFFFF)
val CardBorder = Color(0x14000000)     // 검정 8% — 카드 외곽 미세 구분선
// 3개 액션 카드 아이콘 배지 색 (공공기관 톤: 차분하고 접근성 있는 액센트)
val AccentVideo = Color(0xFF2E7D32)    // 동영상 — 딥 그린
val AccentWeb = Color(0xFF1E88E5)      // 웹사이트 — 블루
val AccentVoice = Color(0xFF7C4DFF)    // 마이보이스 — 바이올렛(사이트 my-voice 톤)
