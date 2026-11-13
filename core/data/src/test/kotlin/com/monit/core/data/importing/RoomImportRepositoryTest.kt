package com.monit.core.data.importing

import android.app.Application
import android.database.sqlite.SQLiteException
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.data.backup.*
import com.monit.core.model.*
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 공개 합성 envelope와 실제 파일 Room만 사용한다. 기기·실제 자료·외부 저장소에 접근하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RoomImportRepositoryTest {
    private val context = ImportPreviewFixture.context
    private fun id(n: Int) = ImportPreviewFixture.id(n)
    private val day = LocalDate.of(2026, 11, 13)
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var writer: RoomImportRepository

    @Before fun open() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "import-writer.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
        writer = RoomImportRepository(db, ledger, clock, contexts)
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("import-writer.db")
    }
    private suspend fun ready() {
        ledger.initialize(context)
        for (n in 10..11) ledger.createAccount(context, id(100 + n), CreateAccount(id(n), id(n + 10), "합성 계좌 $n", AccountNature.GENERAL, LocalDate.of(2026, 1, n - 9), "미확인"))
    }
    private fun row(index: Int = 0, locator: String = "rows/$index", sourceId: String? = "S${index + 1}", change: (JSONObject) -> Unit = {}): JSONObject {
        val snapshot = JSONObject(ImportPreviewFixture.envelope().getString("snapshotJson"))
        val row = JSONObject(snapshot.getJSONArray("rows").getJSONObject(index).toString()).put("locator", locator).put("sourceId", sourceId ?: JSONObject.NULL)
        val raw = JSONObject(row.getString("rawJson")).also(change).toString()
        return row.put("rawJson", raw).put("rawSha256", ImportPreviewParser.digest(raw))
    }
    private fun bytes(rows: List<JSONObject>, mappingChange: (JSONObject) -> Unit = {}): ByteArray = ImportPreviewFixture.edited(
        changeSnapshot = { it.put("rows", JSONArray(rows)) },
        changeMapping = {
            it.put("choices", JSONArray())
            mappingChange(it)
        },
    )
    private suspend fun options(groups: List<ImportTransferGroup> = emptyList(), distinct: Set<String> = emptySet(), excluded: Set<String> = emptySet()): ImportReviewOptions = ImportReviewOptions(
        targetConfirmed = true,
        anchors = ledger.snapshot(context, day).accounts.map { ImportAnchorChoice(it.id, it.anchor.id, it.anchor.revision, it.anchor.date.toString(), ImportAnchorAction.KEEP, it.anchor.amount, "", true) },
        groups = groups,
        confirmedRows = setOf("rows/0"),
        distinctRows = distinct,
        excludedRows = excluded,
    )

    /** 내부 writer 시험의 검증된 영수증 fixture. 실제 암호화/재읽기는 별도 시험에서 함께 검증한다. */
    private suspend fun prepared(bytes: ByteArray, choices: ImportReviewOptions? = null, realBackup: Boolean = false): ImportReview {
        val reviewed = writer.review(context, bytes, (choices ?: options()).copy(confirmedRows = ImportPreviewParser.parse(bytes).rows.map { it.locator }.toSet()))
        val exportId = if (realBackup) {
            val backup = RoomBackupRepository(db, clock, contexts)
            val password = "synthetic-import-backup-password".toCharArray()
            val exported = backup.create(context, password.copyOf())
            backup.verifyExport(context, exported.id, exported.encrypted, password.copyOf()).id
        } else {
            val status = checkNotNull(db.backupDao().status())
            db.backupDao().status(status.copy(lastBackupAt = clock.instant().toString(), lastBackupLedger = context.ledgerId, lastBackupGeneration = context.storageGeneration, lastBackupExportId = id(800), lastBackupRevision = reviewed.report.ledgerRevision))
            id(800)
        }
        val bound = writer.bindBackup(context, reviewed.token, exportId)
        writer.checkSource(context, bound.token, bytes)
        return bound
    }

    /** 원본6=반영3+존재1+보류1+제외1이며 명시 이체2행은 거래1건이다. */
    @Test fun sixRowsReconcileWithoutCountingTransferTwice() = runTest {
        ready()
        val first = bytes(listOf(row()))
        val initial = prepared(first, realBackup = true)
        assertEquals(1, writer.apply(context, initial.token).createdTransactions)
        val transferOut = row(2, "rows/1", "T-out") { it.put("입금계좌", JSONArray()) }
        val transferIn = row(2, "rows/2", "T-in") { it.put("결제수단", JSONArray()) }
        val expense = row(0, "rows/3", "new-expense") { it.put("금액", "900") }
        val foreign = row(0, "rows/5", "foreign") { it.put("금액", "12") }.put("sourceNamespace", "synthetic:foreign")
        val source = bytes(listOf(row(), transferOut, transferIn, expense, row(4), foreign)) { mapping ->
            val field = JSONObject(mapping.getJSONArray("fields").getJSONObject(0).toString()).put("namespace", "synthetic:foreign").put("currency", "USD")
            mapping.getJSONArray("fields").put(field)
        }
        val review = prepared(source, options(listOf(ImportTransferGroup(listOf("rows/1", "rows/2"), true)), excluded = setOf("rows/4")))
        assertEquals(2, review.report.expectedTransactionCount)
        assertEquals(1, review.report.existingCount)
        assertTrue(review.sourceTotals.any { it.currency == "USD" && it.amount.toString() == "12" })
        val result = writer.apply(context, review.token)
        assertEquals(listOf(6, 3, 1, 1, 1, 2), listOf(result.sourceRows, result.appliedRows, result.existingRows, result.heldRows, result.excludedRows, result.createdTransactions))
        assertTrue(result.partial)
        assertEquals(3, ledger.snapshot(context, day).transactions.size)
        assertTrue(readBackupOriginals(db, context.ledgerId).any { it.bytes.contentEquals(source) })
        BackupValidator(clock).validate(BackupSnapshotAdapter(db, clock).capture(context).toString().toByteArray())
    }

    /** 목적: 응답을 사용하지 않고 프로세스를 다시 열어도 확정 결과를 조회하고 재삽입하지 않는다. */
    @Test fun durableResultAndSourceMappingSurviveReopen() = runTest {
        ready()
        val source = bytes(listOf(row()))
        val review = prepared(source)
        writer.apply(context, review.token)
        db.close()
        reopen()
        val result = assertIs<ImportAttempt.Committed>(writer.attempt(context, review.batchId)).result
        assertEquals(1, result.createdTransactions)
        val again = prepared(source)
        assertEquals(1, again.report.existingCount)
        assertEquals(0, writer.apply(context, again.token).createdTransactions)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 같은 출처의 원문 변화나 로컬 메모/삭제는 덮어쓰기·부활이 아닌 보류다. */
    @Test fun changedSourceAndLocallyEditedOrDeletedTargetsStayHeld() = runTest {
        ready()
        val source = bytes(listOf(row()))
        writer.apply(context, prepared(source).token)
        val changed = writer.review(context, bytes(listOf(row { it.put("금액", "6000") })), options())
        assertEquals(setOf(ImportHoldReason.SOURCE_CHANGED), changed.report.rows.single().reasons)
        val before = ledger.snapshot(context, day).transactions.single()
        ledger.updateTransaction(context, id(710), UpdateTransaction(CreateTransaction(before.id, before.kind, before.date, before.amount, before.debitAccountId, before.creditAccountId, "합성 수정"), before.revision))
        assertEquals(setOf(ImportHoldReason.LOCAL_CHANGED), writer.review(context, source, options()).report.rows.single().reasons)
        ledger.deleteTransaction(context, id(711), DeleteTransaction(before.id, 2))
        assertEquals(setOf(ImportHoldReason.LOCAL_CHANGED), writer.review(context, source, options()).report.rows.single().reasons)
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        BackupValidator(clock).validate(BackupSnapshotAdapter(db, clock).capture(context).toString().toByteArray())
    }

    /** 무ID 동일 금액 두 행은 명시 별개 확인 후 두 거래이며 mapping 버전 변경으로 재삽입할 수 없다. */
    @Test fun noIdRowsNeedDistinctApprovalAndMappingChangesCannotBypassHistory() = runTest {
        ready()
        val source = bytes(listOf(row(sourceId = null), row(locator = "rows/1", sourceId = null)))
        val held = writer.review(context, source, options())
        assertTrue(held.report.rows.all { ImportHoldReason.POSSIBLE_DUPLICATE in it.reasons })
        writer.apply(context, prepared(source, options(distinct = setOf("rows/0", "rows/1"))).token)
        assertEquals(2, ledger.snapshot(context, day).transactions.size)
        assertEquals(2, writer.review(context, source, options()).report.existingCount)
        val changed = bytes(listOf(row(sourceId = null), row(locator = "rows/1", sourceId = null))) { it.put("version", "mapping-2") }
        assertTrue(writer.review(context, changed, options(distinct = setOf("rows/0", "rows/1"))).report.rows.all { it.decision == ImportDecision.HELD })
        val reordered = bytes(listOf(row(locator = "rows/1", sourceId = null), row(sourceId = null)))
        assertTrue(writer.review(context, reordered, options()).report.rows.all { ImportHoldReason.POSSIBLE_DUPLICATE in it.reasons })
    }

    /** 보관 계좌의 과거 정상 대응은 EXISTS이며 같은 계좌에 새 출처를 추가하지 않는다. */
    @Test fun archivedAccountKeepsExistingSourceButRejectsNewFinancialRows() = runTest {
        ready()
        val source = bytes(listOf(row()))
        writer.apply(context, prepared(source).token)
        // 아직 별도 보관 UI가 없는 단계의 과거 보관 상태를 합성 DB fixture로 만든다.
        db.openHelper.writableDatabase.execSQL("UPDATE accounts SET status='ARCHIVED',revision=revision+1 WHERE ledgerId=? AND id=?", arrayOf(context.ledgerId, id(10)))
        db.openHelper.writableDatabase.execSQL("UPDATE ledgers SET revision=revision+1 WHERE id=?", arrayOf(context.ledgerId))
        assertEquals(1, writer.review(context, source, options()).report.existingCount)
        val incoming = bytes(listOf(row(sourceId = "new-archived-source") { it.put("금액", "7000") }))
        val held = writer.review(context, incoming, options())
        assertTrue(ImportHoldReason.ACCOUNT_UNAVAILABLE in held.report.rows.single().reasons)
        assertEquals(0, held.report.expectedTransactionCount)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 목적: 두 번째 금융 삽입 실패에서 금융·출처·결과 전부 rollback하고 요청만 복구한다.
     * 조건: 합성 정상2행과 transactions INSERT를 거부하는 실제 SQLite trigger.
     * 절차: 요청 캡처 뒤 trigger 생성→apply 실패→재열기/원장·pending 조회.
     * 기대: 신규 거래0·source mapping0·child0, REQUESTED와 원본은 남으며 독립 입력 pending은 없다.
     */
    @Test fun sqlFailureKeepsOnlyCapturedRequestAndNeverPartialFinance() = runTest {
        ready()
        val source = bytes(listOf(row(), row(1)))
        val review = prepared(source)
        assertFailsWith<SQLiteException> { writer.apply(context, review.token) { stage -> if (stage == ImportStage.APPLYING) db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON transactions WHEN NEW.kind='INCOME' BEGIN SELECT RAISE(ABORT,'synthetic rollback'); END") } }
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        assertTrue(db.importDao().mappings(context.ledgerId).isEmpty())
        assertTrue(db.importDao().children(context.ledgerId, review.batchId).isEmpty())
        db.close()
        reopen()
        assertIs<ImportAttempt.Requested>(writer.attempt(context, review.batchId))
        assertTrue(ledger.pendingRecovery(context).items.isEmpty())
        assertTrue(ledger.submissionRecovery(context).items.none { it is SubmissionRecoveryItem.Pending })
    }

    /** 요청 캡처 뒤 협력 취소는 성공으로 표시하지 않으며 명시 재검토 전 실행하지 않는다. */
    @Test fun cancellationAfterCaptureIsRequestedWithoutFinancialEffects() = runTest {
        ready()
        val review = prepared(bytes(listOf(row())))
        assertFailsWith<CancellationException> { writer.apply(context, review.token) { if (it == ImportStage.APPLYING) throw CancellationException("synthetic cancellation") } }
        assertIs<ImportAttempt.Requested>(writer.attempt(context, review.batchId))
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
    }

    /** 백업 영수증/최종 파일 재읽기/ledger revision 중 하나라도 달라지면 반영하지 않는다. */
    @Test fun staleFileOrLedgerAndMissingBackupAreRejected() = runTest {
        ready()
        val source = bytes(listOf(row()))
        val unbound = writer.review(context, source, options())
        writer.checkSource(context, unbound.token, source)
        assertEquals(ImportFileProblem.BACKUP_REQUIRED, assertFailsWith<ImportFileException> { writer.apply(context, unbound.token) }.problem)
        val prepared = prepared(source)
        assertEquals(ImportFileProblem.STALE_REVIEW, assertFailsWith<ImportFileException> { writer.checkSource(context, prepared.token, source + 32.toByte()) }.problem)
        val current = prepared(source)
        ledger.createTransaction(context, id(750), CreateTransaction(id(751), TransactionKind.INCOME, day, 1, creditAccountId = id(10)))
        assertEquals(ImportFileProblem.STALE_REVIEW, assertFailsWith<ImportFileException> { writer.apply(context, current.token) }.problem)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 계좌마다 고정 C를 유지하며 명시 기준액 확정과 거래를 같은 revision/child 영수증 집합으로 반영한다. */
    @Test fun explicitAnchorAndTransactionsShareOneCommitWithoutGuessingOtherUnknownBasis() = runTest {
        ready()
        val before = ledger.snapshot(context, day)
        val choices = options().copy(anchors = options().anchors.map { if (it.accountId == id(10)) it.copy(action = ImportAnchorAction.CONFIRM, amount = 100, reason = "") else it })
        val review = prepared(bytes(listOf(row())), choices)
        val outcome = writer.apply(context, review.token)
        val after = ledger.snapshot(context, day)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(1, outcome.confirmedAnchors)
        assertEquals(before.accounts.map { it.anchor.date }, after.accounts.map { it.anchor.date })
        assertEquals(100L, after.accounts.single { it.id == id(10) }.anchor.amount)
        assertNull(after.accounts.single { it.id == id(11) }.anchor.amount)
        assertEquals(setOf(after.revision), db.importDao().children(context.ledgerId, outcome.batchId).map { checkNotNull(db.ledgerDao().operation(context.ledgerId, it.operationId)).ledgerRevision }.toSet())
        BackupValidator(clock).validate(BackupSnapshotAdapter(db, clock).capture(context).toString().toByteArray())
    }

    /** 각 기준액이 금액 상한인 계좌의 같은 날 반대 이체도 날짜 마감 집합의 효과로 검증한다. */
    @Test fun opposingSameDayTransfersKeepEachAccountBasisAndFinalBalance() = runTest {
        ready()
        val maximum = 999_999_999_999L
        for (account in ledger.snapshot(context, day).accounts) ledger.confirmAnchor(context, id(500 + account.id.takeLast(2).toInt()), ConfirmAnchor(account.id, account.anchor.id, account.anchor.revision, account.anchor.date, maximum, ""))
        val source = bytes(listOf(row(2, "rows/0", "A-B") { it.put("금액", "1") }, row(2, "rows/1", "B-A") { it.put("금액", "1").put("결제수단", JSONArray(listOf("wallet-b"))).put("입금계좌", JSONArray(listOf("wallet-a"))) }))
        val result = writer.apply(context, prepared(source).token)
        assertEquals(2, result.createdTransactions)
        assertEquals(setOf(maximum), ledger.snapshot(context, day).balances.values.toSet())
    }

    /** 외부 배열/컬렉션 변경은 private 검토 계획의 원본·판정·생성 명령을 바꾸지 못한다. */
    @Test fun publicReviewAndCallerCollectionsCannotMutateCapturedPlan() = runTest {
        ready()
        val source = bytes(listOf(row(), row(1)))
        val unchanged = source.copyOf()
        val approved = mutableSetOf("rows/0", "rows/1")
        val review = writer.review(context, source, options().copy(confirmedRows = approved))
        source.fill(0)
        approved.clear()
        (review.report.rows as MutableList).clear()
        (review.options.confirmedRows as MutableSet).clear()
        val status = checkNotNull(db.backupDao().status())
        db.backupDao().status(status.copy(lastBackupLedger = context.ledgerId, lastBackupGeneration = context.storageGeneration, lastBackupExportId = id(800), lastBackupRevision = review.report.ledgerRevision))
        val bound = writer.bindBackup(context, review.token, id(800))
        writer.checkSource(context, bound.token, unchanged)
        assertEquals(2, writer.apply(context, bound.token).createdTransactions)
    }

    /** 원문 합계 한도 초과는 미리 표시하고 원본/요청/금융을 추가하지 않는다. */
    @Test fun originalCapacityIsCheckedBeforeCapturingAnotherRequest() = runTest {
        ready()
        val existing = ByteArray(2 * 1024 * 1024) { 7 }
        db.backupDao().original(BackupOriginalEntity(context.ledgerId, id(950), "synthetic", "capacity.bin", existing.size.toLong(), BackupJson.digest(existing), existing))
        val review = prepared(bytes(listOf(row())))
        assertFalse(review.canApply)
        assertNotNull(review.limitMessage)
        assertEquals(ImportFileProblem.LIMIT, assertFailsWith<ImportFileException> { writer.apply(context, review.token) }.problem)
        assertTrue(readImportBatches(db, context.ledgerId).isEmpty())
        assertEquals(1, readBackupOriginals(db, context.ledgerId).size)
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
    }

    /** strict v2는 읽되 과거 이관 근거를 NONE으로 만들지 않고 미기록으로 승격한다. */
    @Test fun legacyVersionTwoIsExplicitlyNotRecorded() = runTest {
        ready()
        val root = BackupSnapshotAdapter(db, clock).capture(context)
        root.remove("imports")
        root.put("formatVersion", 2).put("contractVersion", 2)
        root.put("manifest", BackupSnapshotAdapter.manifest(root, sourceMappings = "NONE_PRESENT"))
        val validated = BackupValidator(clock).validate(root.toString().toByteArray())
        assertEquals(3, validated.root.getInt("formatVersion"))
        assertEquals("NOT_RECORDED_BY_V2", validated.root.getJSONObject("imports").text("coverage"))
        assertTrue(validated.root.getJSONObject("imports").list("import_source_mappings").isEmpty())
    }

    /** 해시들을 일관되게 재계산해도 원문 금액과 다른 생성 명령·거래를 만든 파일은 거부한다. */
    @Test fun consistentHashRewriteCannotChangeTheSourceDerivedTransaction() = runTest {
        ready()
        writer.apply(context, prepared(bytes(listOf(row()))).token)
        val before = ledger.snapshot(context, day)
        val root = BackupSnapshotAdapter(db, clock).capture(context)
        val imports = root.getJSONObject("imports")
        val batch = imports.list("import_batches").single()
        val request = JSONObject(batch.text("requestJson"))
        val child = request.list("children").single()
        val command = assertIs<WriteCommand.Transaction>(decodeWrite(context, child.text("requestJson")))
        val raw = captureWrite(context, WriteCommand.Transaction(command.value.copy(amount = command.value.amount + 1)))
        val hash = requestHash(raw)
        child.put("requestJson", raw)
        batch.put("requestJson", canonicalImport(request)).put("requestSha256", requestHash(canonicalImport(request)))
        val link = imports.list("import_child_commands").single()
        link.put("requestSha256", hash)
        val operation = root.list("submissionsEvidence").single { it.text("operationId") == child.text("operationId") }.getJSONObject("operations")
        operation.put("requestJson", raw).put("requestSha256", hash)
        root.getJSONObject("collections").list("transactions").single().put("amount", (command.value.amount + 1).toString())
        val mapping = imports.list("import_source_mappings").single()
        val payload = canonicalImport(JSONObject(mapping.text("payloadJson")).put("amount", (command.value.amount + 1).toString()))
        mapping.put("payloadJson", payload).put("payloadSha256", requestHash(payload))
        imports.list("import_source_fences").single().put("payloadSha256", requestHash(payload))
        assertFailsWith<BackupException> { BackupValidator(clock).validate(root.toString().toByteArray()) }
        assertEquals(before, ledger.snapshot(context, day))
    }

    /** 같은 출처 fence의 provenance가 다르면 복원 합집합에서 임의 선택하지 않는다. */
    @Test fun conflictingSourceFenceUnionIsRejected() = runTest {
        ready()
        writer.apply(context, prepared(bytes(listOf(row()))).token)
        val fences = BackupSnapshotAdapter(db, clock).capture(context).getJSONObject("imports").list("import_source_fences")
        val conflict = JSONObject(fences.single().toString()).put("storageGeneration", id(980))
        assertFailsWith<IllegalArgumentException> { BackupImportV3.unionFences(fences, listOf(conflict)) }
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 새 세대의 요청은 evidence-only이며 삭제 이력 왕복·이전 백업 복원 뒤에도 출처가 자동 부활하지 않는다. */
    @Test fun restoreRoundTripKeepsMappingsAndOlderRestoreKeepsHistoricalFence() = runTest {
        ready()
        val backup = RoomBackupRepository(db, clock, contexts)
        val password = "synthetic-import-restore-password".toCharArray()
        val before = backup.create(context, password.copyOf())
        val source = bytes(listOf(row()))
        val review = prepared(source)
        writer.apply(context, review.token)
        val after = backup.create(context, password.copyOf())
        val recovery = object : RecoveryCopyStore {
            override suspend fun protect(context: LedgerContext, plaintext: ByteArray) = RecoveryCopyProof(id(999), BackupJson.digest(plaintext), plaintext.size)
        }
        val restored = backup.restore(context, backup.inspect(context, after.encrypted, password.copyOf()).id, recovery)
        assertIs<ImportAttempt.Evidence>(writer.attempt(restored.context, review.batchId))
        assertTrue(writer.pending(restored.context).isEmpty())
        val untargeted = bytes(listOf(row())) { it.put("target", JSONObject.NULL) }
        val inherited = writer.review(restored.context, untargeted, ImportReviewOptions(targetConfirmed = true))
        assertEquals(1, inherited.report.existingCount)
        val imported = ledger.snapshot(restored.context, day).transactions.single()
        ledger.deleteTransaction(restored.context, id(981), DeleteTransaction(imported.id, imported.revision))
        val deletedFile = backup.create(restored.context, password.copyOf())
        val deleted = backup.restore(restored.context, backup.inspect(restored.context, deletedFile.encrypted, password.copyOf()).id, recovery)
        assertTrue(ledger.snapshot(deleted.context, day).transactions.isEmpty())
        assertEquals(setOf(ImportHoldReason.LOCAL_CHANGED), writer.review(deleted.context, untargeted, ImportReviewOptions(targetConfirmed = true)).report.rows.single().reasons)
        assertIs<ImportAttempt.Evidence>(writer.attempt(deleted.context, review.batchId))
        val old = backup.restore(deleted.context, backup.inspect(deleted.context, before.encrypted, password.copyOf()).id, recovery)
        assertTrue(ledger.snapshot(old.context, day).transactions.isEmpty())
        assertEquals(setOf(ImportHoldReason.HISTORICAL_SOURCE), writer.review(old.context, untargeted, ImportReviewOptions(targetConfirmed = true)).report.rows.single().reasons)
        BackupValidator(clock).validate(BackupSnapshotAdapter(db, clock).capture(old.context).toString().toByteArray())
    }
}
