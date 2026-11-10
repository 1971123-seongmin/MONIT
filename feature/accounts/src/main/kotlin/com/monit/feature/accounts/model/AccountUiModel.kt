package com.monit.feature.accounts.model

import com.monit.core.model.AccountNature
import com.monit.core.model.LedgerAccount
import java.time.LocalDate

/** 미확인 잔액 null과 사유를 보존하는 표시 모델. */
data class AccountUiModel(
    val id: String,
    val name: String,
    val nature: AccountNature,
    val anchorDate: LocalDate,
    val balance: Long?,
    val unknownReason: String?,
)

internal fun LedgerAccount.toAccountUiModel(balances: Map<String, Long?>): AccountUiModel =
    AccountUiModel(id, name, nature, anchor.date, balances.getValue(id), anchor.unknownReason)
