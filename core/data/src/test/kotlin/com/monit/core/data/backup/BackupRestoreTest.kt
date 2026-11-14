package com.monit.core.data.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.model.*
import java.io.IOException
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

/** 실제 파일 Room과 논리 복원 경계 시험. RecoveryCopyStore 대역은 실제 Keystore 검증을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupRestoreTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 11, 13)
    private fun password() = "synthetic-backup-password-2026".toCharArray()
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var backup: RoomBackupRepository
    private val recovery = MemoryRecovery()

    @Before fun open() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "backup-restore.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
        backup = RoomBackupRepository(db, clock, contexts)
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("backup-restore.db")
    }
    private suspend fun ready() {
        ledger.initialize(context)
        ledger.createAccount(context, id(110), CreateAccount(id(10), id(11), "합성 계좌", AccountNature.GENERAL, day.minusDays(3), "미확인"))
        ledger.confirmAnchor(context, id(200), ConfirmAnchor(id(10), id(11), 1, day.minusDays(3), 1000, ""))
        ledger.createTransaction(context, id(130), transaction(30, 300))
    }
    private fun transaction(n: Int, amount: Long) = CreateTransaction(id(n), TransactionKind.INCOME, day.minusDays(1), amount, creditAccountId = id(10), memo = "합성 수입")
    private suspend fun exported() = backup.create(context, password())
    private suspend fun inspect(value: BackupExport) = backup.inspect(context, value.encrypted, password())
    private suspend fun root(context: LedgerContext = this.context) = BackupSnapshotAdapter(db, clock).capture(context)

    /** 목적: 전체대체/새세대와 evidence-only 보존을 함께 검증한다.
     * 조건: 백업 뒤 새 거래가 추가되어 현재/백업 건수가 다르다.
     * 절차: 미리보기→명시 restore→새세대 조회/재백업→이전 요청의 재시도/UUID 재사용을 시도한다.
     * 기대: 백업 금융만 활성, 현재 사본 보존, 이전 실행 대기열0·원문/참조존재 보존·재실행0이다.
     */
    @Test fun restorePreservesOriginalEvidenceWithoutMakingItExecutable() = runTest {
        ready()
        val original = root()
        val baseline = ledger.snapshot(context, day)
        val file = exported()
        ledger.createTransaction(context, id(131), transaction(31, 700))
        val preview = inspect(file)
        assertEquals(2, preview.currentCounts["transactions"])
        assertEquals(1, preview.sourceCounts["transactions"])
        val outcome = backup.restore(context, preview.id, recovery)
        assertNotEquals(context.storageGeneration, outcome.context.storageGeneration)
        val restored = ledger.snapshot(outcome.context, day)
        assertEquals(baseline.accounts, restored.accounts)
        assertEquals(baseline.transactions, restored.transactions)
        assertEquals(baseline.balances, restored.balances)
        assertEquals(1, recovery.calls)
        assertEquals(2, BackupJson.parse(checkNotNull(recovery.payload)).getJSONObject("collections").list("transactions").size)
        assertTrue(ledger.pendingRecovery(outcome.context).items.isEmpty())
        assertTrue(ledger.submissionRecovery(outcome.context).items.isEmpty())
        assertTrue(RoomPaymentRepository(db, ledger, clock, contexts).prepared(outcome.context).isEmpty())
        assertEquals(3, backup.evidence(outcome.context).size)
        assertTrue(backup.evidence(outcome.context).all { it.sourceContext == context && it.hadSubmissionReference })
        assertFailsWith<LedgerConflictException> { ledger.snapshot(context, day) }
        assertFails { ledger.retryPending(outcome.context, id(130)) }
        assertFailsWith<LedgerConflictException> { ledger.createTransaction(outcome.context, id(130), transaction(99, 900)) }
        assertFailsWith<LedgerConflictException> { ledger.createTransaction(outcome.context, id(131), transaction(99, 900)) }
        val reexport = backup.create(outcome.context, password())
        val decoded = BackupValidator(clock).validate(BackupEnvelope.decrypt(reexport.encrypted, password()))
        assertEquals(original.list("submissionsEvidence").map { it.toString() }, decoded.root.list("submissionsEvidence").map { it.toString() })
        assertEquals(outcome, backup.status().unacknowledgedRestore)
    }

    /** 복구 사본 실패/가짜 digest는 financial 대체 전에 막으며 현재 generation/revision·이력을 그대로 유지한다. */
    @Test fun recoveryCopyFailureAndMismatchedProofLeaveCurrentDataUnchanged() = runTest {
        ready()
        val preview = inspect(exported())
        val before = root().toString()
        recovery.fail = true
        assertEquals(BackupProblem.RECOVERY_COPY, assertFailsWith<BackupException> { backup.restore(context, preview.id, recovery) }.problem)
        assertEquals(before, root().toString())
        assertNull(backup.result(preview.id))
        recovery.fail = false
        recovery.wrongProof = true
        assertEquals(BackupProblem.RECOVERY_COPY, assertFailsWith<BackupException> { backup.restore(context, preview.id, recovery) }.problem)
        assertEquals(before, root().toString())
        ledger.createTransaction(context, id(131), transaction(31, 1))
    }

    /** 목적: 현재 A 증빙이 손상되면 사본 보호와 DB 교체를 시작하지 않는다.
     * 조건: 정상 B를 inspect한 뒤 ledger revision을 바꾸지 않고 A operation kind만 손상한다.
     * 절차: restore의 현재 캡처 재검증→실패→DB·상태와 stage/copy 호출을 대조한다.
     * 기대: INVALID_RELATION, copy0·REPLACEMENT 미진입, 현재 DB와 완료표식 불변이다.
     */
    @Test fun invalidCurrentEvidenceNeverBeginsRecoveryOrReplacement() = runTest {
        ready()
        val preview = inspect(exported())
        db.openHelper.writableDatabase.execSQL("UPDATE operations SET kind='UPDATE_TRANSACTION' WHERE operationId=?", arrayOf(id(130)))
        val before = root().toString()
        val stages = mutableListOf<BackupStage>()
        assertEquals(BackupProblem.INVALID_RELATION, assertFailsWith<BackupException> { backup.restore(context, preview.id, recovery, stages::add) }.problem)
        assertEquals(0, recovery.calls)
        assertFalse(BackupStage.REPLACEMENT in stages)
        assertEquals(before, root().toString())
        assertNull(backup.status().unacknowledgedRestore)
    }

    /** 목적: 대체 중 SQL 실패가 fence·삭제·새세대·완료표식을 부분 반영하지 못하게 한다.
     * 조건: accounts INSERT를 실패시키는 실제 SQLite trigger다.
     * 절차: 사본 확보 후 restore 실패→원래 snapshot/원문/결과/fence를 읽고 trigger를 제거한다.
     * 기대: 모든 이전 자료 동일·결과 없음·새 fence0, 복구 사본은 보존된다.
     */
    @Test fun replacementFailureRollsBackEveryFinancialRowFenceAndCompletionMarker() = runTest {
        ready()
        val preview = inspect(exported())
        val before = root().toString()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFails { backup.restore(context, preview.id, recovery) }
        assertEquals(before, root().toString())
        assertNull(backup.result(preview.id))
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM operation_fences").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertEquals(1, recovery.calls)
        assertNotNull(recovery.payload)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_restore")
    }

    /** 사용자 검토 뒤 변경이 생기면 사본/대체를 시작하지 않고 새 미리보기를 요구한다. */
    @Test fun stalePreviewCannotOverwriteNewerData() = runTest {
        ready()
        val preview = inspect(exported())
        ledger.createTransaction(context, id(131), transaction(31, 700))
        val before = ledger.snapshot(context, day)
        assertEquals(BackupProblem.STALE_PREVIEW, assertFailsWith<BackupException> { backup.restore(context, preview.id, recovery) }.problem)
        assertEquals(0, recovery.calls)
        assertEquals(before, ledger.snapshot(context, day))
    }

    /** 목적: 사본 I/O 대기 중에도 모든 금융 writer·초안·Receiver 저장이 같은 경계를 지킨다.
     * 조건: RecoveryCopyStore의 중단 가능한 gate다.
     * 절차: restore가 gate에 진입→각 쓰기/알림 prepare 시도→gate해제 후 10초 안 종료.
     * 기대: 모든 경쟁 요청은 MAINTENANCE, 원래 요청만 새세대로 한 번 확정한다.
     */
    @Test fun maintenanceBlocksWritersDraftsAndReceiverWork() = runTest {
        ready()
        val preview = inspect(exported())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        recovery.before = {
            entered.complete(Unit)
            release.await()
        }
        val job = async(Dispatchers.Default) { backup.restore(context, preview.id, recovery) }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            assertEquals(BackupProblem.MAINTENANCE, assertFailsWith<BackupException> { ledger.createTransaction(context, id(131), transaction(31, 1)) }.problem)
            assertFailsWith<BackupException> { RoomDraftRepository(db, ledger, contexts).saveDraft(context, id(400), 0, DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS), DraftContent.Account("원문", AccountNature.GENERAL, "입력중", "미확인")) }
            assertFailsWith<BackupException> { RoomReminderRepository(db, clock, contexts).prepare(ReminderAccess(true, true, true)) }
            assertFailsWith<BackupException> { RoomPaymentRepository(db, ledger, clock, contexts).submit(context, id(140), PaymentCommand.RegisterRule(id(40), PaymentTerms("합성 보험", PaymentPurpose.INSURANCE, 1, 13, id(10)), "2026-11")) }
        } finally {
            release.complete(Unit)
        }
        val result = withContext(Dispatchers.Default) { withTimeout(10_000) { job.await() } }
        assertEquals(1, ledger.snapshot(result.context, day).transactions.size)
    }

    /** 목적: 사본 단계 취소가 이전 자료를 그대로 남기고 유지보수 잠금을 해제한다.
     * 조건: 복구 사본의 suspend gate에서 요청을 취소한다.
     * 절차: gate진입 확인→cancelAndJoin→원래 context 읽기/새 쓰기.
     * 기대: 완료표식 없음·이전 snapshot 동일·새 writer 정상진행이다.
     */
    @Test fun cancellationBeforeCommitPreservesOriginalAndReleasesMaintenance() = runTest {
        ready()
        val preview = inspect(exported())
        val before = ledger.snapshot(context, day)
        val entered = CompletableDeferred<Unit>()
        recovery.before = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.Default) { backup.restore(context, preview.id, recovery) }
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                entered.await()
                job.cancelAndJoin()
            }
        }
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(backup.result(preview.id))
        ledger.createTransaction(context, id(131), transaction(31, 1))
    }

    /** 목적: commit 후 caller 응답 유실은 새 전체 상태의 영속 결과로 회복하고 재대체하지 않는다.
     * 조건: 실제 restore 결과를 받기 직후 호출자가 IOException으로 전달을 잃는다.
     * 절차: 예외 확인→DB와 Repository 재열기→원래 previewId로 조회/재시도/명시 확인.
     * 기대: generation/자료 동일·복구 사본 호출1·동일 결과·확인 후 notice만 종료.
     */
    @Test fun lostReplyRecoversPersistedResultAfterReopenWithoutSecondReplacement() = runTest {
        ready()
        val preview = inspect(exported())
        assertFailsWith<IOException> {
            backup.restore(context, preview.id, recovery)
            throw IOException("synthetic lost reply")
        }
        val outcome = checkNotNull(backup.result(preview.id))
        db.close()
        reopen()
        assertEquals(outcome, backup.status().unacknowledgedRestore)
        assertEquals(outcome, backup.restore(context, preview.id, recovery))
        assertEquals(1, recovery.calls)
        assertEquals(1, ledger.snapshot(outcome.context, day).transactions.size)
        backup.acknowledgeRestore(preview.id)
        assertNull(backup.status().unacknowledgedRestore)
        assertEquals(outcome, backup.result(preview.id))
    }

    /** 저장 완료 시각은 파일 생성만으로 갱신하지 않고 정확한 외부 재읽기·인증·논리 검증 뒤에만 남긴다. */
    @Test fun exportSuccessRequiresExactAuthenticatedReadBack() = runTest {
        ready()
        val chars = password()
        val file = backup.create(context, chars)
        assertTrue(chars.all { it == '\u0000' })
        assertNull(backup.status().lastVerifiedBackupAt)
        val changed = file.encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<BackupException> { backup.verifyExport(context, file.id, changed, password()) }
        assertNull(backup.status().lastVerifiedBackupAt)
        val success = backup.verifyExport(context, file.id, file.encrypted.copyOf(), password())
        assertEquals(success.verifiedAt, backup.status().lastVerifiedBackupAt)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 모든 요청 버전·참조 부재·납입 intent 세 상태·초안 연결을 실제 복원/재백업으로 보존한다.
     * 조건: 기준액/거래 변경과 납입 재확인·거절, SQL 실패로 남은 READY, 명시 RELEASED다.
     * 절차: 암호 백업→복원→새세대 조회/재백업→이전 UUID retry/release를 시도한다.
     * 기대: 원래 sourceContext/raw/hash/참조존재 동일, 금융/선호 동일, pending/prepared/초안0, 재실행 없음.
     */
    @Test fun completeEvidenceAndPreferencesRoundTripWithoutExecutableRequests() = runTest {
        ready()
        ledger.createAccount(context, id(120), CreateAccount(id(20), id(21), "합성 저축", AccountNature.SAVINGS, day.minusDays(3), "미확인"))
        val payments = RoomPaymentRepository(db, ledger, clock, contexts)
        val terms = PaymentTerms("합성 납입", PaymentPurpose.SAVINGS, 200, 13, id(10), id(20))
        payments.submit(context, id(140), PaymentCommand.RegisterRule(id(40), terms, "2026-11"))
        val item = payments.month(context, "2026-11").items.single().reference
        val tx = CreateTransaction(id(31), TransactionKind.TRANSFER, day, 200, id(10), id(20))
        payments.submit(context, id(150), PaymentCommand.Confirm(item, PaymentTransaction(tx), true, ""))
        ledger.updateTransaction(context, id(151), UpdateTransaction(tx.copy(amount = 250), 1))
        assertFailsWith<TransactionRejectedException> { ledger.deleteTransaction(context, id(152), DeleteTransaction(id(31), 1)) }
        assertFailsWith<AnchorRejectedException> { ledger.confirmAnchor(context, id(201), ConfirmAnchor(id(10), id(11), 1, day.minusDays(3), 2000, "")) }
        assertFailsWith<PaymentRejectedException> { payments.submit(context, id(153), PaymentCommand.ChangeItem(item, PaymentAction.SKIP)) }
        val released = PaymentCommand.RegisterRule(id(50), terms.copy(name = "해제 합성 규칙"), "2026-11")
        payments.release(context, id(160), released)
        val ready = released.copy(id = id(51))
        val (scope, content) = paymentDraftForCommand(ready)
        val draft = RoomDraftRepository(db, ledger, contexts).saveDraft(context, id(400), 0, scope, content)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_ready BEFORE INSERT ON payment_rules BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFails { payments.submit(context, id(161), ready, DraftSubmission(draft.reference, scope)) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_ready")
        db.ledgerDao().deleteSubmissionReference(context.ledgerId, id(110))
        RoomReminderRepository(db, clock, contexts).setEnabled(context, 0, true)
        RoomReminderRepository(db, clock, contexts).setRuleEnabled(context, id(40), 0, false)
        val original = root()
        val witness = original.list("submissionsEvidence").associate { it.text("operationId") to it.toString() }
        assertEquals(setOf(1, 2, 3, 4), original.list("submissionsEvidence").flatMap { record -> BackupLayout.evidence.mapNotNull { table -> if (record.isNull(table) || !record.getJSONObject(table).has("requestJson")) null else JSONObject(record.getJSONObject(table).text("requestJson")).getInt("version") } }.toSet())
        val before = ledger.snapshot(context, day)
        val preview = inspect(exported())
        val outcome = backup.restore(context, preview.id, recovery)
        val snapshot = ledger.snapshot(outcome.context, day)
        assertEquals(before.transactions, snapshot.transactions)
        assertEquals(before.balances, snapshot.balances)
        assertTrue(RoomDraftRepository(db, ledger, contexts).drafts(outcome.context).isEmpty())
        assertTrue(payments.prepared(outcome.context).isEmpty())
        assertTrue(ledger.pendingWrites(outcome.context).isEmpty())
        val summaries = backup.evidence(outcome.context)
        assertEquals(setOf("READY", "CONSUMED", "RELEASED"), summaries.mapNotNull { it.intentStatus }.toSet())
        assertFalse(summaries.single { it.operationId == id(110) }.hadSubmissionReference)
        assertTrue(summaries.single { it.operationId == id(161) }.draftExcluded)
        val restored = root(outcome.context)
        assertEquals(witness, restored.list("submissionsEvidence").associate { it.text("operationId") to it.toString() })
        assertEquals(original.getJSONObject("reminderPreferences").toString(), restored.getJSONObject("reminderPreferences").toString())
        BackupValidator(clock).validate(BackupEnvelope.decrypt(backup.create(outcome.context, password()).encrypted, password()))
        for (op in listOf(160, 161)) {
            assertFails { payments.retry(outcome.context, id(op)) }
            assertFails { payments.release(outcome.context, id(op), released) }
            assertTrue(payments.prepared(outcome.context).isEmpty())
        }
    }

    /** 목적: 실제 교체 DELETE 이후 취소해도 전체 rollback한다.
     * 조건: 실제 파일 Room의 동기 query callback이 첫 transactions DELETE에서 복원 job을 취소한다.
     * 절차: 유지보수/복구사본 통과→DELETE 관찰→취소 종료 대기→기존 자료·완료표식·새 writer를 확인한다.
     * 기대: DELETE가 실제 관찰됐지만 금융·generation·fence 전체가 이전 상태이고 잠금이 해제된다.
     */
    @Test fun cancellationInsideReplacementTransactionRollsBackAllTables() = runTest {
        ready()
        db.close()
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val observed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = java.util.concurrent.atomic.AtomicReference<Job?>()
        db = androidx.room.Room.databaseBuilder(app, LedgerDatabase::class.java, "backup-restore.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .setQueryCallback({ sql, _ ->
                if (armed.get() && sql == "DELETE FROM transactions") {
                    observed.set(true)
                    task.get()?.cancel()
                }
            }, java.util.concurrent.Executor { it.run() })
            .build()
        ledger = RoomLedgerRepository(db, clock, contexts)
        backup = RoomBackupRepository(db, clock, contexts)
        val preview = inspect(exported())
        val before = root().toString()
        val job = async(Dispatchers.Default, start = CoroutineStart.LAZY) { backup.restore(context, preview.id, recovery) }
        task.set(job)
        armed.set(true)
        job.start()
        assertFailsWith<CancellationException> { withContext(Dispatchers.Default) { withTimeout(10_000) { job.await() } } }
        assertTrue(observed.get())
        assertEquals(before, root().toString())
        assertNull(backup.result(preview.id))
        ledger.createTransaction(context, id(131), transaction(31, 1))
    }

    /** v1 실제 baseline은 금액/원문/hash를 보존하며 자동 알림 기본값을 과거 기록으로 오인하지 않는다. */
    @Test fun usefulVersionOneBaselineRestoresAndReexportsExactOriginalBytes() = runTest {
        ready()
        val minimal = JSONObject(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")!!.use { it.reader().readText() })
        val example = JSONObject(javaClass.getResourceAsStream("/backup/backup-v1-ledger-example.json")!!.use { it.reader().readText() }).getJSONObject("ledger")
        val legacy = minimal.getJSONObject("ledger")
        for (name in listOf("accounts", "anchors", "transactions")) {
            legacy.put(name, JSONArray().put(example.getJSONArray(name).getJSONObject(0)))
            minimal.getJSONObject("manifest").getJSONObject("recordCounts").put(name, 1)
        }
        val raw = minimal.toString().toByteArray()
        val preview = backup.inspect(context, BackupEnvelope.encrypt(raw, password()), password())
        assertTrue(preview.legacyEvidenceMissing)
        val outcome = backup.restore(context, preview.id, recovery)
        val snapshot = ledger.snapshot(outcome.context, day)
        assertEquals(1, snapshot.transactions.size)
        assertEquals(1_000_000L, snapshot.balances.values.single())
        assertTrue(backup.evidence(outcome.context).isEmpty())
        assertFails { backup.notificationsReady(outcome.id) }
        val reminders = RoomReminderRepository(db, clock, contexts)
        val plan = checkNotNull(reminders.prepare(ReminderAccess(false, false, false)))
        assertTrue(reminders.applied(plan, false))
        assertFalse(backup.notificationsReady(outcome.id).notificationResetNeeded)
        val verified = BackupValidator(clock).validate(BackupEnvelope.decrypt(backup.create(outcome.context, password()).encrypted, password()))
        assertEquals("NOT_RECORDED_BY_V1", verified.root.getJSONObject("manifest").text("submissionCoverage"))
        assertEquals("NOT_RECORDED_BY_V1", verified.root.getJSONObject("reminderPreferences").text("coverage"))
        val original = verified.root.list("originals").single()
        assertEquals(BackupJson.digest(raw), original.text("sha256"))
        assertContentEquals(raw, java.util.Base64.getDecoder().decode(original.text("bytesBase64")))
        assertTrue(ledger.pendingWrites(outcome.context).isEmpty())
    }

    /** 유지보수 rollback도 clean 알림 설정의 재조정 신호를 남기되 금융/설정을 바꾸지 않는다. */
    @Test fun failedMaintenanceReleasesAReconciliationSignalWithoutDataMutation() = runTest {
        ready()
        val reminders = RoomReminderRepository(db, clock, contexts)
        assertTrue(reminders.applied(checkNotNull(reminders.prepare(ReminderAccess(false, false, false))), false))
        assertFalse(reminders.configuration(context).dirty)
        val before = root().toString()
        assertFailsWith<IOException> { db.maintenance<Unit> { throw IOException("synthetic copy failure") } }
        assertTrue(withContext(Dispatchers.Default) { withTimeout(10_000) { reminders.changes().first { it } } })
        assertFalse(reminders.configuration(context).dirty)
        assertEquals(before, root().toString())
    }

    private inner class MemoryRecovery : RecoveryCopyStore {
        var calls = 0
        var fail = false
        var wrongProof = false
        var before: suspend () -> Unit = {}
        var payload: ByteArray? = null
        override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof {
            calls++
            before()
            if (fail) throw IOException("synthetic copy failure")
            this.payload = payload.copyOf()
            return RecoveryCopyProof(id(800), if (wrongProof) "invalid" else BackupJson.digest(payload), payload.size)
        }
    }
}
