package com.monit.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 장식 블록을 하나의 로딩 안내로 묶는다. shape는 막대 모양이며 컨테이너는 공통 모서리 규격을 유지한다. */
@Composable
fun MonitSkeleton(
    modifier: Modifier = Modifier,
    lineWidths: List<Float> = listOf(160f / 296, 110f / 296),
    lineHeight: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
) {
    require(lineWidths.isNotEmpty() && lineWidths.all { it > 0f && it <= 1f }) { "lineWidths must contain values in (0, 1]." }

    require(lineHeight > 0.dp) { "lineHeight must be positive; was $lineHeight" }

    val loading = stringResource(R.string.monit_loading)
    val colors = MonitTheme.colors
    // Dark의 두 기존 토큰이 같은 색이므로 이 컴포넌트에서만 승인된 surface를 재사용한다.
    val barColor =
        if (colors.disabledBackground == colors.surfaceContainer) colors.surface else colors.disabledBackground

    Surface(
        modifier.clearAndSetSemantics { contentDescription = loading },
        color = colors.surfaceContainer,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    ) {
        Column(
            Modifier.padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            lineWidths.forEach { fraction ->
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(lineHeight)
                        .background(
                            barColor,
                            shape,
                        ),
                )
            }
        }
    }
}
