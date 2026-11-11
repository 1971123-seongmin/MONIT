package com.monit.feature.accounts

import com.monit.core.model.AccountNature
import java.time.LocalDate

/** 화면에 전달할 표시 데이터와 그 자식 데이터만 정의한다. */
data class AccountRegistrationUiState(
    val draft: AccountDraftUiState,
    val account: AccountUiState? = null,
    val inputError: AccountInputError? = null,
)

data class AccountDraftUiState(
    val name: String = "",
    val nature: AccountNature = AccountNature.GENERAL,
    val anchorDate: String,
    val unknownReason: String = "기준 잔액을 아직 확인하지 않았습니다",
)

/** 미확인 잔액 null과 사유를 보존하는 표시 데이터. */
data class AccountUiState(
    val id: String,
    val name: String,
    val nature: AccountNature,
    val anchorDate: LocalDate,
    val balance: Long?,
    val unknownReason: String?,
)
