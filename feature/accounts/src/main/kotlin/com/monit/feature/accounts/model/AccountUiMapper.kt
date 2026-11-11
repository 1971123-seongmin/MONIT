package com.monit.feature.accounts.model

import com.monit.core.model.LedgerAccount
import com.monit.feature.accounts.AccountUiState

internal fun LedgerAccount.toAccountUiState(balances: Map<String, Long?>): AccountUiState = AccountUiState(id, name, nature, anchor.date, balances.getValue(id), anchor.unknownReason)
