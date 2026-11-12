package com.monit.feature.transactions.entry

data class TransactionEntryState(
    val ui: TransactionEntryUiState = TransactionEntryUiState(),
    val phase: EntryPhase = EntryPhase.LOADING,
    val slot: AccountSlot? = null,
    val selectionRequestId: String? = null,
    val finishing: Boolean = false,
    val canReload: Boolean = false,
    val resultAbsent: Boolean = false,
    val inputError: TransactionInputError? = null,
    val validationAttempt: Int = 0,
)
