package com.monit.feature.home

data class HomeUiState(
    val month: String,
    val income: String,
    val expense: String,
    val paymentSummary: String = "",
    val payments: List<HomePaymentUiState> = emptyList(),
)
data class HomePaymentUiState(val id: String, val name: String, val schedule: String, val status: String)
