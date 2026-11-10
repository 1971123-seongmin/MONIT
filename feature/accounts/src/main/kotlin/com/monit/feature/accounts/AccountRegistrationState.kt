package com.monit.feature.accounts

import com.monit.core.model.AccountNature
import com.monit.feature.accounts.model.AccountUiModel
import com.monit.feature.accounts.model.SubmittedAccountState

data class AccountDraft(
    val name: String = "",
    val nature: AccountNature = AccountNature.GENERAL,
    val anchorDate: String,
    val unknownReason: String = "기준 잔액을 아직 확인하지 않았습니다",
)

enum class AccountInputError { NAME, ANCHOR_DATE, UNKNOWN_REASON }

enum class AccountSavePhase {
    INITIALIZING, EDITING, SAVING, CHECKING_RESULT, RETRY_AVAILABLE, SAVED, UNAVAILABLE,
}

enum class AccountProblem {
    CONTEXT_REQUIRED, INITIALIZATION_FAILED, INVALID_RECOVERY, RESULT_UNCONFIRMED,
    CONFLICT, RESULT_MISMATCH,
}

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

sealed interface AccountReadState {
    data object NotRequested : AccountReadState
    data object Loading : AccountReadState
    data class Ready(val account: AccountUiModel) : AccountReadState
    data object Failed : AccountReadState
}

enum class AccountReadPhase { NOT_REQUESTED, LOADING, READY, FAILED }

data class AccountRegistrationUiState(
    val draft: AccountDraft,
    val saveState: AccountSaveState = AccountSaveState.Initializing,
    val inputError: AccountInputError? = null,
    val readState: AccountReadState = AccountReadState.NotRequested,
) {
    val editable: Boolean get() = saveState == AccountSaveState.Editing
    val savedAccountId: String? get() = (saveState as? AccountSaveState.Saved)?.accountId
    val account: AccountUiModel? get() = (readState as? AccountReadState.Ready)?.account
    val problem: AccountProblem? get() = when (val state = saveState) {
        AccountSaveState.InitializationFailed -> AccountProblem.INITIALIZATION_FAILED
        is AccountSaveState.Unconfirmed -> AccountProblem.RESULT_UNCONFIRMED
        is AccountSaveState.Unavailable -> state.reason
        else -> null
    }
    val phase: AccountSavePhase get() = when (saveState) {
        AccountSaveState.Initializing -> AccountSavePhase.INITIALIZING
        AccountSaveState.Editing -> AccountSavePhase.EDITING
        AccountSaveState.InitializationFailed, is AccountSaveState.Unconfirmed -> AccountSavePhase.RETRY_AVAILABLE
        is AccountSaveState.Saving -> AccountSavePhase.SAVING
        is AccountSaveState.CheckingResult -> AccountSavePhase.CHECKING_RESULT
        is AccountSaveState.Saved -> AccountSavePhase.SAVED
        is AccountSaveState.Unavailable -> AccountSavePhase.UNAVAILABLE
    }
    val readPhase: AccountReadPhase get() = when (readState) {
        AccountReadState.NotRequested -> AccountReadPhase.NOT_REQUESTED
        AccountReadState.Loading -> AccountReadPhase.LOADING
        is AccountReadState.Ready -> AccountReadPhase.READY
        AccountReadState.Failed -> AccountReadPhase.FAILED
    }
}

sealed interface AccountRegistrationAction {
    data class NameChanged(val value: String) : AccountRegistrationAction
    data class NatureChanged(val value: AccountNature) : AccountRegistrationAction
    data class AnchorDateChanged(val value: String) : AccountRegistrationAction
    data class UnknownReasonChanged(val value: String) : AccountRegistrationAction
    data object Submit : AccountRegistrationAction
    data object CheckResult : AccountRegistrationAction
    data object RetrySave : AccountRegistrationAction
    data object RetryInitialization : AccountRegistrationAction
    data object ReloadSavedAccount : AccountRegistrationAction
    data object StartNew : AccountRegistrationAction
}
