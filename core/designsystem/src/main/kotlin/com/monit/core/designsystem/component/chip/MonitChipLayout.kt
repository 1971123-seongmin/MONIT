package com.monit.core.designsystem.component.chip

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

@Immutable
data class MonitChipLayout(
    val contentPadding: PaddingValues,
    val shape: Shape,
    val textStyle: TextStyle,
    val minTouchSize: Dp,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val outlineWidth: Dp,
)
