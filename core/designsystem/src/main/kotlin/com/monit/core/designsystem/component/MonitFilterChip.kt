package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.theme.MonitTheme

/** 선택 동작과 semantics·키보드·ripple은 M3 selectable Surface에 맡긴다. */
@Composable
fun MonitFilterChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MonitTheme.colors
    Surface(selected = selected, onClick = onClick, enabled = enabled,
        modifier = modifier.widthIn(min = 154.dp).heightIn(min = MonitTheme.sizes.touchTarget)
            .semantics { role = Role.Checkbox },
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
        color = when { !enabled -> colors.disabledBackground; selected -> colors.primary; else -> colors.surface },
        contentColor = when { !enabled -> colors.disabledText; selected -> colors.onPrimary; else -> colors.onSurface }) {
        Text(label, Modifier.padding(MonitTheme.spacing.content), style = MaterialTheme.typography.labelLarge)
    }
}
