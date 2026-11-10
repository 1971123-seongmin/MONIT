package com.monit.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Figma 40:12의 semantic 역할. pressed/disabled는 M3에 없는 MONIT 상태 역할이다. */
@Immutable
class MonitColors internal constructor(
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val onSurface: Color,
    val muted: Color,
    val outline: Color,
    val pressed: Color,
    val disabledBackground: Color,
    val disabledText: Color,
    val error: Color,
)

internal val LightMonitColors = MonitColors(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    surface = Color(0xFFF5F6F8), surfaceContainer = Color.White,
    onSurface = Color(0xFF191F28), muted = Color(0xFF5B6573), outline = Color(0xFF5B6573),
    pressed = Color(0xFF1D4ED8), disabledBackground = Color(0xFFE5E8EB),
    disabledText = Color(0xFF5B6573), error = Color(0xFFB42318),
)

internal val DarkMonitColors = MonitColors(
    primary = Color(0xFF93C5FD), onPrimary = Color(0xFF172554),
    surface = Color(0xFF191F28), surfaceContainer = Color(0xFF333D4B),
    onSurface = Color(0xFFF5F6F8), muted = Color(0xFFADB5C0), outline = Color(0xFFADB5C0),
    pressed = Color(0xFFBFDBFE), disabledBackground = Color(0xFF333D4B),
    disabledText = Color(0xFFADB5C0), error = Color(0xFFFDA29B),
)

/** 원본에 없는 M3 역할은 기존 semantic 쌍을 재사용한다. 새 기능 색을 추측해 만들지 않는다. */
internal fun MonitColors.materialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary, primaryContainer = surfaceContainer, onPrimaryContainer = primary,
        secondary = primary, onSecondary = onPrimary, secondaryContainer = surfaceContainer, onSecondaryContainer = onSurface,
        tertiary = primary, onTertiary = onPrimary, tertiaryContainer = surfaceContainer, onTertiaryContainer = onSurface,
        background = surface, onBackground = onSurface, surface = surface, onSurface = onSurface,
        surfaceVariant = surfaceContainer, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent, outline = outline, outlineVariant = disabledBackground,
        inverseSurface = onSurface, inverseOnSurface = surface,
        inversePrimary = if (dark) LightMonitColors.primary else DarkMonitColors.primary,
        error = error, onError = surfaceContainer, errorContainer = surfaceContainer, onErrorContainer = error,
        scrim = Color.Black, surfaceBright = surfaceContainer, surfaceDim = surface,
        surfaceContainer = surfaceContainer, surfaceContainerLowest = surface,
        surfaceContainerLow = surfaceContainer, surfaceContainerHigh = surfaceContainer,
        surfaceContainerHighest = surfaceContainer,
    )
}
