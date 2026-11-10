package com.monit.feature.accounts.model

import com.monit.core.model.CreateAccount
import com.monit.core.model.LedgerContext
import com.monit.core.model.requireLedgerId
import com.monit.feature.accounts.AccountDraft

/** 재시도와 화면 복원에서 ID·내용을 함께 유지하는 불변 제출. */
data class SubmittedAccountState(
    val context: LedgerContext,
    val operationId: String,
    val value: CreateAccount,
) {
    init { requireLedgerId(operationId) }

    internal fun draft() = AccountDraft(value.name, value.nature, value.anchorDate.toString(), value.unknownReason)
}
