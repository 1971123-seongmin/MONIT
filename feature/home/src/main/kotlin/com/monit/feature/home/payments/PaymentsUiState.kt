package com.monit.feature.home.payments

/** 표시 값과 사용자 입력 원문만 소유한다. 명령·조회·진행 상태는 별도 타입이다. */
data class PaymentsUiState(
    val month: String = "",
    val monthLabel: String = "",
    val summary: String = "",
    val items: List<MonthlyPaymentUiState> = emptyList(),
    val rules: List<PaymentRuleUiState> = emptyList(),
    val accounts: List<PaymentAccountUiState> = emptyList(),
    val transactions: List<PaymentTransactionUiState> = emptyList(),
    val requests: List<PaymentRequestUiState> = emptyList(),
    val selected: MonthlyPaymentUiState? = null,
    val form: PaymentFormUiState = PaymentFormUiState(),
    val review: PaymentReviewUiState = PaymentReviewUiState(),
    val message: String? = null,
)
data class MonthlyPaymentUiState(
    val id: String,
    val name: String,
    val schedule: String,
    val status: String,
    val completed: Boolean,
    val skipped: Boolean,
    val active: Boolean,
    val hasTransaction: Boolean,
    val needsReconfirmation: Boolean,
    val confirmed: String?,
)
data class PaymentRuleUiState(val id: String, val name: String, val summary: String)
data class PaymentAccountUiState(val id: String, val name: String, val nature: String)
data class PaymentTransactionUiState(val id: String, val headline: String, val detail: String)
data class PaymentRequestUiState(val id: String, val title: String)
data class PaymentFormUiState(val title: String = "", val values: Map<String, String> = emptyMap())
data class PaymentReviewUiState(
    val title: String = "",
    val schedule: String = "",
    val transaction: String = "",
    val detail: String = "",
    val explanation: String = "",
    val action: String = "",
)
