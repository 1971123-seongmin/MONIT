package com.monit.core.designsystem.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.theme.MonitTheme

/** 표시만 제공한다. 수동 납입 확인·완료 판정·금융 저장 API를 연결하지 않는다. */
@Composable
internal fun PaymentStatusRowSample(
    title: String,
    scheduleText: String,
    statusText: String,
    modifier: Modifier = Modifier,
    completed: Boolean = false,
) {
    MonitCard(modifier.semantics(mergeDescendants = true) {}) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
        )

        Text(
            scheduleText,
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = if (completed) MonitTheme.colors.primary else MonitTheme.colors.muted,
        )
    }
}
