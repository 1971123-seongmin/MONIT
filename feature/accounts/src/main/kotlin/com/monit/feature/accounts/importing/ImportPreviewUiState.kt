package com.monit.feature.accounts.importing

/** 표시 데이터만 보유한다. 파일 파싱/판정·API·로딩 상태는 별도 책임이다. */
data class ImportPreviewUiState(
    val sourceSummary: String = "선택한 파일 없음",
    val sourceRowCount: String = "확인 전",
    val candidateCount: String = "확인 전",
    val existingCount: String = "확정 0 · 검증 불가",
    val heldCount: String = "확인 전",
    val excludedCount: String = "확인 전",
    val transactionCount: String = "확인 전",
    val completeness: String = "추출 범위 확인 전",
    val reconciliation: String = "전체 금액 대사 확인 전",
    val comparison: String = "원본 대응 정보가 없어 확정 중복은 확인할 수 없어요.",
    val evidence: String = "",
    val totals: List<ImportPreviewTotalUiState> = emptyList(),
    val rows: List<ImportPreviewRowUiState> = emptyList(),
)

data class ImportPreviewTotalUiState(val key: String, val title: String, val value: String)

data class ImportPreviewRowUiState(
    val key: String,
    val title: String,
    val decision: String,
    val detail: String,
    val evidence: String,
    val rawExcerpt: String,
)
