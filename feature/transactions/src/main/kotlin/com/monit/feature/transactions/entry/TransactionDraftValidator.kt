package com.monit.feature.transactions.entry

import com.monit.core.model.TransactionKind
import com.monit.core.model.requireLedgerText
import com.monit.core.model.requireTransactionAmount
import com.monit.core.model.transactionDateOf
import java.time.LocalDate

/** 원문을 보정하지 않고 첫 오류를 판정한다. 최종 계좌 상태는 writer가 재검사한다. */
internal fun validateTransactionDraft(ui: TransactionEntryUiState, today: LocalDate): TransactionInputError? = when {
    runCatching {
        require(ui.amount.matches(Regex("[0-9]{1,12}")))
        requireTransactionAmount(ui.amount.toLong())
    }.isFailure -> TransactionInputError.AMOUNT

    runCatching {
        val date = LocalDate.parse(ui.date)
        require(date.toString() == ui.date)
        transactionDateOf(date.year, date.monthValue, date.dayOfMonth, today)
    }.isFailure -> TransactionInputError.DATE

    ui.kind != TransactionKind.INCOME && ui.accounts.none { it.id == ui.debitAccountId } -> TransactionInputError.DEBIT_ACCOUNT

    ui.kind != TransactionKind.EXPENSE &&
        (
            ui.accounts.none { it.id == ui.creditAccountId } ||
                (ui.kind == TransactionKind.TRANSFER && ui.debitAccountId == ui.creditAccountId)
            ) -> TransactionInputError.CREDIT_ACCOUNT

    runCatching { requireLedgerText(ui.memo, allowBlank = true) }.isFailure -> TransactionInputError.MEMO

    else -> null
}
