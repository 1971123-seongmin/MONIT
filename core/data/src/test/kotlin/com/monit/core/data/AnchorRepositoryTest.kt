package com.monit.core.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AnchorRepositoryTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val day = LocalDate.of(2026, 11, 10)
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private val sql get() = db.openHelper.writableDatabase
    private fun command(revision: Long = 1, amount: Long = 1000) = ConfirmAnchor(id(10), id(11), revision, day, amount, "합성 마감액 명시 확인")
    private fun scope() = DraftScope(DraftKind.ANCHOR, DraftOrigin.ACCOUNTS, accountId = id(10), anchorId = id(11), anchorRevision = 1, anchorDate = day)

    @Before fun open() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "anchors.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("anchors.db")
    }
    private suspend fun ready() {
        ledger.initialize(context)
        for ((n, nature) in listOf(10 to AccountNature.GENERAL, 20 to AccountNature.SAVINGS)) ledger.createAccount(context, id(n + 100), CreateAccount(id(n), id(n + 1), "합성 계좌", nature, day, "미확인"))
    }

    /** 목적: 미확인→0→음수 변경의 이력과 독립 anchor revision 및 멱등성을 보장한다.
     * 조건: 미확인 계좌2개와 초기 계좌 영수증이 있다.
     * 절차: 0 확정→같은 요청 재전송→음수 변경→DB 재열기 후 최초 영수증을 재조회한다.
     * 기대: account revision1 유지, anchor3/이력2, 원래 receipt·raw/hash 보존, 새 거래 없음.
     */
    @Test fun independentRevisionHistoryAndLostReplyReplaySurviveReopen() = runTest {
        ready()
        val creation = ledger.receipt(context, id(110))
        val receipt = ledger.confirmAnchor(context, id(200), command(amount = 0).copy(reason = ""))
        val raw = db.ledgerDao().operation(context.ledgerId, id(200))!!
        assertEquals(receipt, ledger.confirmAnchor(context, id(200), command(amount = 0).copy(reason = "")))
        ledger.confirmAnchor(context, id(201), command(2, -300))
        db.close()
        reopen()
        val snapshot = ledger.snapshot(context, day.plusDays(3))
        val account = snapshot.accounts.single { it.id == id(10) }
        assertEquals(1, account.revision)
        assertEquals(3, account.anchor.revision)
        assertEquals(-300, snapshot.balances[id(10)])
        assertNull(snapshot.balances[id(20)])
        assertTrue(snapshot.transactions.isEmpty())
        val history = ledger.anchorHistory(context, id(10))
        assertEquals(listOf(null, 0L), history.map { it.before.amount })
        assertEquals(listOf(2L, 3L), history.map { it.revision })
        assertEquals(listOf(AnchorChangeKind.INITIAL_CONFIRMATION, AnchorChangeKind.CORRECTION), history.map { it.kind })
        assertEquals("", history.first().reason)
        assertEquals(creation, ledger.receipt(context, id(110)))
        assertEquals(receipt, ledger.receipt(context, id(200)))
        assertEquals(raw, db.ledgerDao().operation(context.ledgerId, id(200)))
    }

    /** 목적: 명백한 anchor CAS만 확정 거절하고 그 원문/초안을 보존한다.
     * 조건: 같은 revision1을 검토한 초안과 별도 확정이 있다.
     * 절차: 별도 확정 후 이전 초안 제출→재시도→명시 결과 종료를 한다.
     * 기대: 기준액1000/이력1 유지, typed거절·REJECTED초안·원문 보존, 종료 후 자동 재시도 안 함.
     */
    @Test fun staleOwnRevisionRejectsWithoutRewritingDraftOrOriginalRequest() = runTest {
        ready()
        val drafts = RoomDraftRepository(db, ledger, contexts)
        val form = drafts.saveDraft(context, id(300), 0, scope(), DraftContent.Anchor("2000", command().reason))
        ledger.confirmAnchor(context, id(200), command())
        val stale = command(amount = 2000)
        val failure = assertFailsWith<AnchorRejectedException> { ledger.confirmAnchor(context, id(201), stale, DraftSubmission(form.reference, form.scope)) }
        assertEquals(2, failure.rejection.actualRevision)
        assertEquals(failure.rejection, ledger.anchorRejection(context, id(201)))
        assertFailsWith<AnchorRejectedException> { ledger.retryPending(context, id(201)) }
        assertEquals(form.content, (drafts.draft(context, id(300)) as DraftSnapshot.Rejected).draft.content)
        assertTrue(ledger.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.AnchorRejected })
        assertFalse(ledger.pendingWrites(context).any { it.operationId == id(201) })
        assertEquals(1000, ledger.snapshot(context, day).balances[id(10)])
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
        ledger.finishSubmissionRecovery(context, id(201))
        assertFalse(ledger.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.AnchorRejected })
        assertEquals(captureAnchor(context, stale), db.ledgerDao().submission(context.ledgerId, id(201))!!.requestJson)
    }

    /** 목적: 저장 I/O 오류는 terminal거절이 아니며 두번째 원자경계를 전부 rollback한다.
     * 조건: 이력 INSERT를 거절하는 SQLite trigger와 편집 초안이 있다.
     * 절차: 제출 실패→원문/초안/금융 조회→trigger제거→원래 ID retry한다.
     * 기대: 실패시 anchor·이력·receipt 불변, pending+SUBMITTING 보존, 재시도 한 번 반영·초안소비.
     */
    @Test fun historyFailureRollsBackFinanceButKeepsCapturedRequestAndDraftForRetry() = runTest {
        ready()
        val drafts = RoomDraftRepository(db, ledger, contexts)
        val form = drafts.saveDraft(context, id(300), 0, scope(), DraftContent.Anchor("1000", command().reason))
        val before = ledger.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER fail_anchor BEFORE INSERT ON anchor_history BEGIN SELECT RAISE(ABORT,'synthetic I/O'); END")
        assertFails { ledger.confirmAnchor(context, id(200), command(), DraftSubmission(form.reference, form.scope)) }
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(ledger.receipt(context, id(200)))
        assertNull(ledger.anchorRejection(context, id(200)))
        assertTrue(ledger.anchorHistory(context, id(10)).isEmpty())
        assertIs<DraftSnapshot.Submitted>(drafts.draft(context, id(300)))
        assertIs<PendingRecoveryItem.AnchorConfirmation>(ledger.pendingRecovery(context).items.single())
        sql.execSQL("DROP TRIGGER fail_anchor")
        val receipt = ledger.retryPending(context, id(200))
        assertEquals(receipt, ledger.retryPending(context, id(200)))
        assertIs<DraftSnapshot.Closed>(drafts.draft(context, id(300)))
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
    }

    /** 저장된 C와 다른 날짜/계좌는 CAS 충돌로 포장하지 않고 금융·이력 불변 및 typed pending을 유지한다. */
    @Test fun immutableDateAndOwnerMismatchAreNotTerminalRevisionConflicts() = runTest {
        ready()
        for ((n, value) in listOf(200 to command().copy(date = day.minusDays(1)), 201 to command().copy(accountId = id(20)), 202 to command().copy(date = day.plusDays(3)))) {
            assertFails { ledger.confirmAnchor(context, id(n), value) }
            assertNull(ledger.anchorRejection(context, id(n)))
            assertNull(ledger.receipt(context, id(n)))
        }
        assertEquals(3, ledger.pendingWrites(context).size)
        assertTrue(ledger.anchorHistory(context, id(10)).isEmpty())
        assertNull(ledger.snapshot(context, day).balances[id(10)])
    }

    /** 확인된 기준액은 C당일 거래를 다시 더하지 않고, 수정/논리삭제 효과는 다음날 이후 실제 거래에만 적용한다. */
    @Test fun realTransactionsUpdateDeleteAndDifferentAnchorsRemainConsistent() = runTest {
        ready()
        val first = CreateTransaction(id(30), TransactionKind.TRANSFER, day, 300, id(10), id(20))
        val next = first.copy(id = id(31), date = day.plusDays(1), amount = 200)
        ledger.createTransaction(context, id(130), first)
        ledger.createTransaction(context, id(131), next)
        ledger.confirmAnchor(context, id(200), command())
        ledger.confirmAnchor(context, id(201), command().copy(accountId = id(20), anchorId = id(21), amount = 500))
        assertEquals(mapOf(id(10) to 800L, id(20) to 700L), ledger.snapshot(context, day.plusDays(3)).balances)
        assertEquals(AssetFlows(500, 0, 500), assetFlows(ledger.snapshot(context, day.plusDays(3)).transactions, YearMonth.from(day)))
        ledger.updateTransaction(context, id(140), UpdateTransaction(next.copy(amount = 250), 1))
        ledger.deleteTransaction(context, id(141), DeleteTransaction(id(31), 2))
        assertEquals(mapOf(id(10) to 1000L, id(20) to 500L), ledger.snapshot(context, day.plusDays(3)).balances)
        assertEquals(AssetFlows(300, 0, 300), assetFlows(ledger.snapshot(context, day.plusDays(3)).transactions, YearMonth.from(day)))
        assertFails { ledger.updateTransaction(context, id(142), UpdateTransaction(first.copy(date = day.plusDays(1)), 1)) }
        assertEquals(day, ledger.snapshot(context, day.plusDays(3)).transactions.single().date)
    }

    /** stale generation은 어떤 제출·금융·거절 기록도 만들지 않는다. */
    @Test fun staleGenerationAndDuplicateOperationAreNotReinterpreted() = runTest {
        ready()
        assertFailsWith<LedgerConflictException> { ledger.confirmAnchor(context.copy(storageGeneration = id(999)), id(200), command()) }
        assertNull(db.ledgerDao().submission(context.ledgerId, id(200)))
        ledger.confirmAnchor(context, id(200), command())
        assertFailsWith<LedgerConflictException> { ledger.confirmAnchor(context, id(200), command(amount = 2000)) }
        assertNull(ledger.anchorRejection(context, id(200)))
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
    }

    /** 손상된 성공 이력은 receipt의 성공 표시를 막지만 기존 원문을 고치지 않는다. */
    @Test fun historyCorruptionBlocksSuccessRecovery() = runTest {
        ready()
        ledger.confirmAnchor(context, id(200), command())
        sql.execSQL("UPDATE anchor_history SET amount=2000 WHERE operationId=?", arrayOf(id(200)))
        assertFails { ledger.receipt(context, id(200)) }
        assertTrue(ledger.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.Blocked })
        sql.execSQL("UPDATE anchor_history SET beforeRevision=? WHERE operationId=?", arrayOf(Long.MAX_VALUE, id(200)))
        assertTrue(ledger.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.Blocked })
        assertEquals(captureAnchor(context, command()), db.ledgerDao().operation(context.ledgerId, id(200))!!.requestJson)
    }

    /** 목적: 같은 anchor revision을 검토한 동시 두 확인 중 한 건만 반영한다.
     * 조건: 실제 Default dispatcher·공통 시작 gate·서로 다른 operation이다.
     * 절차: gate 해제 후 10초 상한 안에 둘 다 종료한다.
     * 기대: 성공1·anchor확정거절1·이력1·anchor revision2이다.
     */
    @Test fun concurrentOwnRevisionHasExactlyOneWinner() = runTest {
        ready()
        val gate = CompletableDeferred<Unit>()
        val outcomes = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val jobs = listOf(200, 201).map { n ->
                    async {
                        gate.await()
                        runCatching { ledger.confirmAnchor(context, id(n), command(amount = n.toLong()).copy(reason = "")) }
                    }
                }
                gate.complete(Unit)
                jobs.awaitAll()
            }
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is AnchorRejectedException })
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
        assertEquals(2, ledger.snapshot(context, day).accounts.single { it.id == id(10) }.anchor.revision)
    }

    /** 목적: P2 날짜/계좌 경계 거절이 금융행·revision·이력·기존 납입 연결을 바꾸지 않는다.
     * 조건: 기준일 다음날의 실제 거래에 수동 완료가 연결되어 있다.
     * 절차: 날짜를 C당일로 이동/상대 계좌 제거를 각각 시도하고 모든 업무 테이블을 대조한다.
     * 기대: AnchorBoundaryException, 전체 금융 fingerprint 동일·anchor거절 없음·새pending 없음.
     */
    @Test fun boundaryRejectionKeepsLinkedPaymentAndAllFinancialRecordsUnchanged() = runTest {
        ready()
        val payments = RoomPaymentRepository(db, ledger, clock, contexts)
        payments.submit(context, id(140), PaymentCommand.RegisterRule(id(40), PaymentTerms("합성 저축", PaymentPurpose.SAVINGS, 200, 11, id(10), id(20)), "2026-11"))
        val value = CreateTransaction(id(30), TransactionKind.TRANSFER, day.plusDays(1), 200, id(10), id(20))
        payments.submit(context, id(150), PaymentCommand.Confirm(payments.month(context, "2026-11").items.single().reference, PaymentTransaction(value), true, ""))
        fun records(): List<List<List<String?>>> = listOf("accounts", "anchors", "transactions", "transaction_history", "payment_rules", "payment_rule_versions", "payment_items", "payment_events", "operations", "anchor_history").map { table -> sql.query("SELECT * FROM $table ORDER BY rowid").use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) } } }
        val before = records()
        assertFailsWith<AnchorBoundaryException> { ledger.updateTransaction(context, id(200), UpdateTransaction(value.copy(date = day), 1)) }
        assertEquals(before, records())
        assertFailsWith<AnchorBoundaryException> { ledger.updateTransaction(context, id(201), UpdateTransaction(value.copy(kind = TransactionKind.EXPENSE, creditAccountId = null), 1)) }
        assertEquals(before, records())
        assertNull(ledger.anchorRejection(context, id(200)))
        assertNull(db.ledgerDao().submission(context.ledgerId, id(200)))
        assertEquals(PaymentStatus.COMPLETED, payments.month(context, "2026-11").items.single().status)
    }

    /** 납입과 기준액이 같은 operation UUID를 재사용해 다른 원문/intent를 남기지 않는다. */
    @Test fun paymentCannotCaptureAnAnchorPendingOperation() = runTest {
        ready()
        sql.execSQL("CREATE TRIGGER fail_anchor BEFORE INSERT ON anchor_history BEGIN SELECT RAISE(ABORT,'synthetic I/O'); END")
        assertFails { ledger.confirmAnchor(context, id(200), command()) }
        val pending = db.ledgerDao().submission(context.ledgerId, id(200))
        val payments = RoomPaymentRepository(db, ledger, clock, contexts)
        assertFails { payments.submit(context, id(200), PaymentCommand.RegisterRule(id(40), PaymentTerms("합성 보험", PaymentPurpose.INSURANCE, 200, 11, id(10)), "2026-11")) }
        assertNull(db.paymentDao().intent(context.ledgerId, id(200)))
        assertEquals(pending, db.ledgerDao().submission(context.ledgerId, id(200)))
        sql.execSQL("DROP TRIGGER fail_anchor")
        ledger.retryPending(context, id(200))
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
    }

    /** 기존 확인된 기준액의 변경 사유는 필수이며 빈 사유 실패가 최초확인으로 오인되지 않는다. */
    @Test fun confirmedAnchorCannotBeChangedWithoutReason() = runTest {
        ready()
        ledger.confirmAnchor(context, id(200), command().copy(reason = ""))
        val before = ledger.snapshot(context, day)
        assertFailsWith<IllegalArgumentException> { ledger.confirmAnchor(context, id(201), command(2, 2000).copy(reason = "")) }
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(ledger.anchorRejection(context, id(201)))
        assertNull(ledger.receipt(context, id(201)))
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
        assertEquals("", ledger.anchorHistory(context, id(10)).single().reason)
    }

    /** 목적: ledger CAS 실패를 anchor 충돌로 확정하지 않는다.
     * 조건: SQLite trigger가 ledger revision 갱신을 0행으로 만든다.
     * 절차: 제출 실패→금융/이력/결과 조회→trigger제거→원래 pending 재시도.
     * 기대: 첫 단계 원문만 유지, 금융/이력 원복·거절 없음, 재시도1회 성공.
     */
    @Test fun ledgerCasFailureRollsBackAnchorAndHistoryWithoutTerminalRejection() = runTest {
        ready()
        val before = ledger.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER fail_anchor_ledger BEFORE UPDATE ON ledgers BEGIN SELECT RAISE(IGNORE); END")
        assertFailsWith<LedgerConflictException> { ledger.confirmAnchor(context, id(200), command()) }
        assertEquals(before, ledger.snapshot(context, day))
        assertTrue(ledger.anchorHistory(context, id(10)).isEmpty())
        assertNull(ledger.anchorRejection(context, id(200)))
        assertNull(ledger.receipt(context, id(200)))
        assertEquals(captureAnchor(context, command()), ledger.pendingWrites(context).single().requestJson)
        sql.execSQL("DROP TRIGGER fail_anchor_ledger")
        ledger.retryPending(context, id(200))
        assertEquals(1, ledger.anchorHistory(context, id(10)).size)
    }

    /** 목적: 금융 반영 중 취소를 성공·anchor 확정충돌로 바꾸지 않는다.
     * 조건: 실제 Room UPDATE 콜백에서 이 제출의 Job만 취소한다.
     * 절차: LAZY Job 참조를 먼저 설정→기준액 UPDATE에서 취소→종료 후 저장 경계를 읽는다.
     * 기대: anchor/금융/이력/receipt 불변·거절 없음, 첫 단계 원래 pending 보존.
     */
    @Test fun cancellationDuringAnchorUpdateRollsBackOnlyFinancialStage() = runTest {
        ready()
        val before = ledger.snapshot(context, day)
        db.close()
        val jobRef = AtomicReference<Job>()
        val observed = AtomicBoolean()
        db = Room.databaseBuilder(app, LedgerDatabase::class.java, "anchors.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .setQueryCallback({ query, _ ->
                if (query.startsWith("UPDATE anchors SET amount")) {
                    observed.set(true)
                    jobRef.get()?.cancel()
                }
            }, Executor { it.run() })
            .build()
        ledger = RoomLedgerRepository(db, clock, contexts)
        val job = launch(start = CoroutineStart.LAZY) { ledger.confirmAnchor(context, id(200), command()) }
        jobRef.set(job)
        job.start()
        job.join()
        assertTrue(observed.get())
        assertTrue(job.isCancelled)
        assertEquals(before, ledger.snapshot(context, day))
        assertTrue(ledger.anchorHistory(context, id(10)).isEmpty())
        assertNull(ledger.receipt(context, id(200)))
        assertNull(ledger.anchorRejection(context, id(200)))
        assertEquals(captureAnchor(context, command()), ledger.pendingWrites(context).single().requestJson)
    }
}
