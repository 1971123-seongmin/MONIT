package com.monit.feature.transactions

import java.time.LocalDate
import java.time.YearMonth

/** 표시·선택에 필요한 자료만 보유한다. 조회 진행과 Repository 원문은 별도 소유다. */
data class TransactionsUiState(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val today: LocalDate,
    val calendar: Boolean = true,
    val selectedKindIndex: Int = 0,
    val selectedAccountId: String? = null,
    val accountOptions: List<AccountFilterUiState> = emptyList(),
    val income: String = "",
    val expense: String = "",
    val dates: List<TransactionDateUiState> = emptyList(),
    val rows: List<TransactionRowUiState> = emptyList(),
)

data class AccountFilterUiState(val id: String, val name: String)
data class TransactionDateUiState(
    val date: LocalDate,
    val summary: String,
    val spokenSummary: String
)

data class TransactionRowUiState(
    val id: String,
    val dateLabel: String,
    val headline: String,
    val amount: String,
    val accounts: String,
    val income: Boolean,
)
