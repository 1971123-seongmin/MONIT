package com.monit.feature.accounts.importing

data class ImportPreviewState(
    val phase: ImportPreviewPhase = ImportPreviewPhase.EMPTY,
    val ui: ImportPreviewUiState = ImportPreviewUiState(),
    val report: ImportPreviewReport? = null,
    val problem: String? = null,
    val checking: Boolean = false,
)
