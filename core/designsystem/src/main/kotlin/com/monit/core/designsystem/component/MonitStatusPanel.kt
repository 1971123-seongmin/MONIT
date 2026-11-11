package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 상태의 판정은 소유자가 한다. 결과 불명·저장 실패·정상 빈 결과를 여기서 추정하지 않는다. */
@Composable
fun MonitStatusPanel(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    val colors = MonitTheme.colors
    Surface(
        modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.surface,
        contentColor = colors.onSurface, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)
    ) {
        Column(
            Modifier.padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)
        ) {
            icon?.invoke()
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (error) colors.error else colors.onSurface
            )
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            action?.invoke()
        }
    }
}
