package com.monit.core.designsystem.component.button

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.monit.core.designsystem.theme.MonitTheme

/** 테마·프리셋을 렌더링에 필요한 값으로 해석하는 기본값 공급자. */
object MonitButtonDefaults {
    @Composable
    fun colors(type: MonitButtonType = MonitButtonType.Primary): MonitButtonColors {
        val theme = MonitTheme.colors
        return when (type) {
            MonitButtonType.Primary -> MonitButtonColors(
                container = theme.primary, content = theme.onPrimary,
                pressedContainer = theme.pressed, pressedContent = theme.onPrimary,
                disabledContainer = theme.disabledBackground, disabledContent = theme.disabledText,
                focusOutline = theme.primary,
            )
            MonitButtonType.Secondary -> MonitButtonColors(
                container = theme.surface, content = theme.primary,
                pressedContainer = theme.surfaceContainer, pressedContent = theme.primary,
                disabledContainer = theme.disabledBackground, disabledContent = theme.disabledText,
                focusOutline = theme.primary,
            )
        }
    }

    @Composable
    fun layout(size: MonitButtonSize = MonitButtonSize.Regular): MonitButtonLayout = MonitButtonLayout(
        contentPadding = when (size) {
            // 기존 Compose의 24/12 패딩을 간격 값으로 해석하며 모서리 반경과 결합하지 않는다.
            MonitButtonSize.Regular -> PaddingValues(horizontal = MonitTheme.spacing.section, vertical = MonitTheme.spacing.section / 2)
            MonitButtonSize.Compact -> PaddingValues(horizontal = MonitTheme.spacing.content, vertical = MonitTheme.spacing.inline)
        }, textStyle = MaterialTheme.typography.labelLarge, minHeight = MonitTheme.sizes.touchTarget,
        cornerRadius = MonitTheme.sizes.controlRadius, iconSize = MonitTheme.sizes.icon,
        iconSpacing = MonitTheme.spacing.inline, focusOutlineWidth = MonitTheme.sizes.focusOutline,
        focusGap = MonitTheme.sizes.focusGap,
    )
}
