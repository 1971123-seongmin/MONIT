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
    internal val workProbe = BackupWorkProbe()
    private val adapter get() = BackupSnapshotAdapter(database, clock)
    private val validator get() = BackupValidator(clock, workProbe)
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
                val encrypted = BackupEnvelope.encrypt(plain, password, workProbe)
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
            val plain = BackupEnvelope.decrypt(readBack, password, workProbe)
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
                    database.backupDao().status(prior.copy(lastBackupAt = receipt.verifiedAt, lastBackupLedger = context.ledgerId, lastBackupGeneration = context.storageGeneration, lastBackupExportId = id, lastBackupRevision = source.revision))
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
            val plain = BackupEnvelope.decrypt(encrypted, password, workProbe)
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
            val fences = protectCurrent(context, preview, source, recovery, onStage, lease)
            val outcome = RestoreResult(previewId, LedgerContext(source.context.ledgerId, id()), source.createdAt.toString(), clock.instant().toString(), source.root.getJSONObject("manifest").getInt("recordTotal"), preview.legacyEvidenceMissing)
            checkMergedLimits(source, fences)
            val plan = BackupRestorePlan(source, outcome, fences.operations, fences.importFences)
            try {
                onStage(BackupStage.REPLACEMENT)
                workProbe.start(BackupProbeEvent.REPLACEMENT)
                try {
                    database.withTransaction {
                        workProbe.start(BackupProbeEvent.REPLACEMENT_BODY)
                        try {
                            database.requireMaintenanceWriter(lease)
                            checkPreview(preview)
                            plan.apply(database, workProbe)
                            workProbe.checkpoint(BackupCheckpoint.BEFORE_COMMIT)
                        } finally {
                            workProbe.end(BackupProbeEvent.REPLACEMENT_BODY)
                        }
                    }
                } finally {
                    workProbe.end(BackupProbeEvent.REPLACEMENT)
                }
            } finally {
                plan.clear()
            }
            inspected = null
            exported = null
            workProbe.checkpoint(BackupCheckpoint.AFTER_COMMIT)
            outcome
        }
    }

    /** A의 전체 snapshot/소거된 plain이 교체 continuation에 남지 않도록 필요한 fence만 반환한다. */
    private suspend fun protectCurrent(
        context: LedgerContext,
        preview: RestorePreview,
        source: ValidatedBackup,
        recovery: RecoveryCopyStore,
        onStage: (BackupStage) -> Unit,
        lease: String,
    ): RestoreFences {
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
            workProbe.start(BackupProbeEvent.RECOVERY_COPY)
            val proof = try {
                recovery.protect(context, plain)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BackupJson.fail(BackupProblem.RECOVERY_COPY)
            } finally {
                workProbe.end(BackupProbeEvent.RECOVERY_COPY)
            }
            currentCoroutineContext().ensureActive()
            requireLedgerId(proof.id)
            if (proof.payloadSha256 != expectedHash || BackupJson.digest(plain) != expectedHash || proof.byteLength != plain.size) BackupJson.fail(BackupProblem.RECOVERY_COPY)
        } finally {
            plain.fill(0)
        }
        val operations = current.list("submissionsEvidence").mapTo(HashSet()) { it.text("operationId") } + current.getJSONArray("operationFences").values().map { it as String }
        val importFences = BackupImportV3.unionFences(current.getJSONObject("imports").list("import_source_fences"), source.root.getJSONObject("imports").list("import_source_fences"))
        return RestoreFences(operations, importFences)
    }

    /** 정확한 기존 전체 UTF-8/record 검사를 끝낸 임시 union을 transaction에 넘기지 않는다. */
    private fun checkMergedLimits(source: ValidatedBackup, fences: RestoreFences) {
        val incomingIds = source.root.getJSONArray("operationFences").values().map { it as String }.toSet()
        val union = JSONObject(source.root.toString())
        union.put("operationFences", org.json.JSONArray((fences.operations + incomingIds).sorted()))
        union.getJSONObject("imports").put("import_source_fences", org.json.JSONArray(fences.importFences))
        union.put("manifest", BackupSnapshotAdapter.manifest(union, source.root.getJSONObject("manifest").text("submissionCoverage"), source.root.getJSONObject("imports").text("coverage")))
        if (union.getJSONObject("manifest").getLong("recordTotal") > 50_000 || BackupJson.utf8(union.toString()).size > BackupEnvelope.MAX_PAYLOAD) BackupJson.fail(BackupProblem.LIMIT)
    }

    private data class RestoreFences(val operations: Set<String>, val importFences: List<JSONObject>)

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
