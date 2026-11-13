package com.monit.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme

@Composable
fun HomeScreen(
    state: HomeState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onNewTransaction: (() -> Unit)? = null,
    onPayments: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier.padding(horizontal = MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        item("title") {
            MonitTopBar("이번 달 루틴")
        }

        if (onNewTransaction != null && state is HomeState.Ready) item("new-transaction") {
            MonitButton(
                "거래 기록",
                Modifier.fillMaxWidth(),
                onClick = onNewTransaction,
            )
        }

        when (state) {
            HomeState.Loading -> item("loading") {
                MonitStatusPanel(
                    "불러오는 중",
                    "이번 달 거래를 확인하고 있어요.",
                    Modifier.fillMaxWidth(),
                )
            }

            HomeState.Failed -> item("error") {
                MonitStatusPanel(
                    "조회하지 못했어요",
                    "기존 기록은 그대로 보존됩니다. 다시 확인해 주세요.",
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

            is HomeState.Ready -> item("summary") {
                MonitCard(
                    Modifier.fillMaxWidth(),
                    color = MonitTheme.colors.surface,
                ) {
                    Text(
                        state.ui.month,
                        style = MaterialTheme.typography.labelLarge,
                    )

                    Text(
                        "수입 " + state.ui.income,
                        color = MonitTheme.colors.primary,
                    )

                    Text("지출 " + state.ui.expense)

                    Text(
                        "내부 이체는 수입·지출 합계에서 제외",
                        style = MaterialTheme.typography.labelSmall,
                        color = MonitTheme.colors.muted,
                    )
                }
            }
        }

        if (state is HomeState.Ready) {
            item(
                "payments",
                "header",
            ) {
                Text(
                    "확인할 납입",
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(state.ui.paymentSummary)

                Text("은행 거래를 자동 확인하지 않아요. 실제 납입을 직접 검토해 주세요.")

                if (onPayments != null) MonitButton(
                    "월별 납입 보기",
                    Modifier.fillMaxWidth(),
                    onClick = onPayments,
                )
            }
            items(
                state.ui.payments,
                key = { "payment:" + it.id },
                contentType = { "payment" },
            ) { payment ->
                MonitListItem(
                    headline = {
                        Text(payment.name)
                    },
                    supportingContent = {
                        Text(payment.schedule)
                    },
                    detailContent = {
                        Text(payment.status)
                    },
                    onClick = onPayments,
                )
            }
            if (state.ui.payments.isEmpty()) item(
                "payments-empty",
                "status",
            ) {
                Text("이번 달 예정된 납입이 없어요.")
            }
        }
    }
}
