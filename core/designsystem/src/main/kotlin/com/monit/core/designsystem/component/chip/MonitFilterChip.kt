package com.monit.core.designsystem.component.chip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics

/** 값으로 해석한 색·배치를 적용하고 선택·키보드·ripple은 M3 toggleable Surface에 맡긴다. */
@Composable
fun MonitFilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    type: MonitChipType = MonitChipType.Filled,
    size: MonitChipSize = MonitChipSize.Regular,
    colors: MonitChipColors = MonitChipDefaults.colors(type),
    layout: MonitChipLayout = MonitChipDefaults.layout(size),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val borderColor = when {
        !enabled -> colors.disabledOutline; selected -> colors.selectedOutline; else -> colors.outline
    }
    Surface(
        checked = selected, onCheckedChange = { onClick() }, enabled = enabled,
        modifier = modifier.widthIn(min = layout.minTouchSize).heightIn(min = layout.minTouchSize)
            .semantics { role = Role.Checkbox },
        shape = layout.shape, border = borderColor?.let { BorderStroke(layout.outlineWidth, it) },
        color = when {
            !enabled -> colors.disabledContainer; selected -> colors.selectedContainer; else -> colors.container
        },
        contentColor = when {
            !enabled -> colors.disabledContent; selected -> colors.selectedContent; else -> colors.content
        }
    ) {
        Row(
            Modifier.padding(layout.contentPadding), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(layout.iconSpacing)
        ) {
            leadingIcon?.let {
                Box(
                    Modifier.size(layout.iconSize).clearAndSetSemantics {}) { it() }
            }
            Text(label, style = layout.textStyle)
            trailingIcon?.let {
                Box(
                    Modifier.size(layout.iconSize).clearAndSetSemantics {}) { it() }
            }
        }
    }
}
