package com.monit.feature.accounts.navigation

import kotlinx.serialization.Serializable

@Serializable
data class AccountRegistrationDestination(
    val registrationEntryId: String,
    val origin: AccountRegistrationOrigin,
    val callerEntryId: String? = null,
    val selectionRequestId: String? = null,
    val callerDraftId: String? = null,
    val selectionSlot: String? = null,
) {
    init {
        require(registrationEntryId.isNotBlank())
        when (origin) {
            AccountRegistrationOrigin.ACCOUNTS ->
                require(callerEntryId == null && selectionRequestId == null && callerDraftId == null && selectionSlot == null)

            AccountRegistrationOrigin.TRANSACTION_ENTRY ->
                require(!callerEntryId.isNullOrBlank() && !selectionRequestId.isNullOrBlank())
        }
    }
}
