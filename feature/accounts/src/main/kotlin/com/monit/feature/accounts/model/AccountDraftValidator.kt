package com.monit.feature.accounts.model

import com.monit.core.model.requireLedgerText
import com.monit.feature.accounts.AccountDraftUiState
import com.monit.feature.accounts.AccountInputError
import java.time.LocalDate

/** 원문 입력을 보정하지 않으며 호출자가 서울 오늘을 전달한다. */
internal fun validateAccountDraft(draft: AccountDraftUiState, today: LocalDate): AccountInputError? = when {
    runCatching { requireLedgerText(draft.name) }.isFailure -> AccountInputError.NAME

    runCatching {
        val date = LocalDate.parse(draft.anchorDate)
        require(date.toString() == draft.anchorDate)
        require(date >= LocalDate.of(1900, 1, 1) && date < today)
    }.isFailure -> AccountInputError.ANCHOR_DATE

    runCatching { requireLedgerText(draft.unknownReason) }.isFailure -> AccountInputError.UNKNOWN_REASON

    else -> null
}
