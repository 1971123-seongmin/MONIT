package com.monit.feature.transactions

data class TransactionsState(
    val ui: TransactionsUiState,
    val load: TransactionsLoadState = TransactionsLoadState.LOADING
)
