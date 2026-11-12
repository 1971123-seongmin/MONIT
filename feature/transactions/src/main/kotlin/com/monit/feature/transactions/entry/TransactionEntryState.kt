package com.monit.feature.transactions.entry

import com.monit.core.common.draft.DraftEditorState

data class TransactionEntryState(
    val ui: TransactionEntryUiState = TransactionEntryUiState(),
    val phase: EntryPhase = EntryPhase.LOADING,
    val slot: AccountSlot? = null,
    val selectionRequestId: String? = null,
    val finishing: Boolean = false,
    val existing: Boolean = false,
    val viewing: Boolean = false,
    val deleting: Boolean = false,
    val canReload: Boolean = false,
    val resultAbsent: Boolean = false,
    val inputError: TransactionInputError? = null,
    val validationAttempt: Int = 0,
    val draftPersistence: DraftEditorState? = null,
)
