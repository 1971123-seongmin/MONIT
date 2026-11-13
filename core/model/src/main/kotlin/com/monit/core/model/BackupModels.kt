package com.monit.core.model

enum class BackupProblem { PASSWORD, LIMIT, HEADER, UNSUPPORTED, AUTHENTICATION, CRYPTO_UNAVAILABLE, INVALID_PAYLOAD, INVALID_RELATION, STALE_PREVIEW, MAINTENANCE, RECOVERY_COPY, IO, VERIFICATION_INCOMPLETE }

/** 비밀번호·파일 내용·민감 URI와 원인 예외를 오류 메시지에 포함하지 않는다. */
class BackupException(val problem: BackupProblem) : IllegalStateException("Backup failed: $problem")

data class BackupExport(val id: String, val context: LedgerContext, val revision: Long, val createdAt: String, val encrypted: ByteArray)
data class BackupExportReceipt(val id: String, val createdAt: String, val verifiedAt: String, val revision: Long)
data class RestorePreview(val id: String, val currentContext: LedgerContext, val currentRevision: Long, val sourceContext: LedgerContext, val sourceRevision: Long, val sourceCreatedAt: String, val currentCounts: Map<String, Int>, val sourceCounts: Map<String, Int>, val recordTotal: Int, val legacyEvidenceMissing: Boolean, val originalCount: Int, val anchorDates: Map<String, Int> = emptyMap())
data class RestoreResult(val id: String, val context: LedgerContext, val sourceCreatedAt: String, val committedAt: String, val recordTotal: Int, val legacyEvidenceMissing: Boolean, val notificationResetNeeded: Boolean = true)
data class BackupStatus(val lastVerifiedBackupAt: String?, val unacknowledgedRestore: RestoreResult?)
data class RestoredEvidenceSummary(val operationId: String, val sourceContext: LedgerContext, val committed: Boolean, val rejected: Boolean, val intentStatus: String?, val hadSubmissionReference: Boolean, val draftExcluded: Boolean)

data class RecoveryCopyProof(val id: String, val payloadSha256: String, val byteLength: Int)

/** Keystore 보호·파일 닫기/재읽기·인증·정확한 bytes 대조를 완료해야 proof를 반환한다. */
interface RecoveryCopyStore {
    suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof
}

interface BackupRepository {
    suspend fun create(context: LedgerContext, password: CharArray): BackupExport
    suspend fun verifyExport(context: LedgerContext, id: String, readBack: ByteArray, password: CharArray): BackupExportReceipt
    suspend fun inspect(context: LedgerContext, encrypted: ByteArray, password: CharArray): RestorePreview
    suspend fun restore(context: LedgerContext, previewId: String, recovery: RecoveryCopyStore): RestoreResult
    suspend fun status(): BackupStatus
    suspend fun result(id: String): RestoreResult?
    suspend fun acknowledgeRestore(id: String)

    /** 알림 재조정 후 영속 dirty/failed 해제를 확인한 경우에만 완료 표식을 갱신한다. */
    suspend fun notificationsReady(id: String): RestoreResult
    suspend fun evidence(context: LedgerContext): List<RestoredEvidenceSummary>
    fun discardPreview(id: String)
}
