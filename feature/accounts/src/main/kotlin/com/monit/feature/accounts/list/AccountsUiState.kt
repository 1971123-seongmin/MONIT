package com.monit.feature.accounts.list

data class AccountsUiState(val balanceSummary: String, val accounts: List<AccountRowUiState>, val asOf: String = "", val month: String = "", val assetSummary: String = "", val flows: String = "", val exclusions: String = "")
data class AccountRowUiState(
    val id: String,
    val title: String,
    val balance: String,
    val description: String,
)
