package com.monit.feature.home.backup

enum class BackupPhase { ENTRY, EXPORT_PASSWORD, RESTORE_PASSWORD, WORKING, PREVIEW, EXPORTED, EXPORTED_UNVERIFIED, RESULT, UNKNOWN }

/** 진행 단계와 순수 표시 데이터를 분리한다. 비밀번호·URI·복원 원문은 화면 상태에 담지 않는다. */
data class BackupState(val phase: BackupPhase = BackupPhase.ENTRY, val ui: BackupUiState = BackupUiState())
