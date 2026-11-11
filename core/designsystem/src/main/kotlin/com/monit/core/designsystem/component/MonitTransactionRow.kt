package com.monit.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import com.monit.core.designsystem.theme.MonitTheme

/** 금액·부호·조회 기준은 호출자가 확정한다. 세 줄을 가변 높이로 표시한다. */
@Composable
fun MonitTransactionRow(
    kindLabel: String,
    title: String,
    amountText: String,
    accountLabel: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    income: Boolean = false,
) {
    val colors = MonitTheme.colors
    Surface(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius), color = colors.surfaceContainer, contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(MonitTheme.spacing.content), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            Text("$kindLabel · $title", style = MaterialTheme.typography.labelLarge)
            Text(amountText, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                color = if (income) colors.primary else colors.onSurface)
            Text(accountLabel, style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}
