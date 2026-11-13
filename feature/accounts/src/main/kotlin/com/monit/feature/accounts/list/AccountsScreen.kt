package com.monit.feature.accounts.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme

@Composable
fun AccountsScreen(
    state: AccountsState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRegisterAccount: (() -> Unit)? = null,
    onImportPreview: (() -> Unit)? = null,
    onAccountSelected: ((String) -> Unit)? = null,
    onMonthChange: (Long) -> Unit = {},
) {
    LazyColumn(
        modifier
            .testTag("accounts-list")
            .padding(horizontal = MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        item("title") {
            MonitTopBar("계좌")
        }

        if (onRegisterAccount != null && state is AccountsState.Ready) item("register-account") {
            MonitButton(
                "계좌 추가",
                Modifier.fillMaxWidth(),
                onClick = onRegisterAccount,
            )
        }

        if (onImportPreview != null && state is AccountsState.Ready) item("import-preview") {
            MonitButton(
                text = "이관 미리보기",
                modifier = Modifier.fillMaxWidth(),
                onClick = onImportPreview,
            )
        }

        when (state) {
            AccountsState.Loading -> item("loading") {
                MonitStatusPanel(
                    "불러오는 중",
                    "계좌와 장부 잔액을 확인하고 있어요.",
                    Modifier.fillMaxWidth(),
                )
            }

            AccountsState.Failed -> item("error") {
                MonitStatusPanel(
                    "조회하지 못했어요",
                    "기존 계좌는 그대로 보존됩니다. 다시 확인해 주세요.",
                    Modifier.fillMaxWidth(),
                    error = true,
                    action = {
                        MonitButton(
                            "다시 불러오기",
                            onClick = onRetry,
                        )
                    },
                )
            }

            is AccountsState.Ready -> {
                item("summary") {
                    Text(
                        state.ui.balanceSummary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                if (state.ui.month.isNotEmpty()) item("asset-summary") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text("${state.ui.asOf} 기준 · KRW 장부 잔액")

                        Text(state.ui.assetSummary)

                        Text(state.ui.exclusions)

                        Row(horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
                            MonitButton(
                                text = "이전 달",
                                onClick = { onMonthChange(-1) },
                            )

                            MonitButton(
                                text = "다음 달",
                                onClick = { onMonthChange(1) },
                            )
                        }

                        Text("${state.ui.month} 저축·투자 납입 현황")

                        Text(state.ui.flows)

                        Text("일반 계좌와 저축·투자 계좌 사이 이체 기준 · 당시 계좌 성격으로 분류해요. 재배치·보험·직접 수입/지출은 제외해요.")

                        Text("시장 평가액·수익률·순자산이 아니에요. 미확인 금액은 0원으로 계산하지 않아요.")
                    }
                }

                if (state.ui.accounts.isEmpty()) item("empty") {
                    MonitStatusPanel(
                        "등록된 계좌가 없어요",
                        "계좌가 등록되면 이곳에서 장부 잔액을 확인할 수 있어요.",
                        Modifier.fillMaxWidth(),
                    )
                }

                items(
                    state.ui.accounts,
                    key = { "account:" + it.id },
                    contentType = { "account" },
                ) { account ->
                    MonitListItem(
                        headline = {
                            Text(account.title)
                        },
                        supportingContent = {
                            Text(account.balance)
                        },
                        detailContent = {
                            Text(account.description)
                        },
                        onClick = onAccountSelected?.let { select -> { select(account.id) } },
                    )
                }
            }
        }
    }
}
