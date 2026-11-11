package com.monit.core.designsystem.component.textfield

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

@Immutable
data class MonitTextFieldLayout(
    val textStyle: TextStyle,
    val labelStyle: TextStyle,
    val supportingStyle: TextStyle,
    val shape: Shape,
    val contentPadding: PaddingValues,
    val minHeight: Dp,
    val supportingSpacing: Dp,
    val iconSpacing: Dp,
    val labelSpacing: Dp,
    val outlineWidth: Dp,
    val focusedOutlineWidth: Dp,
)
