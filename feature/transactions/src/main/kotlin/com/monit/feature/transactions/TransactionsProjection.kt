package com.monit.feature.transactions

import com.monit.core.model.LedgerSnapshot
import com.monit.core.model.TransactionKind
import com.monit.core.model.sumSignedAmounts
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 월 전체 합계는 필터에 영향받지 않으며 이체는 금액 합계에 포함하지 않는다. */
internal fun LedgerSnapshot.toTransactionsUiState(selection: TransactionsUiState): TransactionsUiState {
    val format = NumberFormat.getIntegerInstance(Locale.KOREA)
    fun money(amount: Long) = format.format(amount) + "원"
    fun compact(amount: Long): String = when {
        amount >= 100_000_000 -> BigDecimal.valueOf(amount)
            .divide(BigDecimal.valueOf(100_000_000), 1, RoundingMode.DOWN).stripTrailingZeros()
            .toPlainString() + "억"

        amount >= 10_000 -> BigDecimal.valueOf(amount)
            .divide(BigDecimal.valueOf(10_000), 1, RoundingMode.DOWN).stripTrailingZeros()
            .toPlainString() + "만"

        else -> format.format(amount)
    }

    val monthRows = transactions.filter { YearMonth.from(it.date) == selection.month }
    val kind = listOf(
        null,
        TransactionKind.EXPENSE,
        TransactionKind.INCOME,
        TransactionKind.TRANSFER,
    )[selection.selectedKindIndex]
    val filtered = monthRows.filter {
        (kind == null || it.kind == kind) && (
            selection.selectedAccountId == null ||
                it.debitAccountId == selection.selectedAccountId || it.creditAccountId == selection.selectedAccountId
            )
    }
    val names = accounts.associate { it.id to it.name }
    val days = filtered.groupBy { it.date }
    val dateFormat = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
    return selection.copy(
        accountOptions = accounts.map { AccountFilterUiState(it.id, it.name) },
        income = money(
            sumSignedAmounts(
                monthRows.filter { it.kind == TransactionKind.INCOME }
                    .map { it.amount },
            ),
        ),
        expense = money(
            sumSignedAmounts(
                monthRows.filter { it.kind == TransactionKind.EXPENSE }
                    .map { it.amount },
            ),
        ),
        dates = (1..selection.month.lengthOfMonth()).map { day ->
            val date = selection.month.atDay(day)
            val rows = days[date].orEmpty()
            val summaries = buildList {
                val income = rows.filter { it.kind == TransactionKind.INCOME }
                val expense = rows.filter { it.kind == TransactionKind.EXPENSE }
                val transfers = rows.count { it.kind == TransactionKind.TRANSFER }
                if (income.isNotEmpty()) add("수입 " + money(sumSignedAmounts(income.map { it.amount })))
                if (expense.isNotEmpty()) add("지출 " + money(sumSignedAmounts(expense.map { it.amount })))
                if (transfers > 0) add("이체 " + transfers + "건")
            }
            val compactSummary = buildList {
                val income = rows.filter { it.kind == TransactionKind.INCOME }
                val expense = rows.filter { it.kind == TransactionKind.EXPENSE }
                val transfers = rows.count { it.kind == TransactionKind.TRANSFER }
                if (income.isNotEmpty()) add("＋" + compact(sumSignedAmounts(income.map { it.amount })))
                if (expense.isNotEmpty()) add("−" + compact(sumSignedAmounts(expense.map { it.amount })))
                if (transfers > 0) add("이체 ${transfers}건")
            }
            TransactionDateUiState(
                date,
                compactSummary.joinToString("\n"),
                summaries.joinToString(", "),
            )
        },
        rows = filtered.filter { !selection.calendar || it.date == selection.selectedDate }
            .sortedWith(compareByDescending<com.monit.core.model.LedgerTransaction> { it.date }.thenBy { it.id })
            .map {
                val kindLabel = when (it.kind) {
                    TransactionKind.EXPENSE -> "지출"
                    TransactionKind.INCOME -> "수입"
                    TransactionKind.TRANSFER -> "이체"
                }
                TransactionRowUiState(
                    it.id,
                    it.date.format(dateFormat),
                    if (it.memo.isBlank()) kindLabel else "$kindLabel · ${it.memo}",
                    (
                        when (it.kind) {
                            TransactionKind.EXPENSE -> "−"
                            TransactionKind.INCOME -> "+"
                            else -> ""
                        }
                        ) + money(it.amount),
                    listOfNotNull(
                        it.debitAccountId?.let(names::get),
                        it.creditAccountId?.let(names::get),
                    ).joinToString(" → "),
                    it.kind == TransactionKind.INCOME,
                )
            },
    )
}
