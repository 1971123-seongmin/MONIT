package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 표시 값만 받는다. balanceText=null은 미확인으로서 확인한 '0원'과 구분한다. */
data class MonitAccountOption(
    val id: String,
    val name: String,
    val kindLabel: String,
    val baselineLabel: String,
    val balanceText: String?,
    val disabledReason: String? = null,
)

/** 계좌 선택은 ID만 반환한다. 계좌 추가·재시도·화면 복귀·거래 저장은 호출자가 처리한다. */
@Composable
fun MonitAccountPicker(
    accounts: List<MonitAccountOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val colors = MonitTheme.colors
    val saving = stringResource(R.string.monit_saving)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
        if (loading) Text(saving, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (errorMessage != null) {
            Text(errorMessage, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) MonitButton(
                stringResource(R.string.monit_retry), onRetry, Modifier.fillMaxWidth(), enabled = enabled && !loading,
            )
        }
        if (accounts.isEmpty() && errorMessage == null && !loading) {
            Text(stringResource(R.string.monit_no_accounts), style = MaterialTheme.typography.bodyMedium)
            MonitButton(stringResource(R.string.monit_add_account), onAddAccount, Modifier.fillMaxWidth(), enabled = enabled && !loading)
        } else if (accounts.isNotEmpty()) {
            if (accounts.none { it.id == selectedId }) Text(
                stringResource(R.string.monit_choose_account), color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
                for (account in accounts) {
                    key(account.id) {
                        MonitAccountRow(account, account.id == selectedId, enabled && !loading) { onSelect(account.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitAccountRow(account: MonitAccountOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    MonitAccountCard(
        name = "${account.name} · ${account.kindLabel}",
        balanceText = account.balanceText,
        description = listOf(account.baselineLabel,
            if (account.balanceText == null) stringResource(R.string.monit_balance_excluded) else "")
            .filter { it.isNotBlank() }.joinToString(" · "),
        selected = selected, onClick = onClick, enabled = enabled, disabledReason = account.disabledReason,
    )
}
