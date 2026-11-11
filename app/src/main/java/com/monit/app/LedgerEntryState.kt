package com.monit.app

import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerEntryProblem

sealed interface LedgerEntryState {
    data object Loading : LedgerEntryState
    data class Ready(val context: LedgerContext) : LedgerEntryState
    data class RecoveryRequired(val reason: LedgerEntryProblem) : LedgerEntryState
    data object Failed : LedgerEntryState
}
