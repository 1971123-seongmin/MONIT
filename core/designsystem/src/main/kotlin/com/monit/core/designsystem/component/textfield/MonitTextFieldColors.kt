package com.monit.core.designsystem.component.textfield

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class MonitTextFieldColors(
    val container: Color,
    val disabledContainer: Color,
    val text: Color,
    val disabledText: Color,
    val label: Color,
    val outline: Color,
    val focusedOutline: Color,
    val disabledOutline: Color,
    val error: Color,
)
