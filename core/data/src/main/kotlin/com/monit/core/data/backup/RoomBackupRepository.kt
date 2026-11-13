package com.monit.core.data.backup

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.LedgerDatabase
import com.monit.core.model.*
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** 한 번의 암호/파일 워크플로만 처리한다. 사용자 대기/파일/암호 처리는 Room commit 밖이다. */
@Singleton
internal class RoomBackupRepository @Inject constructor(private val database: LedgerDatabase, private val clock: Clock, private val contexts: CoroutineContextProvider) : BackupRepository {
    private val single = Mutex()
    private val adapter get() = BackupSnapshotAdapter(database, clock)
    private val validator get() = BackupValidator(clock)
    private var exported: Pair<BackupExport, String>? = null
    private var inspected: Pair<RestorePreview, ValidatedBackup>? = null
    private fun id() = UUID.randomUUID().toString()

    override suspend fun create(context: LedgerContext, password: CharArray, onStage: (BackupStage) -> Unit): BackupExport = try {
        work {
            onStage(BackupStage.SNAPSHOT)
            val root = adapter.capture(context)
            val plain = BackupJson.utf8(root.toString())
            try {
                val source = validate(plain, onStage)
                currentCoroutineContext().ensureActive()
                onStage(BackupStage.CRYPTO)
                val encrypted = BackupEnvelope.encrypt(plain, password)
                currentCoroutineContext().ensureActive()
                BackupExport(id(), context, source.revision, source.createdAt.toString(), encrypted).also { exported = it to BackupJson.digest(encrypted) }
            } finally {
                plain.fill(0)
            }
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun verifyExport(context: LedgerContext, id: String, readBack: ByteArray, password: CharArray, onStage: (BackupStage) -> Unit): BackupExportReceipt = try {
        work {
            val (original, hash) = checkNotNull(exported).also { require(it.first.id == id && it.first.context == context) }
            onStage(BackupStage.CRYPTO)
            val plain = BackupEnvelope.decrypt(readBack, password)
            try {
                currentCoroutineContext().ensureActive()
                val source = validate(plain, onStage)
                require(BackupJson.digest(readBack) == hash && source.context == context && source.revision == original.revision)
                currentCoroutineContext().ensureActive()
                val receipt = BackupExportReceipt(id, original.createdAt, clock.instant().toString(), source.revision)
                database.withTransaction {
                    database.requireDurableWriter()
                    checkContext(context)
                    val prior = database.backupDao().status() ?: emptyStatus()
                    database.backupDao().status(prior.copy(lastBackupAt = receipt.verifiedAt, lastBackupLedger = context.ledgerId, lastBackupGeneration = context.storageGeneration))
                    currentCoroutineContext().ensureActive()
                }
                exported = null
                receipt
            } finally {
                plain.fill(0)
            }
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun inspect(context: LedgerContext, encrypted: ByteArray, password: CharArray, onStage: (BackupStage) -> Unit): RestorePreview = try {
        work {
            onStage(BackupStage.CRYPTO)
            val plain = BackupEnvelope.decrypt(encrypted, password)
            try {
                currentCoroutineContext().ensureActive()
                val source = validate(plain, onStage)
                require(source.revision < Long.MAX_VALUE)
                currentCoroutineContext().ensureActive()
                onStage(BackupStage.SNAPSHOT)
                val current = adapter.capture(context)
                val currentLedger = current.getJSONObject("sourceLedger")
                val preview = RestorePreview(id(), context, currentLedger.exactLong("revision"), source.context, source.revision, source.createdAt.toString(), counts(current), counts(source.root), source.root.getJSONObject("manifest").getInt("recordTotal"), source.root.getJSONObject("manifest").text("submissionCoverage") == "NOT_RECORDED_BY_V1", source.root.list("originals").size)
                val detailed = preview.copy(anchorDates = source.rows("anchors").groupingBy { it.text("date") }.eachCount())
                inspected = detailed to source
                detailed
            } finally {
                plain.fill(0)
            }
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun restore(context: LedgerContext, previewId: String, recovery: RecoveryCopyStore, onStage: (BackupStage) -> Unit): RestoreResult = work {
        result(previewId)?.let { return@work it }
        val (preview, source) = checkNotNull(inspected).also { require(it.first.id == previewId && it.first.currentContext == context) }
        database.maintenance { lease ->
            onStage(BackupStage.SNAPSHOT)
            val current = database.withTransaction {
                database.requireMaintenanceWriter(lease)
                checkPreview(preview)
                adapter.captureInside(context)
            }
            val plain = BackupJson.utf8(current.toString())
            try {
                validate(plain, onStage)
                val expectedHash = BackupJson.digest(plain)
                onStage(BackupStage.RECOVERY_COPY)
                val proof = try {
                    recovery.protect(context, plain)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    BackupJson.fail(BackupProblem.RECOVERY_COPY)
                }
                currentCoroutineContext().ensureActive()
                requireLedgerId(proof.id)
                if (proof.payloadSha256 != expectedHash || BackupJson.digest(plain) != expectedHash || proof.byteLength != plain.size) BackupJson.fail(BackupProblem.RECOVERY_COPY)
            } finally {
                plain.fill(0)
            }
            val outcome = RestoreResult(previewId, LedgerContext(source.context.ledgerId, id()), source.createdAt.toString(), clock.instant().toString(), source.root.getJSONObject("manifest").getInt("recordTotal"), preview.legacyEvidenceMissing)
            val oldIds = current.list("submissionsEvidence").mapTo(HashSet()) { it.text("operationId") } + current.getJSONArray("operationFences").values().map { it as String }
            val incomingIds = source.root.getJSONArray("operationFences").values().map { it as String }.toSet()
            if (source.root.getJSONObject("manifest").getLong("recordTotal") + oldIds.count { it !in incomingIds } > 50_000) BackupJson.fail(BackupProblem.LIMIT)
            val plan = BackupRestorePlan(source, outcome, oldIds)
            try {
                onStage(BackupStage.REPLACEMENT)
                database.withTransaction {
                    database.requireMaintenanceWriter(lease)
                    checkPreview(preview)
                    plan.apply(database)
                }
            } finally {
                plan.clear()
            }
            inspected = null
            exported = null
            outcome
        }
    }

    override suspend fun status(): BackupStatus = database.withTransaction {
        database.requireAvailable()
        val row = database.backupDao().status()
        val result = row?.restoreResultJson?.let { decodeRestoreResult(it) }
        result?.let { checkContext(it.context) }
        BackupStatus(row?.lastBackupAt, result?.takeUnless { row.restoreAcknowledged })
    }
    override suspend fun result(id: String): RestoreResult? = database.withTransaction {
        requireLedgerId(id)
        database.requireAvailable()
        database.backupDao().status()?.restoreResultJson?.let { decodeRestoreResult(it) }?.takeIf { it.id == id }?.also { checkContext(it.context) }
    }
    override suspend fun acknowledgeRestore(id: String) = database.withTransaction {
        database.requireDurableWriter()
        requireLedgerId(id)
        val status = checkNotNull(database.backupDao().status())
        require(decodeRestoreResult(checkNotNull(status.restoreResultJson)).id == id)
        database.backupDao().status(status.copy(restoreAcknowledged = true))
        currentCoroutineContext().ensureActive()
    }
    override suspend fun notificationsReady(id: String): RestoreResult = database.withTransaction {
        database.requireDurableWriter()
        requireLedgerId(id)
        val status = checkNotNull(database.backupDao().status())
        val result = decodeRestoreResult(checkNotNull(status.restoreResultJson))
        require(result.id == id)
        checkContext(result.context)
        val settings = checkNotNull(database.reminderDao().settings(result.context.ledgerId))
        require(settings.storageGeneration == result.context.storageGeneration && !settings.dirty && !settings.failed)
        val updated = result.copy(notificationResetNeeded = false)
        database.backupDao().status(status.copy(restoreResultJson = encodeRestoreResult(updated)))
        currentCoroutineContext().ensureActive()
        updated
    }
    override suspend fun evidence(context: LedgerContext): List<RestoredEvidenceSummary> = database.withTransaction {
        checkContext(context)
        database.backupDao().evidence(context.ledgerId).map { row ->
            val item = BackupJson.parseText(row.evidenceJson)
            val source = item.getJSONObject("sourceContext")
            RestoredEvidenceSummary(row.operationId, LedgerContext(source.text("ledgerId"), source.text("storageGeneration")), !item.isNull("operations"), listOf("submission_rejections", "payment_rejections", "anchor_rejections").any { !item.isNull(it) }, if (item.isNull("payment_intents")) null else item.getJSONObject("payment_intents").text("status"), !item.isNull("submission_references"), item.text("draftAvailability") == "EXCLUDED_BY_POLICY")
        }
    }
    override fun discardPreview(id: String) {
        if (!single.isLocked && inspected?.first?.id == id) inspected = null
    }

    private suspend fun checkContext(context: LedgerContext) {
        database.requireAvailable()
        val ledger = checkNotNull(database.ledgerDao().ledger(context.ledgerId))
        if (ledger.storageGeneration != context.storageGeneration) throw LedgerConflictException(LedgerConflict.STALE_GENERATION)
    }
    private suspend fun checkPreview(preview: RestorePreview) {
        val ledger = database.ledgerDao().ledger(preview.currentContext.ledgerId)
        if (ledger == null || ledger.storageGeneration != preview.currentContext.storageGeneration || ledger.revision != preview.currentRevision) BackupJson.fail(BackupProblem.STALE_PREVIEW)
    }
    private suspend fun <T> work(action: suspend () -> T): T = withContext(contexts.default) {
        if (!single.tryLock()) BackupJson.fail(BackupProblem.MAINTENANCE)
        try {
            currentCoroutineContext().ensureActive()
            action()
        } finally {
            single.unlock()
        }
    }
    private suspend fun validate(bytes: ByteArray, onStage: (BackupStage) -> Unit): ValidatedBackup = withTimeout(30_000) {
        onStage(BackupStage.VALIDATION)
        validator.validate(bytes)
    }
    private fun counts(root: JSONObject): Map<String, Int> = root.getJSONObject("manifest").getJSONObject("recordCounts").let { value -> value.keys().asSequence().associateWith { value.getInt(it) } }
    private fun emptyStatus() = BackupStatusEntity(lastBackupAt = null, lastBackupLedger = null, lastBackupGeneration = null, restoreResultJson = null, restoreAcknowledged = true)
}
