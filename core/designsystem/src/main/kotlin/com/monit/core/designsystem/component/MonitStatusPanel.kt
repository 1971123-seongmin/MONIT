package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 상태의 판정은 소유자가 한다. 결과 불명·저장 실패·정상 빈 결과를 여기서 추정하지 않는다. */
@Composable
fun MonitStatusPanel(title: String, description: String, actionLabel: String, onAction: () -> Unit,
    modifier: Modifier = Modifier, busy: Boolean = false, error: Boolean = false) {
    val colors = MonitTheme.colors
    MonitPanel(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.surface) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (error) colors.error else colors.onSurface)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        MonitButton(actionLabel, onAction, Modifier.fillMaxWidth(), loading = busy,
            loadingText = actionLabel, loadingDescription = title)
    }
}
