package com.monit.feature.transactions.entry

import com.monit.core.model.TransactionKind

data class TransactionEntryUiState(
    val kind: TransactionKind = TransactionKind.EXPENSE,
    val amount: String = "",
    val date: String = "",
    val memo: String = "",
    val debitAccountId: String? = null,
    val creditAccountId: String? = null,
    val accounts: List<EntryAccountUiState> = emptyList(),
    val candidateAccountId: String? = null,
    val error: String? = null,
)

data class EntryAccountUiState(val id: String, val name: String)
