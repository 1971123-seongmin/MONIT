package com.monit.core.data.importing

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.data.backup.*
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** 별도 root 요청은 실행 근거이며 금융 child는 한 writer commit에서만 생성한다. */
@Singleton
internal class RoomImportRepository @Inject constructor(private val database: LedgerDatabase, private val ledger: RoomLedgerRepository, private val clock: Clock, private val contexts: CoroutineContextProvider) : ImportRepository {
    private val plans = ConcurrentHashMap<String, ImportPlan>()
    private val single = Mutex()
    private val dao get() = database.importDao()
    private val records get() = database.ledgerDao()
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))

    override suspend fun review(context: LedgerContext, source: ByteArray, options: ImportReviewOptions): ImportReview = work {
        val copy = source.copyOf()
        try {
            val plan = database.withTransaction {
                ledger.requireContext(context)
                val built = ImportReviewBuilder(today()).build(context, copy, importOptions(optionsJson(options.detached())), ledger.snapshot(context, today()), dao.mappings(context.ledgerId), dao.fences(), records.transactions(context.ledgerId))
                val coverage = ImportHistoryCoverage.valueOf(database.backupDao().status()?.importCoverage ?: "NOT_RECORDED_BY_V2")
                val root = BackupSnapshotAdapter(database, clock).captureInside(context)
                val originals = readBackupOriginals(database, context.ledgerId)
                val candidates = built.review.report.rows.count { it.decision == ImportDecision.CANDIDATE }
                val transactions = built.children.count { it.command is WriteCommand.Transaction }
                val anchors = built.children.count { it.command is WriteCommand.Anchor }
                val extraRecords = 3L + built.review.report.rows.size + 3L * built.children.size + transactions + 2L * candidates + anchors
                val limit = when {
                    originals.size + 1 > 32 || originals.sumOf { it.byteLength } + copy.size > 2 * 1024 * 1024L -> "원문 보관 한도(합계 2 MiB·32개)를 넘어요. 원문을 잘라서 반영하지 않아요."
                    root.getJSONObject("manifest").getLong("recordTotal") + extraRecords > 50_000 -> "원본·출처·명령·결과를 포함한 백업 레코드 한도를 넘어요."
                    else -> null
                }
                built.copy(review = built.review.copy(historyCoverage = coverage, canApply = built.review.canApply && limit == null, limitMessage = limit))
            }
            plans.keys.toList().forEach(::discard)
            plans[plan.review.token] = plan
            plan.review.detached()
        } finally {
            copy.fill(0)
        }
    }

    override suspend fun revise(context: LedgerContext, token: String, options: ImportReviewOptions): ImportReview {
        val plan = plan(context, token)
        return review(context, plan.bytes, options)
    }

    override suspend fun bindBackup(context: LedgerContext, token: String, exportId: String): ImportReview = work {
        val before = plan(context, token)
        database.withTransaction {
            fresh(before)
            backup(before, exportId)
        }
        val review = before.review.copy(token = UUID.randomUUID().toString(), verifiedBackupId = exportId)
        plans[review.token] = before.copy(review = review, bytes = before.bytes.copyOf(), backupId = exportId, sourceChecked = false)
        discard(token)
        review.detached()
    }

    override suspend fun checkSource(context: LedgerContext, token: String, readBack: ByteArray) = work {
        val before = plan(context, token)
        if (!before.bytes.contentEquals(readBack)) {
            discard(token)
            importFailure(ImportFileProblem.STALE_REVIEW)
        }
        database.withTransaction { fresh(before) }
        check(plans.replace(token, before, before.copy(sourceChecked = true)))
    }

    override suspend fun apply(context: LedgerContext, token: String, onStage: (ImportStage) -> Unit): ImportResult = work {
        val plan = plan(context, token)
        if (plan.review.limitMessage != null) importFailure(ImportFileProblem.LIMIT)
        if (!plan.review.canApply || !plan.sourceChecked) importFailure(ImportFileProblem.STALE_REVIEW)
        val batchId = plan.review.batchId
        val request = plan.request()
        onStage(ImportStage.CAPTURING)
        database.withTransaction {
            database.requireDurableWriter()
            fresh(plan)
            backup(plan, plan.backupId ?: importFailure(ImportFileProblem.BACKUP_REQUIRED))
            if (readImportBatch(database, context.ledgerId, batchId) != null) importFailure(ImportFileProblem.DUPLICATE_REQUEST)
            database.requireFreshOperation(batchId)
            for (operationId in listOf(batchId) + plan.children.map { it.operationId }) {
                database.requireFreshOperation(operationId)
                if (records.operation(context.ledgerId, operationId) != null || records.submission(context.ledgerId, operationId) != null || records.submissionReference(context.ledgerId, operationId) != null) importFailure(ImportFileProblem.DUPLICATE_REQUEST)
            }
            val originals = readBackupOriginals(database, context.ledgerId)
            if (originals.size + 1 > 32 || originals.sumOf { it.byteLength } + plan.bytes.size > 2 * 1024 * 1024L) importFailure(ImportFileProblem.LIMIT)
            database.backupDao().original(BackupOriginalEntity(context.ledgerId, plan.originalId, "monit-import-1", "import-envelope.json", plan.bytes.size.toLong(), plan.review.report.input.inputSha256, plan.bytes))
            dao.insertBatch(ImportBatchEntity(context.ledgerId, batchId, context.storageGeneration, request, requestHash(request), plan.originalId, plan.review.report.ledgerRevision, plan.review.report.fingerprint, "REQUESTED", null, clock.instant().toString(), null, false, false))
            plan.review.report.rows.forEachIndexed { position, row ->
                val identity = checkNotNull(plan.identities[row.source.locator])
                val child = plan.children.singleOrNull { row.source.locator in it.locators }
                dao.insertRow(ImportRowEntity(context.ledgerId, batchId, row.source.locator, position, identity.hash, identity.key, row.source.rawSha256, row.decision.name, canonicalImport(JSONArray(row.reasons.map { it.name }.sorted())), child?.operationId, (child?.command as? WriteCommand.Transaction)?.value?.id))
            }
            database.backupDao().fence(OperationFenceEntity(batchId))
            plan.children.forEach { database.backupDao().fence(OperationFenceEntity(it.operationId)) }
            // 요청 캡처 자체도 한도를 넘기면 보존/재시도 불가능한 자료를 남기지 않는다.
            BackupSnapshotAdapter(database, clock).captureInside(context)
            currentCoroutineContext().ensureActive()
        }
        onStage(ImportStage.APPLYING)
        val result = database.withTransaction {
            database.requireDurableWriter()
            fresh(plan)
            backup(plan, checkNotNull(plan.backupId))
            val batch = checkNotNull(readImportBatch(database, context.ledgerId, batchId))
            check(batch.status == "REQUESTED" && batch.requestJson == request && batch.requestSha256 == requestHash(request) && !batch.evidenceOnly)
            val at = clock.instant().toString()
            val revision = Math.addExact(batch.expectedRevision, 1)
            val storedRows = dao.rows(context.ledgerId, batchId).associateBy { it.locator }
            for (child in plan.children) {
                currentCoroutineContext().ensureActive()
                check(records.operation(context.ledgerId, child.operationId) == null)
                val refs = when (val command = child.command) {
                    is WriteCommand.Anchor -> {
                        val store = AnchorStore(database, clock)
                        val before = store.target(context, command.value)
                        check(before.revision == command.value.expectedRevision)
                        store.apply(context, child.operationId, command.value, before)
                    }

                    is WriteCommand.Transaction -> ledger.insertTransactionRow(context, command.value)

                    else -> error("Unsupported import child")
                }
                val raw = captureWrite(context, child.command)
                val kind = if (child.command is WriteCommand.Anchor) "CONFIRM_ANCHOR" else "CREATE_TRANSACTION"
                records.insertOperation(OperationEntity(context.ledgerId, child.operationId, context.storageGeneration, kind, raw, requestHash(raw), at, revision, encodeRefs(refs)))
                dao.insertChild(ImportChildEntity(context.ledgerId, batchId, child.operationId, requestHash(raw), kind, refs.single().id))
                if (child.command is WriteCommand.Transaction) {
                    val target = checkNotNull(records.transaction(context.ledgerId, child.command.value.id))
                    val payload = transactionPayload(target)
                    for (locator in child.locators) {
                        val row = plan.review.report.rows.single { it.source.locator == locator }
                        val stored = checkNotNull(storedRows[locator])
                        val position = stored.position
                        val identity = checkNotNull(plan.identities[locator])
                        val input = plan.review.report.input
                        val mapping = ImportMappingEntity(context.ledgerId, identity.hash, identity.key, row.source.rawSha256, input.inputSha256, input.snapshotSha256, input.mappingSha256, checkNotNull(plan.mappingHashes[locator]), locator, position, plan.originalId, batchId, context.storageGeneration, child.operationId, target.id, target.revision, target.financialRevision, payload, requestHash(payload))
                        dao.insertMapping(mapping)
                        dao.insertFence(ImportSourceFenceEntity(mapping.identity, mapping.keyJson, mapping.ledgerId, mapping.storageGeneration, mapping.rawSha256, mapping.fileSha256, mapping.snapshotSha256, mapping.fileMappingSha256, locator, position, batchId, target.id, mapping.payloadSha256))
                        check(dao.updateRow(stored.copy(decision = "APPLIED")) == 1)
                    }
                }
            }
            validateFinalSet(context)
            check(records.advanceRevision(context.ledgerId, batch.expectedRevision) == 1)
            database.reminderDao().dirty(context.ledgerId)
            val rows = dao.rows(context.ledgerId, batchId)
            val outcome = ImportResult(batchId, context, at, revision, rows.size, rows.count { it.decision == "APPLIED" }, rows.count { it.decision == "EXISTS" }, rows.count { it.decision == "HELD" }, rows.count { it.decision == "EXCLUDED" }, plan.children.count { it.command is WriteCommand.Transaction }, plan.children.count { it.command is WriteCommand.Anchor }, plan.review.report.partial || plan.review.historyCoverage != ImportHistoryCoverage.RECORDED || rows.any { it.decision == "HELD" || it.decision == "EXCLUDED" }, plan.originalId)
            check(dao.updateBatch(batch.copy(status = "COMMITTED", resultJson = importResultJson(outcome), committedAt = at)) == 1)
            BackupSnapshotAdapter(database, clock).captureInside(context)
            currentCoroutineContext().ensureActive()
            outcome
        }
        discard(token)
        result
    }

    override suspend fun attempt(context: LedgerContext, batchId: String): ImportAttempt = work {
        database.withTransaction {
            ledger.requireContext(context)
            readImportBatch(database, context.ledgerId, requireLedgerId(batchId))?.let {
                validateStored(context)
                verifiedAttempt(it)
            } ?: ImportAttempt.Missing
        }
    }
    override suspend fun pending(context: LedgerContext): List<ImportAttempt> = work {
        database.withTransaction {
            ledger.requireContext(context)
            val pending = readImportBatches(database, context.ledgerId).filter { !it.acknowledged && !it.evidenceOnly }
            if (pending.isNotEmpty()) validateStored(context)
            pending.map { verifiedAttempt(it) }
        }
    }
    override suspend fun acknowledge(context: LedgerContext, batchId: String) = work {
        database.withTransaction {
            database.requireDurableWriter()
            ledger.requireContext(context)
            val batch = checkNotNull(readImportBatch(database, context.ledgerId, requireLedgerId(batchId)))
            validateStored(context)
            verifiedAttempt(batch)
            check(dao.updateBatch(batch.copy(acknowledged = true)) == 1)
        }
    }
    override fun discard(token: String) {
        plans.remove(token)?.bytes?.fill(0)
    }

    private suspend fun validateStored(context: LedgerContext) {
        val bytes = BackupSnapshotAdapter(database, clock).captureInside(context).toString().toByteArray(Charsets.UTF_8)
        try {
            BackupValidator(clock).validate(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun verifiedAttempt(batch: ImportBatchEntity): ImportAttempt {
        check(requestHash(batch.requestJson) == batch.requestSha256)
        val request = JSONObject(batch.requestJson)
        check(request.getString("version") == "monit-import-1" && request.getString("batchId") == batch.batchId && canonicalImport(request) == batch.requestJson)
        val context = LedgerContext(batch.ledgerId, batch.storageGeneration)
        check(request.getString("ledgerId") == context.ledgerId && request.getString("storageGeneration") == context.storageGeneration)
        val original = readBackupOriginals(database, batch.ledgerId).single { it.id == batch.originalId }
        check(original.sha256 == request.getString("sourceSha256") && ImportPreviewParser.digest(original.bytes) == original.sha256)
        val result = batch.resultJson?.let(::importResult)
        if (result != null) {
            check(batch.status == "COMMITTED" && result.batchId == batch.batchId && result.context == context && result.revision == Math.addExact(batch.expectedRevision, 1) && result.originalId == original.id)
            val rows = dao.rows(batch.ledgerId, batch.batchId)
            check(result.sourceRows == rows.size && result.appliedRows == rows.count { it.decision == "APPLIED" } && result.existingRows == rows.count { it.decision == "EXISTS" } && result.heldRows == rows.count { it.decision == "HELD" } && result.excludedRows == rows.count { it.decision == "EXCLUDED" })
            val children = dao.children(batch.ledgerId, batch.batchId)
            check(children.size == result.createdTransactions + result.confirmedAnchors)
            if (!batch.evidenceOnly) for (child in children) {
                val operation = checkNotNull(records.operation(batch.ledgerId, child.operationId))
                check(operation.requestSha256 == child.requestSha256 && requestHash(operation.requestJson) == child.requestSha256 && operation.ledgerRevision == result.revision && operation.receipt().entities.single().id == child.entityId)
            }
        } else check(batch.status == "REQUESTED" && batch.committedAt == null)
        return if (batch.evidenceOnly) ImportAttempt.Evidence(batch.batchId, context, result) else result?.let(ImportAttempt::Committed) ?: ImportAttempt.Requested(batch.batchId, context)
    }

    private fun plan(context: LedgerContext, token: String): ImportPlan = (plans[token] ?: importFailure(ImportFileProblem.STALE_REVIEW)).also { if (it.review.report.context != context) importFailure(ImportFileProblem.WRONG_LEDGER) }
    private suspend fun fresh(plan: ImportPlan) {
        if (ledger.requireContext(plan.review.report.context).revision != plan.review.report.ledgerRevision) importFailure(ImportFileProblem.STALE_REVIEW)
    }
    private suspend fun backup(plan: ImportPlan, exportId: String) {
        val status = database.backupDao().status()
        val context = plan.review.report.context
        if (status?.lastBackupExportId != exportId || status.lastBackupLedger != context.ledgerId || status.lastBackupGeneration != context.storageGeneration || status.lastBackupRevision != plan.review.report.ledgerRevision) importFailure(ImportFileProblem.BACKUP_REQUIRED)
    }
    private suspend fun validateFinalSet(context: LedgerContext) {
        val snapshot = ledger.snapshot(context, today())
        snapshot.accounts.forEach { requireBalanceHistory(it.anchor, it.id, snapshot.transactions, today()) }
        val active = snapshot.accounts.filter { it.status == AccountStatus.ACTIVE }
        for (date in (snapshot.transactions.map { it.date } + active.map { it.anchor.date } + today()).distinct()) {
            currentCoroutineContext().ensureActive()
            val balances = active.associate { it.id to accountBalance(it.anchor, it.id, snapshot.transactions, date) }
            sumSignedAmounts(balances.values.filterNotNull())
            sumSignedAmounts(active.filter { it.nature != AccountNature.GENERAL }.mapNotNull { balances[it.id] })
        }
        snapshot.transactions.groupBy { YearMonth.from(it.date) }.forEach { (month, values) ->
            sumSignedAmounts(values.filter { it.kind == TransactionKind.INCOME }.map { it.amount })
            sumSignedAmounts(values.filter { it.kind == TransactionKind.EXPENSE }.map { it.amount })
            assetFlows(values, month)
        }
    }
    private suspend fun <T> work(action: suspend () -> T): T = withContext(contexts.default) {
        if (!single.tryLock()) importFailure(ImportFileProblem.UNAVAILABLE)
        try {
            withTimeout(30_000) { action() }
        } finally {
            single.unlock()
        }
    }
}
