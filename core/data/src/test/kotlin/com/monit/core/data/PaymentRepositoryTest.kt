package com.monit.core.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

/** 합성자료의 실제 파일 Room 시험. 실제 단말 종료·전원 중단을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PaymentRepositoryTest {
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 11, 12)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private val dao get() = db.paymentDao()
    private val sql get() = db.openHelper.writableDatabase
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("payments.db")
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "payments.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
        payments = RoomPaymentRepository(db, ledger, clock, contexts)
    }
    private suspend fun accounts() {
        ledger.initialize(context)
        for ((number, nature) in listOf(10 to AccountNature.GENERAL, 20 to AccountNature.SAVINGS, 30 to AccountNature.INVESTMENT)) {
            ledger.createAccount(context, id(number + 100), CreateAccount(id(number), id(number + 1), "합성 계좌 $number", nature, day.minusDays(1), "미확인"))
        }
    }
    private fun terms(purpose: PaymentPurpose = PaymentPurpose.INSURANCE, amount: Long = 30_000, due: Int = 31) = PaymentTerms(
        "합성 납입",
        purpose,
        amount,
        due,
        id(10),
        when (purpose) {
            PaymentPurpose.SAVINGS -> id(20)
            PaymentPurpose.INVESTMENT -> id(30)
            else -> null
        },
    )
    private suspend fun rule(number: Int = 40, purpose: PaymentPurpose = PaymentPurpose.INSURANCE, start: String = "2026-01", end: String? = null) {
        payments.submit(context, id(number + 100), PaymentCommand.RegisterRule(id(number), terms(purpose), start, end))
    }
    private suspend fun item(month: String = "2026-11", number: Int = 40) = payments.month(context, month).items.single { it.reference.ruleId == id(number) }
    private fun transaction(number: Int = 50, amount: Long = 30_000, purpose: PaymentPurpose = PaymentPurpose.INSURANCE, date: LocalDate = day): CreateTransaction = CreateTransaction(id(number), if (purpose == PaymentPurpose.SAVINGS || purpose == PaymentPurpose.INVESTMENT) TransactionKind.TRANSFER else TransactionKind.EXPENSE, date, amount, id(10), terms(purpose).creditAccountId, "합성 납입 거래")
    private suspend fun confirm(operation: Int = 150, number: Int = 50, full: Boolean = true, amount: Long = 30_000, reason: String = "", month: String = "2026-11", rule: Int = 40, purpose: PaymentPurpose = PaymentPurpose.INSURANCE) = payments.submit(context, id(operation), PaymentCommand.Confirm(item(month, rule).reference, PaymentTransaction(transaction(number, amount, purpose)), full, reason))

    /** 예정 조회는 월말·윤년·시작/종료 포함을 계산하되 월항목·금융·명령을 생성하지 않는다. */
    @Test fun monthReadIsPureAndClampsMonthEndWithinInclusiveRange() = runTest {
        accounts()
        rule(start = "2026-02", end = "2028-02")
        val before = ledger.snapshot(context, day)
        assertTrue(payments.month(context, "2026-01").items.isEmpty())
        assertEquals(LocalDate.of(2026, 2, 28), item("2026-02").dueDate)
        assertEquals(LocalDate.of(2028, 2, 29), item("2028-02").dueDate)
        assertTrue(payments.month(context, "2028-03").items.isEmpty())
        assertEquals(item().reference.id, item().reference.id)
        assertEquals(0, item().reference.expectedRevision)
        assertTrue(dao.ruleItems(context.ledgerId, id(40)).isEmpty())
        assertEquals(before, ledger.snapshot(context, day))
        assertTrue(payments.prepared(context).isEmpty())
    }

    /** 신규 거래·월항목·완료·확인 근거를 함께 확정하고 응답 미보관 후 같은 ID로만 조회한다. */
    @Test fun newConfirmationCommitsOnceAndSurvivesReopenAndLostReply() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        val receipt = payments.submit(context, id(150), command)
        db.close()
        reopen()
        assertEquals(receipt, payments.submit(context, id(150), command))
        assertEquals(receipt, payments.retry(context, id(150)))
        assertEquals(receipt, ledger.retryPending(context, id(150)))
        assertEquals(PaymentAttempt.Committed(receipt), payments.attempt(context, id(150)))
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertEquals(id(50), item().transactionId)
        assertEquals(1, item().confirmedFinancialRevision)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
        assertEquals(30_000, ledger.snapshot(context, day).expense)
        assertEquals(1, dao.events(context.ledgerId, item().reference.id).size)
        assertTrue(ledger.pendingWrites(context).isEmpty())
        assertEquals(LedgerConflict.DUPLICATE_REQUEST, assertFailsWith<LedgerConflictException> { payments.submit(context, id(150), command.copy(reason = "다른 원문")) }.conflict)
    }

    /** 기존 거래 연결은 복제하지 않으며 연결 해제도 원래 거래·합계를 지우지 않는다. */
    @Test fun linkAndUnlinkExistingTransactionNeverDuplicateOrDeleteFinance() = runTest {
        accounts()
        rule()
        val tx = transaction()
        ledger.createTransaction(context, id(151), tx)
        val before = ledger.snapshot(context, day)
        payments.submit(context, id(150), PaymentCommand.Confirm(item().reference, PaymentTransaction(tx, financialRevision = 1), true, ""))
        assertEquals(before.transactions, ledger.snapshot(context, day).transactions)
        val linked = item()
        payments.submit(context, id(152), PaymentCommand.ChangeItem(linked.reference, PaymentAction.UNLINK))
        assertEquals(PaymentStatus.PENDING, item().status)
        assertNull(item().transactionId)
        assertEquals(before.transactions, ledger.snapshot(context, day).transactions)
        assertEquals(before.expense, ledger.snapshot(context, day).expense)
    }

    /** 차액 사유·전액 확인 없이 완료하지 않고 부분 거래의 완료 우회를 차단한다. */
    @Test fun differenceAndPartialMeaningRemainExplicitAndCannotBeRelinkedAsFull() = runTest {
        accounts()
        rule()
        assertFailsWith<IllegalArgumentException> { confirm(amount = 25_000) }
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        assertEquals(PaymentStatus.PENDING, item().status)
        assertEquals(PaymentAttempt.Released, payments.release(context, id(150)))
        confirm(operation = 151, full = false, amount = 25_000, reason = "일부만 기록")
        assertEquals(PaymentStatus.PENDING, item().status)
        assertNull(item().transactionId)
        assertEquals(25_000, ledger.snapshot(context, day).expense)
        assertTrue(dao.hasPartial(context.ledgerId, id(50)))
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(152), PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction(amount = 25_000), financialRevision = 1), true, "전액이라고 변경")) }
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
        assertEquals(PaymentStatus.PENDING, item().status)
    }

    /** 지난달 항목의 완료 이력과 실제 거래 월의 금액을 분리한다. */
    @Test fun delayedPaymentBelongsToTargetMonthButFinanceToActualMonth() = runTest {
        accounts()
        rule()
        confirm(month = "2026-10")
        assertEquals(PaymentStatus.COMPLETED, item("2026-10").status)
        assertEquals(PaymentStatus.PENDING, item("2026-11").status)
        assertEquals(0, ledger.snapshot(context, LocalDate.of(2026, 10, 31)).expense)
        assertEquals(30_000, ledger.snapshot(context, day).expense)
    }

    /** 보험은 지출, 저축·투자는 지정 성격의 계좌 이체이며 일반 지출 합계에 중복 가산하지 않는다. */
    @Test fun purposeUsesExpenseOrDesignatedAccountTransfer() = runTest {
        accounts()
        rule(40, PaymentPurpose.INSURANCE)
        rule(41, PaymentPurpose.SAVINGS)
        rule(42, PaymentPurpose.INVESTMENT)
        confirm(rule = 40)
        confirm(operation = 151, number = 51, rule = 41, purpose = PaymentPurpose.SAVINGS)
        confirm(operation = 152, number = 52, rule = 42, purpose = PaymentPurpose.INVESTMENT)
        assertTrue(payments.month(context, "2026-11").items.all { it.status == PaymentStatus.COMPLETED })
        assertEquals(30_000, ledger.snapshot(context, day).expense)
        assertEquals(0, ledger.snapshot(context, day).income)
        assertEquals(3, ledger.snapshot(context, day).transactions.size)
    }

    /** 메모는 완료를 유지하고 금융 수정은 같은 writer에서 재확인·거래 예약을 함께 반영한다. */
    @Test fun memoKeepsCompletionButFinancialChangeRequiresReconfirmationAndReservesTransaction() = runTest {
        accounts()
        rule()
        confirm()
        ledger.updateTransaction(context, id(160), UpdateTransaction(transaction().copy(memo = "메모 수정"), 1))
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertEquals(1, item().confirmedFinancialRevision)
        ledger.updateTransaction(context, id(161), UpdateTransaction(transaction().copy(amount = 25_000), 2))
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        assertEquals(id(50), item().transactionId)
        rule(41)
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(162), PaymentCommand.Confirm(item(number = 41).reference, PaymentTransaction(transaction(amount = 25_000), financialRevision = 2), true, "청구액 변경")) }
        payments.submit(context, id(163), PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction(amount = 25_000), financialRevision = 2), true, "청구액 변경"))
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertEquals(2, item().confirmedFinancialRevision)
    }

    /** 연결 거래 삭제는 원자적으로 연결 해제·재확인을 남기며 완료로 자동 복원하지 않는다. */
    @Test fun transactionDeletionUnlinksAndPreservesConfirmationHistory() = runTest {
        accounts()
        rule()
        confirm()
        ledger.deleteTransaction(context, id(160), DeleteTransaction(id(50), 1))
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        assertNull(item().transactionId)
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        assertEquals(listOf("CONFIRMED", "TRANSACTION_DELETED"), dao.events(context.ledgerId, item().reference.id).map { it.kind })
        assertNotNull(ledger.receipt(context, id(150)))
    }

    /** 건너뜀/해제는 금융 쓰기가 없으며 완료는 명시 연결 해제를 먼저 거쳐야 한다. */
    @Test fun skipAndUnskipNeverCreateFinanceOrSilentlyDropCompletion() = runTest {
        accounts()
        rule()
        payments.submit(context, id(150), PaymentCommand.ChangeItem(item().reference, PaymentAction.SKIP))
        assertEquals(PaymentStatus.SKIPPED, item().status)
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        payments.submit(context, id(151), PaymentCommand.ChangeItem(item().reference, PaymentAction.UNSKIP))
        assertEquals(PaymentStatus.PENDING, item().status)
        confirm(operation = 152)
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(153), PaymentCommand.ChangeItem(item().reference, PaymentAction.SKIP)) }
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertNotNull(item().transactionId)
    }

    /**
     * 목적: 다른 operation의 동시 완료가 거래를 두 번 만들지 못하게 한다.
     * 조건: 같은 아직 없는 월항목에 서로 다른 두 신규 거래를 준비한다.
     * 절차: 실제 Default dispatcher에서 공통 gate를 열고 10초 상한으로 결과를 기다린다.
     * 기대: 성공 한 건·명백한 revision 거절 한 건, 거래/완료/연결 이력은 한 건이다.
     */
    @Test fun concurrentConfirmationHasOneWinnerAndOneDurableRejection() = runTest {
        accounts()
        rule()
        val ref = item().reference
        val gate = CompletableDeferred<Unit>()
        val results = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val jobs = listOf(150, 151).map { n ->
                    async {
                        gate.await()
                        runCatching { payments.submit(context, id(n), PaymentCommand.Confirm(ref, PaymentTransaction(transaction(n - 100)), true, "")) }
                    }
                }
                gate.complete(Unit)
                jobs.awaitAll()
            }
        }
        assertEquals(1, results.count { it.isSuccess })
        val rejected = (results.single { it.isFailure }.exceptionOrNull() as PaymentRejectedException).rejection
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
        assertEquals(1, dao.events(context.ledgerId, ref.id).size)
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertEquals(PaymentAttempt.Rejected(rejected), payments.attempt(context, rejected.operationId))
        assertEquals(FinishSubmissionRecoveryResult.Finished, ledger.finishSubmissionRecovery(context, rejected.operationId))
        assertEquals(FinishSubmissionRecoveryResult.AlreadyInactive, ledger.finishSubmissionRecovery(context, rejected.operationId))
        assertTrue(ledger.submissionRecovery(context).items.none { it is SubmissionRecoveryItem.PaymentRejected })
    }

    /** 입력 실패는 준비한 원문만 남기고 이후 명시 해제로 늦은 이전 실행을 막는다. */
    @Test fun invalidInputRollsBackCaptureAndExplicitReleaseFencesLateRetry() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction(date = day.plusDays(2))), true, "")
        val before = ledger.snapshot(context, day)
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(150), command) }
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(db.ledgerDao().submission(context.ledgerId, id(150)))
        assertNull(db.ledgerDao().submissionReference(context.ledgerId, id(150)))
        assertNull(payments.rejection(context, id(150)))
        assertTrue(payments.attempt(context, id(150)) is PaymentAttempt.Prepared)
        assertEquals(command, payments.prepared(context).single().command)
        assertEquals(PaymentAttempt.Released, payments.release(context, id(150)))
        assertFailsWith<IllegalStateException> { payments.submit(context, id(150), command) }
        assertFailsWith<IllegalStateException> { payments.retry(context, id(150)) }
        confirm(operation = 151)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 미래 미완료만 같은 ID로 갱신하며 과거/현재/완료/건너뜀/재확인 스냅샷은 보존한다. */
    @Test fun ruleChangePreservesProtectedSnapshotsAndReplacesFuturePendingInPlace() = runTest {
        accounts()
        rule()
        confirm(month = "2026-10")
        payments.submit(context, id(151), PaymentCommand.ChangeItem(item("2026-11").reference, PaymentAction.SKIP))
        payments.submit(context, id(152), PaymentCommand.ChangeItem(item("2026-11").reference, PaymentAction.UNSKIP))
        payments.submit(context, id(153), PaymentCommand.ChangeItem(item("2026-12").reference, PaymentAction.SKIP))
        payments.submit(context, id(154), PaymentCommand.ChangeItem(item("2026-12").reference, PaymentAction.UNSKIP))
        payments.submit(context, id(155), PaymentCommand.ChangeItem(item("2027-01").reference, PaymentAction.SKIP))
        confirm(operation = 156, number = 51, month = "2027-02")
        ledger.updateTransaction(context, id(157), UpdateTransaction(transaction(51, 25_000), 1))
        confirm(operation = 158, number = 52, month = "2027-03")
        val old = listOf("2026-10", "2026-11", "2027-01", "2027-02", "2027-03").associateWith { item(it) }
        val future = item("2026-12")
        payments.submit(context, id(160), PaymentCommand.ChangeRule(id(40), 1, "2026-12", terms(amount = 40_000, due = 15)))
        for ((month, expected) in old) {
            val actual = item(month)
            assertEquals(expected.copy(reference = expected.reference.copy(expectedRuleRevision = 2)), actual)
        }
        assertEquals(future.reference.id, item("2026-12").reference.id)
        assertEquals(future.reference.expectedRevision + 1, item("2026-12").reference.expectedRevision)
        assertEquals(40_000, item("2026-12").terms.amount)
        assertEquals(LocalDate.of(2026, 12, 15), item("2026-12").dueDate)
        payments.submit(context, id(161), PaymentCommand.ChangeRule(id(40), 2, "2026-12", terms(amount = 45_000, due = 9)))
        assertEquals(45_000, item("2026-12").terms.amount)
        assertEquals(45_000, item("2027-04").terms.amount)
        assertEquals(3, dao.versions(context.ledgerId, id(40)).size)
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(162), PaymentCommand.ChangeRule(id(40), 3, "2026-11", terms())) }
    }

    /** 종료 월을 포함하고 이후 미완료만 비활성화하며 확인 이력은 예외로 남긴다. */
    @Test fun terminationPreservesExceptionsAndNeverReactivatesFutureItems() = runTest {
        accounts()
        rule()
        payments.submit(context, id(150), PaymentCommand.ChangeItem(item("2026-12").reference, PaymentAction.SKIP))
        payments.submit(context, id(151), PaymentCommand.ChangeItem(item("2026-12").reference, PaymentAction.UNSKIP))
        payments.submit(context, id(152), PaymentCommand.ChangeItem(item("2027-01").reference, PaymentAction.SKIP))
        confirm(operation = 153, month = "2027-02")
        val oldTransaction = ledger.snapshot(context, day).transactions
        payments.submit(context, id(154), PaymentCommand.EndRule(id(40), 1, "2026-11"))
        assertTrue(item("2026-11").active)
        assertFalse(item("2026-12").active)
        assertEquals(PaymentStatus.SKIPPED, item("2027-01").status)
        assertEquals(PaymentStatus.COMPLETED, item("2027-02").status)
        assertTrue(payments.month(context, "2027-03").items.isEmpty())
        assertEquals(oldTransaction, ledger.snapshot(context, day).transactions)
        payments.submit(context, id(155), PaymentCommand.ChangeRule(id(40), 2, "2026-12", terms(amount = 40_000)))
        assertFalse(item("2026-12").active)
        assertFailsWith<IllegalArgumentException> { payments.submit(context, id(156), PaymentCommand.EndRule(id(40), 3, "2026-12")) }
    }

    /**
     * 목적: 신규 납입의 SQL 실패에서 금융/월항목/참조/잠금이 함께 rollback하고 사전 원문이 유지된다.
     * 조건: 별도 commit한 초안·원래 요청이 있고 성공 원장 INSERT만 SQL trigger로 거부한다.
     * 절차: 제출 실패→원문/목록 조회→파일 재열기→trigger 제거→같은 ID/내용 명시 재시도.
     * 기대: 실패 때 금융/참조/pending/잠금0·초안 동일, 재시도 때 거래1·완료1·초안 소비.
     */
    @Test fun outerFailurePreservesIntentAndDraftWithoutBlockingOtherInputs() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        val (scope, content) = paymentDraftForCommand(command)
        var drafts = RoomDraftRepository(db, ledger, contexts)
        val draft = drafts.saveDraft(context, id(60), 0, scope, content)
        val before = ledger.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER fail_payment BEFORE INSERT ON operations WHEN NEW.kind='PAYMENT_CONFIRM' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<Exception> { payments.submit(context, id(150), command, DraftSubmission(draft.reference, scope)) }
        assertEquals(before, ledger.snapshot(context, day))
        assertEquals(draft, (drafts.draft(context, id(60)) as DraftSnapshot.Editable).draft)
        assertTrue(ledger.pendingWrites(context).isEmpty())
        assertTrue(ledger.submissionRecovery(context).items.none { it is SubmissionRecoveryItem.Pending || it is SubmissionRecoveryItem.LegacyPending })
        assertNull(db.ledgerDao().submissionReference(context.ledgerId, id(150)))
        assertNull(dao.item(context.ledgerId, command.item.id))
        db.close()
        reopen()
        drafts = RoomDraftRepository(db, ledger, contexts)
        assertEquals(draft, (drafts.draft(context, id(60)) as DraftSnapshot.Editable).draft)
        val request = payments.prepared(context).single()
        assertEquals(command, request.command)
        assertEquals(DraftSubmission(draft.reference, scope), request.draft)
        sql.execSQL("DROP TRIGGER fail_payment")
        val receipt = payments.retry(context, id(150))
        assertEquals(receipt, ledger.receipt(context, id(150)))
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
        assertTrue(drafts.draft(context, id(60)) is DraftSnapshot.Closed)
    }

    /**
     * 목적: 금융 트랜잭션 중 취소를 성공·확정 충돌로 오분류하지 않는다.
     * 조건: 실제 Room INSERT 콜백에서 해당 요청 Job만 취소한다.
     * 절차: LAZY 자식 Job 참조를 먼저 설정하고 실행/종료 뒤 DB의 모든 경계를 재조회한다.
     * 기대: 요청 원문만 Prepared, 금융/월항목/참조 없음, 거절 없음, 명시 해제 후 늦은 재시도 차단.
     */
    @Test fun cancellationRollsBackCaptureAndFinanceButKeepsOriginalIntent() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        val before = ledger.snapshot(context, day)
        db.close()
        val jobRef = AtomicReference<Job>()
        val observed = AtomicBoolean()
        db = Room.databaseBuilder(app, LedgerDatabase::class.java, "payments.db")
            .addMigrations(LedgerMigration1To2, LedgerMigration2To3, LedgerMigration3To4, LedgerMigration4To5)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .setQueryCallback({ query, _ ->
                if (query.startsWith("INSERT OR ABORT INTO `payment_items`")) {
                    observed.set(true)
                    jobRef.get()?.cancel()
                }
            }, Executor { it.run() })
            .build()
        ledger = RoomLedgerRepository(db, clock, contexts)
        payments = RoomPaymentRepository(db, ledger, clock, contexts)
        val job = launch(start = CoroutineStart.LAZY) { payments.submit(context, id(150), command) }
        jobRef.set(job)
        job.start()
        job.join()
        assertTrue(observed.get())
        assertTrue(job.isCancelled)
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(dao.item(context.ledgerId, command.item.id))
        assertNull(payments.rejection(context, id(150)))
        assertNull(db.ledgerDao().submission(context.ledgerId, id(150)))
        assertNull(db.ledgerDao().submissionReference(context.ledgerId, id(150)))
        assertTrue(payments.attempt(context, id(150)) is PaymentAttempt.Prepared)
        assertEquals(PaymentAttempt.Released, payments.release(context, id(150), command))
        assertFailsWith<IllegalStateException> { payments.retry(context, id(150)) }
    }

    /** ledger CAS 실패는 거절이 아니라 전체 rollback이며 원래 요청으로만 재시도한다. */
    @Test fun ledgerCasFailureNeverBecomesTerminalPaymentConflict() = runTest {
        accounts()
        rule()
        val before = ledger.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER fail_ledger BEFORE UPDATE ON ledgers BEGIN SELECT RAISE(IGNORE); END")
        assertEquals(LedgerConflict.STALE_REVISION, assertFailsWith<LedgerConflictException> { confirm() }.conflict)
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(payments.rejection(context, id(150)))
        assertTrue(payments.attempt(context, id(150)) is PaymentAttempt.Prepared)
        sql.execSQL("DROP TRIGGER fail_ledger")
        payments.retry(context, id(150))
        assertEquals(PaymentStatus.COMPLETED, item().status)
    }

    /** 금융 revision 불일치는 완료·연결·거래 변경 없이 거절하며 메모 변경은 전제로 추가하지 않는다. */
    @Test fun existingFinancialConflictIsDurableWhileMemoChangeCanStillConfirm() = runTest {
        accounts()
        rule()
        val tx = transaction()
        ledger.createTransaction(context, id(151), tx)
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(tx, financialRevision = 1), true, "")
        ledger.updateTransaction(context, id(152), UpdateTransaction(tx.copy(amount = 25_000), 1))
        val before = ledger.snapshot(context, day)
        val rejected = assertFailsWith<PaymentRejectedException> { payments.submit(context, id(150), command) }.rejection
        assertEquals("transaction_financial", rejected.collection)
        assertEquals(before, ledger.snapshot(context, day))
        assertNull(dao.item(context.ledgerId, command.item.id))
        db.close()
        reopen()
        assertEquals(rejected, assertFailsWith<PaymentRejectedException> { payments.retry(context, id(150)) }.rejection)
        ledger.finishSubmissionRecovery(context, id(150))
        val latest = transaction(amount = 25_000)
        ledger.updateTransaction(context, id(153), UpdateTransaction(latest.copy(memo = "메모만 변경"), 2))
        payments.submit(context, id(154), PaymentCommand.Confirm(item().reference, PaymentTransaction(latest, financialRevision = 2), true, "청구액 변경"))
        assertEquals(PaymentStatus.COMPLETED, item().status)
        assertEquals("메모만 변경", item().confirmedTransaction!!.memo)
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** 이미 확정된 동일 요청은 현재 금융 revision이 바뀌어도 과거 receipt가 우선하며 재완료하지 않는다. */
    @Test fun originalReceiptWinsAfterLaterFinancialChangeWithoutRecompletion() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        val receipt = payments.submit(context, id(150), command)
        ledger.updateTransaction(context, id(151), UpdateTransaction(transaction(amount = 25_000), 1))
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        assertEquals(receipt, payments.submit(context, id(150), command))
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        assertNull(payments.rejection(context, id(150)))
        assertEquals(1, ledger.snapshot(context, day).transactions.size)
    }

    /** #59의 금융 writer가 납입 이력 갱신 실패 시에도 함께 rollback하고 원래 v2 재시도를 유지한다. */
    @Test fun legacyTransactionWriterAndPaymentInvalidationRollbackTogether() = runTest {
        accounts()
        rule()
        confirm()
        val before = ledger.snapshot(context, day)
        val payment = item()
        sql.execSQL("CREATE TRIGGER fail_payment_event BEFORE INSERT ON payment_events WHEN NEW.kind='FINANCIAL_CHANGED' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<Exception> { ledger.updateTransaction(context, id(160), UpdateTransaction(transaction(amount = 25_000), 1)) }
        assertEquals(before, ledger.snapshot(context, day))
        assertEquals(payment, item())
        assertTrue(ledger.pendingRecovery(context).items.single() is PendingRecoveryItem.TransactionUpdate)
        sql.execSQL("DROP TRIGGER fail_payment_event")
        ledger.retryPending(context, id(160))
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        assertEquals(25_000, ledger.snapshot(context, day).transactions.single().amount)
    }

    /** 결과가 없더라도 명시 해제 fence를 먼저 만들면 늦은 원래 시도의 intent 생성도 막는다. */
    @Test fun explicitReleaseBeforeIntentExistsPreventsLateOriginalSubmission() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        assertEquals(PaymentAttempt.Missing, payments.attempt(context, id(150)))
        assertEquals(PaymentAttempt.Released, payments.release(context, id(150), command))
        assertFailsWith<IllegalStateException> { payments.submit(context, id(150), command) }
        assertTrue(ledger.snapshot(context, day).transactions.isEmpty())
        assertEquals(PaymentAttempt.Released, payments.attempt(context, id(150)))
    }

    /** canonical v3와 typed 복구를 검증하고 기존 생성 v1/수정 v2 원문은 변경하지 않는다. */
    @Test fun paymentCodecTypedLegacyRecoveryAndUnknownVersionRemainStrict() = runTest {
        accounts()
        rule()
        val command = PaymentCommand.Confirm(item().reference, PaymentTransaction(transaction()), true, "")
        val raw = capturePayment(context, command)
        assertEquals(command, decodePayment(context, raw))
        assertEquals(WriteCommand.Payment(command), decodeWrite(context, raw))
        db.ledgerDao().insertSubmission(SubmissionEntity(context.ledgerId, id(150), raw, requestHash(raw)))
        assertEquals(PendingRecoveryItem.PaymentSubmission(id(150), command), ledger.pendingRecovery(context).items.single())
        ledger.retryPending(context, id(150))
        assertEquals(PaymentStatus.COMPLETED, item().status)
        val unknown = raw.replace("\"version\":3", "\"version\":99")
        assertEquals(PendingRecoveryItem.Unsupported(id(151), UnsupportedSubmission.VERSION), SubmissionEntity(context.ledgerId, id(151), unknown, requestHash(unknown)).toRecoveryItem(context))
        assertTrue(SubmissionEntity(context.ledgerId, id(151), raw + " ", requestHash(raw + " ")).toRecoveryItem(context) is PendingRecoveryItem.Corrupted)
        for (kind in listOf(PaymentPurpose.GENERAL, PaymentPurpose.INSURANCE, PaymentPurpose.SAVINGS, PaymentPurpose.INVESTMENT)) {
            val register = PaymentCommand.RegisterRule(id(41), terms(kind), "2026-11")
            assertEquals(register, decodePayment(context, capturePayment(context, register)))
        }
    }
}
