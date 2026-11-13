package com.monit.feature.home.backup

data class BackupUiState(
    val title: String = "백업과 복원",
    val message: String? = null,
    val lastBackup: String = "검증된 백업이 아직 없어요.",
    val progress: String = "자료 확인 중",
    val sourceDate: String = "",
    val summary: String = "",
    val counts: List<BackupCountUiState> = emptyList(),
    val originals: String = "",
    val anchorSummary: String = "",
    val legacyNotice: String? = null,
    val notificationResetNeeded: Boolean = true,
)

data class BackupCountUiState(val name: String, val current: String, val incoming: String)
