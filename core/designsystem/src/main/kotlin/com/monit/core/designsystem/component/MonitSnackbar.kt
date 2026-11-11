package com.monit.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** SnackbarHost의 snackbar 슬롯에 사용한다. 표시·닫기 수명은 Host가 관리한다. */
@Composable
fun MonitSnackbar(message: String, modifier: Modifier = Modifier) {
    MonitPanel(modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MonitTheme.colors.onSurface) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MonitTheme.colors.surfaceContainer)
    }
}
