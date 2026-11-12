package com.monit.app.entry

data class EntryRecoveryUiState(val rows: List<RecoveryRowUiState> = emptyList())

data class RecoveryRowUiState(val title: String, val detail: String)
