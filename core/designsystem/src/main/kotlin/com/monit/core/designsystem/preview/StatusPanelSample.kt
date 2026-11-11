package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.button.MonitButton

@Composable
internal fun StatusPanelSample(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    error: Boolean = false,
) {
    MonitStatusPanel(
        title,
        description,
        modifier,
        error = error,
        action = {
            MonitButton(
                actionLabel,
                Modifier.fillMaxWidth(),
                loading = busy,
                loadingText = actionLabel,
                loadingDescription = title,
                onClick = onAction,
            )
        },
    )
}
