package com.monit.core.designsystem.component.chip

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.monit.core.designsystem.theme.MonitTheme

object MonitChipDefaults {
    @Composable
    fun colors(type: MonitChipType = MonitChipType.Filled): MonitChipColors = with(MonitTheme.colors) {
        MonitChipColors(
            container = if (type == MonitChipType.Filled) surface else surfaceContainer,
            content = onSurface,
            selectedContainer = primary,
            selectedContent = onPrimary,
            disabledContainer = disabledBackground,
            disabledContent = disabledText,
            outline = if (type == MonitChipType.Outlined) outline else null,
            selectedOutline = if (type == MonitChipType.Outlined) primary else null,
            disabledOutline = if (type == MonitChipType.Outlined) disabledBackground else null,
        )
    }

    @Composable
    fun layout(size: MonitChipSize = MonitChipSize.Regular): MonitChipLayout = MonitChipLayout(
        contentPadding = when (size) {
            MonitChipSize.Regular -> PaddingValues(MonitTheme.spacing.content)

            MonitChipSize.Compact -> PaddingValues(
                horizontal = MonitTheme.spacing.content,
                vertical = MonitTheme.spacing.inline,
            )
        },
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
        textStyle = MaterialTheme.typography.labelLarge,
        minTouchSize = MonitTheme.sizes.touchTarget,
        iconSize = MonitTheme.sizes.icon,
        iconSpacing = MonitTheme.spacing.inline,
        outlineWidth = MonitTheme.sizes.outline,
    )
}
