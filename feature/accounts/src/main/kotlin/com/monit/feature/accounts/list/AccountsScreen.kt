package com.monit.feature.accounts.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme

@Composable
fun AccountsScreen(state: AccountsState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.padding(horizontal = MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        item("title") { MonitTopBar("계좌") }
        when (state) {
            AccountsState.Loading -> item("loading") {
                MonitStatusPanel("불러오는 중", "계좌와 장부 잔액을 확인하고 있어요.", Modifier.fillMaxWidth())
            }

            AccountsState.Failed -> item("error") {
                MonitStatusPanel(
                    "조회하지 못했어요", "기존 계좌는 그대로 보존됩니다. 다시 확인해 주세요.",
                    Modifier.fillMaxWidth(), error = true,
                    action = { MonitButton("다시 불러오기", onClick = onRetry) })
            }

            is AccountsState.Ready -> {
                item("summary") {
                    Text(state.ui.balanceSummary, style = MaterialTheme.typography.labelLarge)
                }
                if (state.ui.accounts.isEmpty()) item("empty") {
                    MonitStatusPanel(
                        "등록된 계좌가 없어요",
                        "계좌가 등록되면 이곳에서 장부 잔액을 확인할 수 있어요.",
                        Modifier.fillMaxWidth()
                    )
                }
                items(
                    state.ui.accounts,
                    key = { "account:" + it.id },
                    contentType = { "account" }) { account ->
                    MonitListItem(
                        headline = { Text(account.title) },
                        supportingContent = { Text(account.balance) },
                        detailContent = { Text(account.description) },
                    )
                }
            }
        }
    }
}
