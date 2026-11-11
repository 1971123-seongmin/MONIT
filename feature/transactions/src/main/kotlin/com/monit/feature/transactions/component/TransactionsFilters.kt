package com.monit.feature.transactions.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.chip.MonitFilterChip
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.transactions.TransactionsUiState

@Composable
internal fun TransactionsFilters(
    ui: TransactionsUiState,
    onKind: (Int) -> Unit,
    onAccount: (String?) -> Unit,
) {
    var accountExpanded by rememberSaveable { mutableStateOf(false) }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        Box {
            MonitFilterChip(
                label = if (ui.selectedAccountId == null) "전체 계좌"
                else ui.accountOptions.find { it.id == ui.selectedAccountId }?.name ?: "이전 선택 계좌",
                selected = ui.selectedAccountId != null,
                onClick = { accountExpanded = true },
            )

            DropdownMenu(
                accountExpanded,
                onDismissRequest = { accountExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text("전체 계좌")
                    },
                    onClick = {
                        onAccount(null)

                        accountExpanded = false
                    },
                )

                ui.accountOptions.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Text(account.name)
                        },
                        onClick = {
                            onAccount(account.id)

                            accountExpanded = false
                        },
                    )
                }
            }
        }

        listOf(
            "전체 유형",
            "지출",
            "수입",
            "이체",
        ).forEachIndexed { index, name ->
            MonitFilterChip(
                name,
                selected = ui.selectedKindIndex == index,
                onClick = { onKind(index) },
            )
        }
    }
}
