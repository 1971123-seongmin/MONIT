package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteException
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** 기존 CREATE_TRANSACTION v1의 투영·명시 재시도만 실제 파일에서 확인한다. 자동 실행/UI 시험은 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TransactionPendingRecoveryTest {
    private lateinit var application: Application
    private lateinit var database: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-02T15:30:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val dao get() = database.ledgerDao()
    private val sql get() = database.openHelper.writableDatabase
    private val tables = listOf(
        "ledgers",
        "accounts",
        "anchors",
        "transactions",
        "operations",
        "submissions",
        "submission_references"
    )

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        application.deleteDatabase("transaction-pending.db")
    }

    private fun reopen() {
        database = createLedgerDatabase(application, "transaction-pending.db")
        repository = RoomLedgerRepository(database, clock, contexts)
    }

    private suspend fun ready() {
        repository.initialize(context)
        for (number in listOf(1L, 3L)) {
            val receipt = repository.createAccount(
                context, id(1000 + number), CreateAccount(
                    id(number), id(number + 1),
                    "합성 계좌 $number", AccountNature.GENERAL, LocalDate.of(2026, 10, 31), "확인 전"
                )
            )
            assertEquals(
                FinishSubmissionRecoveryResult.Finished,
                repository.finishSubmissionRecovery(context, receipt.operationId)
            )
        }
    }

    private fun value(number: Long, kind: TransactionKind) = CreateTransaction(
        id(number), kind, LocalDate.of(2026, 11, 1), 15 + number,
        debitAccountId = if (kind != TransactionKind.INCOME) id(1) else null,
        creditAccountId = if (kind != TransactionKind.EXPENSE) id(3) else null,
        memo = "합성 \"${kind.name}\"\n메모 \\ 보존",
    )

    private suspend fun pending(
        operationId: String,
        value: CreateTransaction,
        active: Boolean
    ): SubmissionEntity {
        val raw = captureWrite(context, WriteCommand.Transaction(value))
        val row = SubmissionEntity(context.ledgerId, operationId, raw, requestHash(raw))
        dao.insertSubmission(row)
        if (active) dao.insertSubmissionReference(
            SubmissionReferenceEntity(
                context.ledgerId,
                operationId,
                context.storageGeneration
            )
        )
        return row
    }

    private fun rows(): List<List<List<String?>>> = tables.map { table ->
        sql.query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                    cursor.getString(
                        it
                    )
                })
            }
        }
    }

    /** 수입/지출/이체의 legacy·활성 캡처를 재열어도 요청·ID·계좌 참조가 그대로이며 조회는 무변경이다. */
    @Test
    fun allKindsProjectOriginalTypedValuesForLegacyAndActivePendingWithoutWrites() = runTest {
        ready()
        val originals = mutableListOf<Triple<SubmissionEntity, Boolean, CreateTransaction>>()
        for (active in listOf(false, true)) {
            for ((index, kind) in TransactionKind.entries.withIndex()) {
                val number = 10L + index + if (active) 10 else 0
                val original = value(number, kind)
                originals += Triple(pending(id(100 + number), original, active), active, original)
            }
        }
        database.close(); reopen()
        val before = rows()
        val typed = repository.pendingRecovery(context).items
        val discovered = repository.submissionRecovery(context).items
        for ((row, active, original) in originals) {
            val expected = PendingRecoveryItem.TransactionCreation(row.operationId, original)
            assertTrue(expected in typed)
            assertTrue(
                (if (active) SubmissionRecoveryItem.Pending(
                    SubmissionReference(
                        context,
                        row.operationId
                    ), expected
                )
                else SubmissionRecoveryItem.LegacyPending(expected)) in discovered
            )
            assertEquals(row, dao.submission(context.ledgerId, row.operationId))
        }
        assertEquals(6, typed.size)
        assertEquals(6, discovered.size)
        assertTrue(dao.transactions(context.ledgerId).isEmpty())
        assertEquals(before, rows())
    }

    /**
     * 목적: 기존 submit에서 금융 commit 실패 뒤 남은 세 종류 거래를 원래 ID로만 명시 재시도한다.
     * 조건: CREATE_TRANSACTION 성공 원장 INSERT trigger가 실패해 첫 capture/ref만 확정된다.
     * 절차: 파일 재열기·무변경 조회 후 trigger를 제거하고 선택한 ID별로 재시도·중복 재시도한다.
     * 기대: canonical 원문/hash/거래ID/계좌가 유지되고 각 금융 commit은 한 번, 영수증도 동일하다.
     */
    @Test
    fun capturedTransactionFailuresRetryEachOriginalCommandExactlyOnce() = runTest {
        ready()
        sql.execSQL("CREATE TRIGGER fail_transaction BEFORE INSERT ON operations WHEN NEW.kind='CREATE_TRANSACTION' BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        val requests = TransactionKind.entries.mapIndexed { index, kind ->
            id(110L + index) to value(
                10L + index,
                kind
            )
        }
        for ((operationId, value) in requests) {
            assertFailsWith<SQLiteException> {
                repository.createTransaction(
                    context,
                    operationId,
                    value
                )
            }
        }
        val captured = dao.submissions(context.ledgerId)
        assertTrue(dao.transactions(context.ledgerId).isEmpty())
        database.close(); reopen()
        val beforeRead = rows()
        assertEquals(
            3,
            repository.submissionRecovery(context).items.filterIsInstance<SubmissionRecoveryItem.Pending>().size
        )
        assertEquals(beforeRead, rows())
        sql.execSQL("DROP TRIGGER fail_transaction")
        for ((operationId, value) in requests) {
            val receipt = repository.retryPending(context, operationId)
            val original = captured.single { it.operationId == operationId }
            val operation = dao.operation(context.ledgerId, operationId)!!
            assertEquals(original.requestJson, operation.requestJson)
            assertEquals(original.requestSha256, operation.requestSha256)
            assertEquals(
                value,
                (decodeWrite(context, operation.requestJson) as WriteCommand.Transaction).value
            )
            assertEquals(value.id, receipt.entities.single().id)
            val after = rows()
            assertEquals(receipt, repository.retryPending(context, operationId))
            assertEquals(receipt, repository.createTransaction(context, operationId, value))
            assertEquals(after, rows())
        }
        assertEquals(3, dao.transactions(context.ledgerId).size)
        assertEquals(6L, repository.snapshot(context, LocalDate.of(2026, 11, 1)).revision)
        assertTrue(repository.pendingWrites(context).isEmpty())
    }

    /**
     * 목적: 참조 없는 이전 거래 pending도 선택 재시도에서만 활성 참조를 등록한다.
     * 조건: 세 종류의 검증된 legacy 캡처가 있고 금융 행은 없다.
     * 절차: 조회 무변경을 확인하고 한 건씩 기존 retryPending을 호출한다.
     * 기대: 원래 ID/캡처로 성공하며 선택하지 않은 pending은 그대로, 참조는 선택한 ID만 추가된다.
     */
    @Test
    fun legacyTransactionsRegisterReferencesOnlyWhenExplicitlyRetried() = runTest {
        ready()
        val originals = TransactionKind.entries.mapIndexed { index, kind ->
            pending(
                id(110L + index),
                value(10L + index, kind),
                false
            )
        }
        val before = rows()
        assertEquals(
            3,
            repository.submissionRecovery(context).items.filterIsInstance<SubmissionRecoveryItem.LegacyPending>().size
        )
        assertEquals(before, rows())
        for ((index, row) in originals.withIndex()) {
            val receipt = repository.retryPending(context, row.operationId)
            assertEquals(row.operationId, receipt.operationId)
            assertEquals(
                row.requestJson,
                dao.operation(context.ledgerId, row.operationId)!!.requestJson
            )
            assertEquals(
                originals.take(index + 1).map { it.operationId },
                dao.submissionReferences(context.ledgerId).map { it.operationId })
            assertEquals(originals.drop(index + 1), dao.submissions(context.ledgerId))
        }
    }

    /**
     * 목적: commit 응답을 UI에 보관하지 못해도 새 제출을 만들지 않고 성공 영수증을 우선한다.
     * 조건: 거래 commit과 pending 소비 뒤 파일을 닫아 호출자 상태를 버린다.
     * 절차: 발견 참조의 원래 ID로 결과 확인·재시도를 호출한다.
     * 기대: 원래 receipt/거래 한 건이며 조회·재시도·같은 명령 재전달은 행을 변경하지 않는다.
     */
    @Test
    fun lostCommitResponseReturnsReceiptBeforeLookingForConsumedPending() = runTest {
        ready()
        val original = value(10, TransactionKind.TRANSFER)
        repository.createTransaction(context, id(110), original)
        database.close(); reopen()
        val before = rows()
        val committed =
            repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Committed
        assertEquals(id(110), committed.reference.operationId)
        assertEquals(
            committed.receipt,
            repository.receipt(context, committed.reference.operationId)
        )
        assertEquals(
            committed.receipt,
            repository.retryPending(context, committed.reference.operationId)
        )
        assertEquals(
            committed.receipt,
            repository.createTransaction(context, committed.reference.operationId, original)
        )
        assertEquals(before, rows())
    }

    @Test
    fun invalidCanonicalTransactionsRemainBlockedAndCannotWrite() = runTest {
        ready()
        val original = pending(id(110), value(10, TransactionKind.TRANSFER), true)
        val invalid = listOf(
            original.copy(requestSha256 = "bad") to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"version\":1", "\"version\":2")
                .let { changed(original, it) } to SubmissionRecoveryProblem.UNSUPPORTED,
            original.requestJson.replace("CREATE_TRANSACTION", "FUTURE_KIND")
                .let { changed(original, it) } to SubmissionRecoveryProblem.UNSUPPORTED,
            changed(original, original.requestJson + " ") to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace(context.storageGeneration, id(999))
                .let { changed(original, it) } to SubmissionRecoveryProblem.GENERATION_MISMATCH,
            original.requestJson.replace("\"amount\":\"25\"", "\"amount\":\"025\"")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("2026-11-01", "bad-date")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace(id(10), "bad-id")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace(id(3), id(1))
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace(
                "\"creditAccountId\":\"${id(3)}\"",
                "\"creditAccountId\":null"
            )
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"kind\":\"TRANSFER\"", "\"kind\":\"INCOME\"")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"kind\":\"TRANSFER\"", "\"kind\":\"EXPENSE\"")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"amount\":\"25\"", "\"amount\":\"0\"")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"amount\":\"25\"", "\"amount\":\"1000000000000\"")
                .let { changed(original, it) } to SubmissionRecoveryProblem.CORRUPTED,
        )
        for ((row, reason) in invalid) {
            sql.execSQL(
                "UPDATE submissions SET requestJson=?,requestSha256=?",
                arrayOf(row.requestJson, row.requestSha256)
            )
            val before = rows()
            assertEquals(
                SubmissionRecoveryItem.Blocked(
                    SubmissionReference(context, id(110)),
                    reason
                ), repository.submissionRecovery(context).items.single()
            )
            assertTrue(repository.pendingRecovery(context).items.single() !is PendingRecoveryItem.TypedSubmission)
            val error = assertFails { repository.retryPending(context, id(110)) }
            assertTrue(error is IllegalStateException || error is IllegalArgumentException || error is java.time.DateTimeException)
            assertEquals(before, rows())
        }
    }

    @Test
    fun originalGenerationAndReferenceWithoutCaptureCannotBeReboundToNewTransaction() = runTest {
        ready()
        pending(id(110), value(10, TransactionKind.INCOME), true)
        sql.execSQL("UPDATE submission_references SET storageGeneration=?", arrayOf(id(999)))
        val before = rows()
        val reference = SubmissionReference(LedgerContext(context.ledgerId, id(999)), id(110))
        assertEquals(
            SubmissionRecoveryItem.Blocked(
                reference,
                SubmissionRecoveryProblem.GENERATION_MISMATCH
            ), repository.submissionRecovery(context).items.single()
        )
        assertEquals(
            LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> {
                repository.retryPending(
                    context,
                    id(110)
                )
            }.conflict
        )
        assertEquals(before, rows())
        sql.execSQL(
            "UPDATE submission_references SET storageGeneration=?",
            arrayOf(context.storageGeneration)
        )
        sql.execSQL("DELETE FROM submissions")
        val missing = rows()
        assertEquals(
            SubmissionRecoveryProblem.MISSING_RECORD,
            (repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Blocked).reason
        )
        assertFailsWith<IllegalStateException> {
            repository.createTransaction(
                context,
                id(110),
                value(10, TransactionKind.INCOME)
            )
        }
        assertEquals(missing, rows())
    }

    @Test
    fun duplicateSuccessAndPendingAreBlockedEvenWhenBothRequestsAreTypedValid() = runTest {
        ready()
        val original = value(10, TransactionKind.EXPENSE)
        repository.createTransaction(context, id(110), original)
        pending(id(110), original, false)
        val before = rows()
        assertEquals(
            SubmissionRecoveryProblem.CONFLICTING_RECORDS,
            (repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Blocked).reason
        )
        assertFailsWith<IllegalStateException> { repository.retryPending(context, id(110)) }
        assertEquals(before, rows())
    }

    /**
     * 목적: canonical 요청의 유효성과 현재 계좌의 실행 가능성을 구분하고 writer 검증을 유지한다.
     * 조건: legacy/활성 pending이 부재·다른 장부 소유·보관 계좌를 각각 참조한다.
     * 절차: 읽기 무변경과 정상 typed 투영 후 사용자가 선택한 기존 ID만 명시 재시도한다.
     * 기대: writer는 MISSING_REFERENCE/ARCHIVED_ACCOUNT로 금융 쓰기를 거부하며 legacy 참조 등록만 허용된다.
     */
    @Test
    fun currentAccountAvailabilityDoesNotReclassifyCaptureButStillGuardsFinancialRetry() = runTest {
        ready()
        val other = LedgerContext(id(902), id(903))
        repository.initialize(other)
        repository.createAccount(
            other, id(1500), CreateAccount(
                id(999), id(998), "다른 장부 합성 계좌",
                AccountNature.GENERAL, LocalDate.of(2026, 10, 31), "확인 전"
            )
        )
        sql.execSQL(
            "UPDATE accounts SET status='ARCHIVED' WHERE ledgerId=? AND id=?",
            arrayOf(context.ledgerId, id(3))
        )
        val cases = listOf(
            id(997) to LedgerConflict.MISSING_REFERENCE,
            id(999) to LedgerConflict.MISSING_REFERENCE, id(3) to LedgerConflict.ARCHIVED_ACCOUNT
        )
        for (active in listOf(false, true)) {
            for ((index, case) in cases.withIndex()) {
                val number = 20L + index + if (active) 10 else 0
                val operationId = id(100 + number)
                val original =
                    value(number, TransactionKind.INCOME).copy(creditAccountId = case.first)
                pending(operationId, original, active)
                val before = rows()
                val beforeRefs = dao.submissionReferences(context.ledgerId)
                val expected = PendingRecoveryItem.TransactionCreation(operationId, original)
                assertTrue(expected in repository.pendingRecovery(context).items)
                assertTrue(
                    (if (active) SubmissionRecoveryItem.Pending(
                        SubmissionReference(
                            context,
                            operationId
                        ), expected
                    )
                    else SubmissionRecoveryItem.LegacyPending(expected)) in repository.submissionRecovery(
                        context
                    ).items
                )
                assertEquals(before, rows())
                assertEquals(
                    case.second,
                    assertFailsWith<LedgerConflictException> {
                        repository.retryPending(
                            context,
                            operationId
                        )
                    }.conflict
                )
                assertEquals(before.take(6), rows().take(6))
                val expectedRefs = if (active) beforeRefs else (beforeRefs +
                        SubmissionReferenceEntity(
                            context.ledgerId,
                            operationId,
                            context.storageGeneration
                        )).sortedBy { it.operationId }
                assertEquals(expectedRefs, dao.submissionReferences(context.ledgerId))
                assertNull(dao.operation(context.ledgerId, operationId))
            }
        }
    }

    private fun changed(original: SubmissionEntity, raw: String) =
        original.copy(requestJson = raw, requestSha256 = requestHash(raw))

    private fun id(number: Long) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')
}
