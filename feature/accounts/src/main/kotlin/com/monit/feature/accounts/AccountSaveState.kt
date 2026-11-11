package com.monit.feature.accounts

import com.monit.feature.accounts.model.SubmittedAccountState

sealed interface AccountSaveState {
    data object Initializing : AccountSaveState
    data object Editing : AccountSaveState
    data object InitializationFailed : AccountSaveState
    data class Saving(val submission: SubmittedAccountState) : AccountSaveState
    data class CheckingResult(val submission: SubmittedAccountState) : AccountSaveState
    data class Unconfirmed(val submission: SubmittedAccountState) : AccountSaveState
    data class Saved(val operationId: String, val accountId: String) : AccountSaveState
    data class Unavailable(val reason: AccountProblem) : AccountSaveState
}
