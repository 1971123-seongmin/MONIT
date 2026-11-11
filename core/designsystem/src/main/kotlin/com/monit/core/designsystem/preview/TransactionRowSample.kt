package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.LineBreak
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.theme.MonitTheme

/** 금액·부호·조회 기준은 호출자가 확정한다. 세 줄을 가변 높이로 표시한다. */
@Composable
internal fun TransactionRowSample(
    kindLabel: String,
    title: String,
    amountText: String,
    accountLabel: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    income: Boolean = false,
) {
    val colors = MonitTheme.colors

    MonitListItem(
        headline = {
            Text(
                "$kindLabel · $title",
                style = MaterialTheme.typography.labelLarge,
            )
        },
        modifier = modifier,
        onClick = onClick,
        supportingContent = {
            Text(
                amountText,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                color = if (income) colors.primary else colors.onSurface,
            )
        },
        detailContent = {
            Text(
                accountLabel,
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}
