package com.monit.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Immutable
object MonitSpacing {
    val content = 16.dp
    val section = 24.dp
    val inline = 8.dp
    val compact = 4.dp
}

@Immutable
object MonitSizes {
    val icon = 24.dp
    val touchTarget = 48.dp
    val inputMinHeight = 56.dp
    val rowMinHeight = 72.dp
    val dateIndicator = 40.dp
    val controlRadius = 12.dp
    val fieldRadius = 4.dp
    val outline = 1.dp
    val focusOutline = 2.dp
    val focusGap = 2.dp
}

private val LocalMonitColors = staticCompositionLocalOf { LightMonitColors }

object MonitTheme {
    val colors: MonitColors @Composable @ReadOnlyComposable get() = LocalMonitColors.current
    val spacing: MonitSpacing get() = MonitSpacing
    val sizes: MonitSizes get() = MonitSizes
}

/** 기본값은 기기 테마를 따른다. 동적 색상은 사용하지 않으며 명시적 인자는 Preview/테스트에도 쓴다. */
@Composable
fun MonitTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkMonitColors else LightMonitColors
    CompositionLocalProvider(LocalMonitColors provides colors) {
        MaterialTheme(
            colorScheme = colors.materialScheme(darkTheme), typography = MonitTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(MonitSizes.fieldRadius),
                small = RoundedCornerShape(MonitSizes.controlRadius),
                medium = RoundedCornerShape(MonitSizes.controlRadius),
                large = RoundedCornerShape(MonitSizes.controlRadius),
                extraLarge = RoundedCornerShape(MonitSizes.controlRadius),
            ),
            content = content,
        )
    }
}
