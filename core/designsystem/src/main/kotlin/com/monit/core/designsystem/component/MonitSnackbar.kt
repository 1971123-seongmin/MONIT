package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** SnackbarHost의 snackbar 슬롯에 사용한다. 표시·닫기 수명은 Host가 관리한다. */
@Composable
fun MonitSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MonitTheme.colors.onSurface,
        contentColor = MonitTheme.colors.surfaceContainer,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(MonitTheme.spacing.content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            Box(Modifier.weight(1f)) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MonitTheme.colors.surfaceContainer,
                )
            }

            action?.invoke()
        }
    }
}
