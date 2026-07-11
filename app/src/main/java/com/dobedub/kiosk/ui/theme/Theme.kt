package com.dobedub.kiosk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 키오스크는 항상 라이트 테마 고정(공용 단말, 어린이 이용자 가독성 우선)
private val KioskColorScheme = lightColorScheme(
    primary = PrimaryHeavy,
    onPrimary = BackgroundNormal,
    primaryContainer = PrimaryNormal,
    onPrimaryContainer = LabelNormal,
    background = BackgroundNormal,
    onBackground = LabelNormal,
    surface = BackgroundNormal,
    onSurface = LabelNormal,
    surfaceVariant = SurfaceTint,
    onSurfaceVariant = LabelSecondary,
    outline = LineNeutral,
    error = AccentRed
)

@Composable
fun DobedubKioskTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KioskColorScheme,
        typography = KioskAppTypography,
        content = content
    )
}
