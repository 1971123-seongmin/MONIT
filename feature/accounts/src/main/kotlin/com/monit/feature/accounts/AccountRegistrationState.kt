package com.monit.feature.accounts

import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.common.draft.DraftEditorState

/** 등록 작업 상태. UI에는 표시 데이터만 투영한 uiState를 전달한다. */
data class AccountRegistrationState(
    val draft: AccountDraftUiState,
    val saveState: AccountSaveState = AccountSaveState.Initializing,
    val inputError: AccountInputError? = null,
    val readState: AccountReadState = AccountReadState.NotRequested,
    val finishing: Boolean = false,
    val completionError: Boolean = false,
    val resultAbsent: Boolean = false,
    val validationAttempt: Int = 0,
    val draftPersistence: DraftEditorState? = null,
) {
    val uiState: AccountRegistrationUiState get() = AccountRegistrationUiState(draft, account, inputError)
    val editable: Boolean get() = saveState == AccountSaveState.Editing && (draftPersistence == null || draftPersistence.phase == DraftEditorPhase.EDITING) && draftPersistence?.frozen != true
    val savedAccountId: String? get() = (saveState as? AccountSaveState.Saved)?.accountId
    val account: AccountUiState? get() = (readState as? AccountReadState.Ready)?.account
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
