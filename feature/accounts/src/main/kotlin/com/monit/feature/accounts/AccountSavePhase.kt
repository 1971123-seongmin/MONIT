package com.monit.feature.accounts

enum class AccountSavePhase {
    INITIALIZING,
    EDITING,
    SAVING,
    CHECKING_RESULT,
    RETRY_AVAILABLE,
    SAVED,
    UNAVAILABLE,
}
