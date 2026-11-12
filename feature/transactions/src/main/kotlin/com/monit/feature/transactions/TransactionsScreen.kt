package com.monit.feature.transactions

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
import com.monit.core.designsystem.component.MonitSegmentedControl
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.transactions.component.TransactionsCalendar
import com.monit.feature.transactions.component.TransactionsFilters
import com.monit.feature.transactions.component.TransactionsMonthHeader
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun TransactionsScreen(
    state: TransactionsState,
    onRetry: () -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onCalendarSelected: (Boolean) -> Unit,
    onKindSelected: (Int) -> Unit,
    onAccountSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onNewTransaction: (() -> Unit)? = null,
    onTransactionSelected: ((String) -> Unit)? = null,
) {
    val ui = state.ui

    LazyColumn(
        modifier.padding(
            horizontal = if (ui.calendar) MonitTheme.spacing.content - MonitTheme.spacing.compact
            else MonitTheme.spacing.content,
        ),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        item("title") {
            MonitTopBar("거래")
        }

        if (onNewTransaction != null && state.load == TransactionsLoadState.READY) item("new-transaction") {
            MonitButton(
                "선택한 날짜에 거래 기록",
                Modifier.fillMaxWidth(),
                onClick = onNewTransaction,
            )
        }

        item("view") {
            MonitSegmentedControl(
                listOf(
                    "달력",
                    "목록",
                ),
                if (ui.calendar) 0 else 1,
                { onCalendarSelected(it == 0) },
                Modifier.fillMaxWidth(),
            )
        }

        item("filters") {
            TransactionsFilters(
                ui,
                onKindSelected,
                onAccountSelected,
            )
        }

        item("month") {
            TransactionsMonthHeader(
                ui,
                onMonthChange,
                onToday,
            )
        }

        when (state.load) {
            TransactionsLoadState.LOADING -> item("loading") {
                MonitStatusPanel(
                    "불러오는 중",
                    "선택한 월의 거래를 확인하고 있어요.",
                    Modifier.fillMaxWidth(),
                )
            }

            TransactionsLoadState.FAILED -> item("error") {
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

            TransactionsLoadState.READY -> {
                item("summary") {
                    MonitCard(
                        Modifier.fillMaxWidth(),
                        color = MonitTheme.colors.surface,
                    ) {
                        Text(
                            "${ui.month.monthValue}월 전체 · 필터 적용 전",
                            style = MaterialTheme.typography.labelLarge,
                        )

                        Text(
                            "수입 ${ui.income}",
                            color = MonitTheme.colors.primary,
                        )

                        Text("지출 ${ui.expense}")

                        Text(
                            "내부 이체는 수입·지출 합계에서 제외",
                            style = MaterialTheme.typography.labelSmall,
                            color = MonitTheme.colors.muted,
                        )
                    }
                }

                if (ui.calendar) item("calendar") {
                    TransactionsCalendar(
                        ui,
                        onDateSelected,
                    )
                }

                item("scope") {
                    Text(
                        if (ui.calendar) "${ui.selectedDate} · 필터 결과" else "${ui.month} 월 전체 목록 · 필터 결과",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                if (ui.rows.isEmpty()) item("empty") {
                    MonitStatusPanel(
                        "거래가 없어요",
                        "선택한 날짜·월과 필터에 맞는 거래가 없어요.",
                        Modifier.fillMaxWidth(),
                    )
                }

                items(
                    ui.rows,
                    key = { "transaction:" + it.id },
                    contentType = { "transaction" },
                ) { row ->
                    MonitListItem(
                        onClick = onTransactionSelected?.let { callback -> { callback(row.id) } },
                        headline = {
                            Text(if (ui.calendar) row.headline else row.dateLabel + " · " + row.headline)
                        },
                        supportingContent = {
                            Text(
                                row.amount,
                                color = if (row.income) MonitTheme.colors.primary else MonitTheme.colors.onSurface,
                            )
                        },
                        detailContent = {
                            Text(row.accounts)
                        },
                    )
                }
            }
        }
    }
}
