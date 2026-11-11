package com.monit.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 장식 블록을 하나의 로딩 안내로 묶는다. 실제 금액·빈 결과를 표시하지 않는다. */
@Composable
fun MonitSkeleton(modifier: Modifier = Modifier, card: Boolean = false) {
    val loading = stringResource(R.string.monit_loading)
    val colors = MonitTheme.colors
    // Dark의 두 기존 토큰이 같은 색이므로 이 컴포넌트에서만 승인된 surface를 재사용한다.
    val barColor = if (colors.disabledBackground == colors.surfaceContainer) colors.surface else colors.disabledBackground
    MonitPanel(modifier.clearAndSetSemantics { contentDescription = loading }) {
        val widths = if (card) listOf(210f / 296, 140f / 296, 270f / 296) else listOf(160f / 296, 110f / 296)
        widths.forEach { fraction ->
            Box(Modifier.fillMaxWidth(fraction).height(16.dp)
                .background(barColor, RoundedCornerShape(MonitTheme.sizes.controlRadius)))
        }
    }
}
