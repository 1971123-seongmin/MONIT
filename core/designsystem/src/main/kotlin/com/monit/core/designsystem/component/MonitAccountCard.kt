package com.monit.core.designsystem.component

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 선택과 잔액 확정은 독립한다. null 잔액을 0원으로 바꾸지 않는다. */
@Composable
fun MonitAccountCard(
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
    Surface(selected = selected, onClick = onClick, enabled = enabled && disabledReason == null,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            role = Role.RadioButton
            disabledReason?.let { stateDescription = it }
        },
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
        border = if (selected) BorderStroke(MonitTheme.sizes.focusOutline, colors.primary) else null,
        color = if (selected) colors.surface else colors.surfaceContainer, contentColor = colors.onSurface) {
        Column(Modifier.padding(MonitTheme.spacing.content), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(balanceText ?: unknown, Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph))
            Text(disabledReason ?: description,
                modifier = if (disabledReason != null) Modifier.clearAndSetSemantics {} else Modifier,
                style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
    }
}
