package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.LineBreak
import com.monit.core.designsystem.component.MonitSelectableCard
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 선택과 잔액 확정은 독립한다. null 잔액을 0원으로 바꾸지 않는다. */
@Composable
internal fun AccountCardSample(
    name: String,
    balanceText: String?,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    val colors = MonitTheme.colors
    val unknown = stringResource(R.string.monit_balance_unknown)
    MonitSelectableCard(
        selected, onClick, modifier, enabled = enabled && disabledReason == null,
        stateDescription = disabledReason
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Text(
            balanceText ?: unknown, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph)
        )
        Text(
            disabledReason ?: description,
            modifier = if (disabledReason != null) Modifier.clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}
