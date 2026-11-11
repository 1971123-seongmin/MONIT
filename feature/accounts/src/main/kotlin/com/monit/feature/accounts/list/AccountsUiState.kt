package com.monit.feature.accounts.list

data class AccountsUiState(val balanceSummary: String, val accounts: List<AccountRowUiState>)
data class AccountRowUiState(
    val id: String,
    val title: String,
    val balance: String,
    val description: String,
)
