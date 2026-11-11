package com.monit.core.designsystem.component.chip

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class MonitChipColors(
    val container: Color,
    val content: Color,
    val selectedContainer: Color,
    val selectedContent: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
    val outline: Color? = null,
    val selectedOutline: Color? = null,
    val disabledOutline: Color? = null,
)
