package com.monit.core.model

import java.math.BigInteger

object ImportLimits {
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    const val MAX_ROWS = 10_000
}

enum class ImportAnchorAction { KEEP, CONFIRM }
enum class ImportStage { READING, REVIEWING, CAPTURING, APPLYING, RESOLVING }

data class ImportAnchorChoice(val accountId: String, val anchorId: String, val expectedRevision: Long, val date: String, val action: ImportAnchorAction, val amount: Long?, val reason: String, val confirmed: Boolean)
data class ImportTransferGroup(val locators: List<String>, val confirmed: Boolean)
data class ImportReviewOptions(
    val targetConfirmed: Boolean = false,
    val accounts: List<ImportAccountMapping> = emptyList(),
    val anchors: List<ImportAnchorChoice> = emptyList(),
    val groups: List<ImportTransferGroup> = emptyList(),
    val confirmedRows: Set<String> = emptySet(),
    val distinctRows: Set<String> = emptySet(),
    val excludedRows: Set<String> = emptySet(),
)

data class ImportCurrencyTotal(val currency: String, val amount: BigInteger, val count: Int)
data class ImportReview(val token: String, val batchId: String, val report: ImportPreviewReport, val options: ImportReviewOptions, val accounts: List<LedgerAccount>, val sourceTotals: List<ImportCurrencyTotal>, val canApply: Boolean, val limitMessage: String? = null, val verifiedBackupId: String? = null, val accountLinks: List<ImportAccountMapping> = emptyList(), val historyCoverage: ImportHistoryCoverage = ImportHistoryCoverage.RECORDED)
data class ImportResult(val batchId: String, val context: LedgerContext, val committedAt: String, val revision: Long, val sourceRows: Int, val appliedRows: Int, val existingRows: Int, val heldRows: Int, val excludedRows: Int, val createdTransactions: Int, val confirmedAnchors: Int, val partial: Boolean, val originalId: String) {
    init {
        require(sourceRows == appliedRows + existingRows + heldRows + excludedRows)
    }
}
sealed interface ImportAttempt {
    data object Missing : ImportAttempt
    data class Requested(val batchId: String, val context: LedgerContext) : ImportAttempt
    data class Committed(val result: ImportResult) : ImportAttempt
    data class Evidence(val batchId: String, val sourceContext: LedgerContext, val result: ImportResult?) : ImportAttempt
}

/** 원본/판정의 권위는 private 검토 token과 영속 요청/결과다. 표시 fingerprint를 승인 token으로 받지 않는다. */
interface ImportRepository {
    suspend fun review(context: LedgerContext, source: ByteArray, options: ImportReviewOptions = ImportReviewOptions()): ImportReview
    suspend fun revise(context: LedgerContext, token: String, options: ImportReviewOptions): ImportReview
    suspend fun bindBackup(context: LedgerContext, token: String, exportId: String): ImportReview
    suspend fun checkSource(context: LedgerContext, token: String, readBack: ByteArray)
    suspend fun apply(context: LedgerContext, token: String, onStage: (ImportStage) -> Unit = {}): ImportResult
    suspend fun attempt(context: LedgerContext, batchId: String): ImportAttempt
    suspend fun pending(context: LedgerContext): List<ImportAttempt>
    suspend fun acknowledge(context: LedgerContext, batchId: String)
    fun discard(token: String)
}
