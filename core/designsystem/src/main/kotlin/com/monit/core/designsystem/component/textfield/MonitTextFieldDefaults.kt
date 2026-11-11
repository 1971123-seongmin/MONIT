package com.monit.core.designsystem.component.textfield

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.monit.core.designsystem.theme.MonitTheme

object MonitTextFieldDefaults {
    @Composable
    fun colors(): MonitTextFieldColors = with(MonitTheme.colors) {
        MonitTextFieldColors(surfaceContainer, surface, onSurface, disabledText, muted, outline, primary, disabledBackground, error)
    }

    @Composable
    fun layout(): MonitTextFieldLayout = MonitTextFieldLayout(
        textStyle = MaterialTheme.typography.bodyLarge, labelStyle = MaterialTheme.typography.labelSmall,
        supportingStyle = MaterialTheme.typography.labelSmall, shape = RoundedCornerShape(MonitTheme.sizes.fieldRadius),
        contentPadding = PaddingValues(horizontal = MonitTheme.spacing.content, vertical = MonitTheme.spacing.inline),
        minHeight = MonitTheme.sizes.inputMinHeight, supportingSpacing = MonitTheme.spacing.inline,
        iconSpacing = MonitTheme.spacing.inline, labelSpacing = MonitTheme.spacing.compact,
        outlineWidth = MonitTheme.sizes.outline, focusedOutlineWidth = MonitTheme.sizes.focusOutline,
    )
}
