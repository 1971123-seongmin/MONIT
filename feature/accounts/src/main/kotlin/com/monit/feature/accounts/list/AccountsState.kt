package com.monit.feature.accounts.list

sealed interface AccountsState {
    data object Loading : AccountsState
    data class Ready(val ui: AccountsUiState) : AccountsState
    data object Failed : AccountsState
}
