package com.monit.app.entry

import com.monit.core.model.SubmissionRecoveryItem
import com.monit.core.model.WriteReceipt

data class EntryRecoveryState(
    val items: List<SubmissionRecoveryItem> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val selected: Int? = null,
    val receipt: WriteReceipt? = null,
    val rejection: com.monit.core.model.TransactionRejection? = null,
    val resultAbsent: Boolean = false,
    val ready: Boolean = false,
    val ui: EntryRecoveryUiState = EntryRecoveryUiState(),
)
