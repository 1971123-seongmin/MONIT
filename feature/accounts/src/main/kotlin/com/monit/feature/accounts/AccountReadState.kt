package com.monit.feature.accounts

sealed interface AccountReadState {
    data object NotRequested : AccountReadState
    data object Loading : AccountReadState
    data class Ready(val account: AccountUiState) : AccountReadState
    data object Failed : AccountReadState
}
