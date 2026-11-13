package com.monit.feature.accounts.importing

import com.monit.core.model.*

data class ImportPreviewState(
    val phase: ImportPreviewPhase = ImportPreviewPhase.EMPTY,
    val ui: ImportPreviewUiState = ImportPreviewUiState(),
    val report: ImportPreviewReport? = null,
    val problem: String? = null,
    val checking: Boolean = false,
    val review: ImportReview? = null,
    val result: ImportResult? = null,
    val pending: List<ImportAttempt> = emptyList(),
    val selectedGroup: Set<String> = emptySet(),
    val missingVerified: Boolean = false,
)
