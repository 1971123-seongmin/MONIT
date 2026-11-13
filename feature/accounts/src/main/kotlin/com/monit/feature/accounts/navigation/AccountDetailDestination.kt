package com.monit.feature.accounts.navigation

import com.monit.core.model.requireLedgerId
import kotlinx.serialization.Serializable

@Serializable
data class AccountDetailDestination(val entryId: String, val accountId: String) {
    init {
        requireLedgerId(entryId)
        requireLedgerId(accountId)
    }
}
