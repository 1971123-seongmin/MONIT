package com.monit.feature.accounts

import com.monit.core.model.AccountNature

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
