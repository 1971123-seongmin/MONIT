package com.monit.feature.accounts

import com.monit.core.model.AccountNature
import java.time.LocalDate

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

/** 잔액 미확인 계좌의 표시 모델. 거래 합계를 잔액 0원으로 바꾸지 않는다. */
data class AccountUiModel(
    val id: String,
    val name: String,
    val nature: AccountNature,
    val anchorDate: LocalDate,
    val balance: Long?,
    val unknownReason: String?,
)

enum class AccountReadPhase { NOT_REQUESTED, LOADING, READY, FAILED }

data class AccountRegistrationUiState(
    val draft: AccountDraft,
    val phase: AccountSavePhase = AccountSavePhase.INITIALIZING,
    val inputError: AccountInputError? = null,
    val problem: AccountProblem? = null,
    val savedAccountId: String? = null,
    val account: AccountUiModel? = null,
    val readPhase: AccountReadPhase = AccountReadPhase.NOT_REQUESTED,
) {
    val editable: Boolean get() = phase == AccountSavePhase.EDITING
}

sealed interface AccountRegistrationAction {
    data class NameChanged(val value: String) : AccountRegistrationAction
    data class NatureChanged(val value: AccountNature) : AccountRegistrationAction
    data class AnchorDateChanged(val value: String) : AccountRegistrationAction
    data class UnknownReasonChanged(val value: String) : AccountRegistrationAction
    data object Submit : AccountRegistrationAction
    data object Retry : AccountRegistrationAction
    data object ReloadSavedAccount : AccountRegistrationAction
    data object StartNew : AccountRegistrationAction
}
