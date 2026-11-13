package com.monit.feature.home.payments

import com.monit.core.common.draft.DraftEditorState

enum class PaymentsMode { MONTH, DETAIL, RULE_FORM, TRANSACTION_FORM, CHOOSE_TRANSACTION, REVIEW, RESULT }
enum class PaymentsPhase { LOADING, READY, SAVING, CHECKING, UNCONFIRMED, SAVED, REJECTED, BLOCKED }

data class PaymentsState(
    val ui: PaymentsUiState = PaymentsUiState(),
    val mode: PaymentsMode = PaymentsMode.MONTH,
    val phase: PaymentsPhase = PaymentsPhase.LOADING,
    val draft: DraftEditorState? = null,
    val resultAbsent: Boolean = false,
    val finishing: Boolean = false,
    val picker: String? = null,
    val showExit: Boolean = false,
)
