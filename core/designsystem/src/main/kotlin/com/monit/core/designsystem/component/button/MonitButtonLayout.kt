package com.monit.core.designsystem.component.button

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/** 테마에서 해석한 배치 값. 호출자는 copy로 필요한 값만 바꾸며 렌더러는 테마를 조회하지 않는다. */
@Immutable
data class MonitButtonLayout(
    val contentPadding: PaddingValues,
    val textStyle: TextStyle,
    val minHeight: Dp,
    val cornerRadius: Dp,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val focusOutlineWidth: Dp,
    val focusGap: Dp,
)
