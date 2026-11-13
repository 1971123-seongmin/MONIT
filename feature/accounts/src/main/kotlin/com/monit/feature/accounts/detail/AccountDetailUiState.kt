package com.monit.feature.accounts.detail

data class AccountDetailUiState(
    val title: String = "계좌 상세",
    val balance: String = "잔액 미확인",
    val asOf: String = "",
    val anchorDate: String = "",
    val anchorAmount: String = "미확인",
    val anchorConfirmed: Boolean = false,
    val balanceChange: String = "미확인",
    val inclusion: String = "",
    val month: String = "",
    val deposits: String = "",
    val withdrawals: String = "",
    val net: String = "",
    val history: List<AnchorHistoryUiState> = emptyList(),
    val form: AnchorFormUiState = AnchorFormUiState(),
    val preview: AnchorPreviewUiState = AnchorPreviewUiState(),
    val message: String = "",
)

data class AnchorHistoryUiState(val id: String, val description: String)
data class AnchorFormUiState(val amount: String = "", val reason: String = "")
data class AnchorPreviewUiState(val before: String = "", val after: String = "", val amount: String = "", val reason: String = "", val subtotalBefore: String = "", val subtotalAfter: String = "", val inclusionBefore: Int = 0, val inclusionAfter: Int = 0)
