package com.monit.feature.home.backup

data class BackupUiState(
    val title: String = "백업과 복원",
    val message: String? = null,
    val lastBackup: String = "검증된 백업이 아직 없어요.",
    val progress: String = "자료 확인 중",
    val progressDetail: String = "취소를 요청해도 실제 처리와 파일 정리가 끝날 때까지 기다려야 해요.",
    val cancelRequested: Boolean = false,
    val sourceDate: String = "",
    val summary: String = "",
    val counts: List<BackupCountUiState> = emptyList(),
    val originals: String = "",
    val anchorSummary: String = "",
    val legacyNotice: String? = null,
    val notificationResetNeeded: Boolean = true,
)

data class BackupCountUiState(val name: String, val current: String, val incoming: String)
