package com.monit.core.designsystem.component.button

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class MonitButtonColors(
    val container: Color,
    val content: Color,
    val pressedContainer: Color,
    val pressedContent: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val focusOutline: Color,
)
