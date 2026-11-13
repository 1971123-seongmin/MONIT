package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.AccountNature
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.TransactionKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 실제 Room 생성 DAO와 파일 SQLite를 쓰는 JVM 통합 시험. Android 기기/전원 손실 시험과 구분한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RoomLedgerRepositoryTest {
    private lateinit var androidContext: Application
    private lateinit var database: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private lateinit var databaseName: String
    private val context = LedgerContext(id(900), id(901))
    private val coroutineContexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val today = LocalDate.of(2026, 11, 3)

    // UTC로는 전날이다. Repository가 서울 날짜를 사용하는지도 함께 검증한다.
    private val clock = Clock.fixed(Instant.parse("2026-11-02T15:30:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = runBlocking {
        androidContext = RuntimeEnvironment.getApplication()
        // Robolectric의 테스트별 디렉터리로 격리한다. Windows SQLite 경로 길이를 불필요하게 늘리지 않는다.
        databaseName = "ledger.db"
        reopen()
        repository.initialize(context)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::androidContext.isInitialized && ::databaseName.isInitialized) androidContext.deleteDatabase(databaseName)
    }

    private fun reopen() {
        database = createLedgerDatabase(androidContext, databaseName)
        repository = RoomLedgerRepository(database, clock, coroutineContexts)
    }

    private fun accountValue(number: Long, name: String = "같은 이름") = CreateAccount(
        id(number),
        id(number + 50),
        name,
        AccountNature.GENERAL,
        LocalDate.of(2026, 10, 31),
        "사용자 확인 전",
    )

    private suspend fun account(number: Long): CreateAccount = accountValue(number).also {
        repository.createAccount(context, id(100 + number), it)
    }

    /** 실제 Room 파일에 세 성격을 등록하고 재열어 원래 성격/미확인 기준액/기준일을 읽는다. 기존 계좌 변경은 없다. */
    @Test fun newlyRegisteredAccountNaturesSurviveDatabaseReopen() = runBlocking {
        val values = AccountNature.entries.mapIndexed { index, nature -> accountValue(index + 1L).copy(nature = nature) }
        values.forEachIndexed { index, value -> repository.createAccount(context, id(index + 101L), value) }
        database.close()
        reopen()
        val snapshot = repository.snapshot(context, today)
        assertEquals(3, snapshot.accounts.size)
        for (value in values) {
            val stored = snapshot.accounts.single { it.id == value.id }
            assertEquals(value.nature, stored.nature)
            assertEquals(value.anchorDate, stored.anchor.date)
            assertNull(stored.anchor.amount)
        }
        assertTrue(snapshot.transactions.isEmpty())
    }

    private fun transfer(number: Long = 10) = CreateTransaction(
        id(number),
        TransactionKind.TRANSFER,
        today,
        50,
        id(1),
        id(2),
        "이체",
    )

    /** 조회 계산용 합성 기준 fixture다. 제품 CONFIRM_ANCHOR API 구현을 뜻하지 않는다. */
    private fun confirmedFixture(account: Long, amount: Long) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE anchors SET amount=?, confirmedAt=?, unknownReason=NULL WHERE ledgerId=? AND accountId=?",
            arrayOf<Any>(amount, Instant.EPOCH.toString(), context.ledgerId, id(account)),
        )
    }

    /** 계좌/미확인 기준/성공 결과가 함께 저장되고 동명 계좌는 서로 다른 ID로 보존된다. */
    @Test
    fun createsAccountsWithUnknownAnchorAndDistinctIdentity() = runTest {
        val first = accountValue(1, "새 \"계좌\"\\\n💰")
        val receipt = repository.createAccount(context, id(101), first)
        repository.createAccount(context, id(102), accountValue(2, first.name))
        val snapshot = repository.snapshot(context, today)
        assertEquals(2, snapshot.accounts.size)
        assertEquals(first.name, snapshot.accounts.first { it.id == first.id }.name)
        assertEquals(mapOf(id(1) to null, id(2) to null), snapshot.balances)
        assertTrue(snapshot.accounts.all { it.anchor.amount == null && it.anchor.confirmedAt == null })
        assertEquals(setOf("accounts", "anchors"), receipt.entities.map { it.collection }.toSet())
        assertEquals(2L, receipt.ledgerRevision)
        assertEquals(3L, snapshot.revision)
        assertTrue(repository.pendingWrites(context).isEmpty())
    }

    /**
     * 목적: 확정 의도의 재실행을 중복 저장과 구별한다. 조건: 같은 ID/payload와 다른 payload.
     * 절차: 첫 확정 뒤 다른 계좌를 저장하고 원래 요청을 재실행한다.
     * 기대: 과거 receipt가 그대로 반환되고 판은 추가 증가하지 않으며 다른 payload는 충돌이다.
     */
    @Test
    fun sameOperationReplaysOriginalResultWithoutAnotherRevision() = runTest {
        val value = accountValue(1)
        val first = repository.createAccount(context, id(101), value)
        account(2)
        assertEquals(first, repository.createAccount(context, id(101), value))
        assertEquals(first, repository.receipt(context, id(101)))
        assertEquals(3L, repository.snapshot(context, today).revision)
        assertEquals(
            LedgerConflict.DUPLICATE_REQUEST,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(context, id(101), value.copy(name = "변경"))
            }.conflict,
        )
    }

    /** 이체는 한 행이며 두 계좌 효과·수입/지출·기준일 경계를 같은 스냅샷에서 계산한다. */
    @Test
    fun transferKeepsBothEffectsAndExcludesPrincipalFromIncomeExpense() = runTest {
        account(1)
        account(2)
        confirmedFixture(1, 10_000)
        confirmedFixture(2, 20_000)
        repository.createTransaction(context, id(210), transfer())
        repository.createTransaction(context, id(211), CreateTransaction(id(11), TransactionKind.INCOME, today, 1_000, creditAccountId = id(1)))
        repository.createTransaction(context, id(212), CreateTransaction(id(12), TransactionKind.EXPENSE, today, 200, debitAccountId = id(1)))
        repository.createTransaction(context, id(213), CreateTransaction(id(13), TransactionKind.INCOME, LocalDate.of(2026, 10, 31), 999, creditAccountId = id(1)))
        val snapshot = repository.snapshot(context, today)
        assertEquals(4, snapshot.transactions.size)
        assertEquals(1, snapshot.transactions.count { it.kind == TransactionKind.TRANSFER })
        assertEquals(mapOf(id(1) to 10_750L, id(2) to 20_050L), snapshot.balances)
        assertEquals(1_000L, snapshot.income)
        assertEquals(200L, snapshot.expense)
        assertEquals(999L, repository.snapshot(context, LocalDate.of(2026, 10, 31)).income)
        assertEquals(10_000L, repository.snapshot(context, LocalDate.of(2026, 10, 31)).balances[id(1)])
    }

    /** 확인한 0원은 유효한 기준이며 잔액 부족을 은행처럼 판단하지 않고 음수 잔액을 허용한다. */
    @Test
    fun confirmedZeroCanBecomeNegative() = runTest {
        account(1)
        confirmedFixture(1, 0)
        repository.createTransaction(context, id(201), CreateTransaction(id(10), TransactionKind.EXPENSE, today, 500, debitAccountId = id(1)))
        assertEquals(-500L, repository.snapshot(context, today).balances[id(1)])
    }

    /**
     * 목적: 미확정 의도 복구를 검증한다. 조건: 입금 계좌가 아직 없다.
     * 절차: 이체 저장 실패 확인 후 계좌를 생성하고 같은 의도를 retryPending한다.
     * 기대: 실패 시 금융 변경/성공 원장 0, 의도 1개 보존 후 거래 1개로 확정·소비된다.
     */
    @Test
    fun missingReferencePreservesSubmissionForRetry() = runTest {
        account(1)
        assertEquals(
            LedgerConflict.MISSING_REFERENCE,
            assertFailsWith<LedgerConflictException> {
                repository.createTransaction(context, id(210), transfer())
            }.conflict,
        )
        assertEquals(2L, repository.snapshot(context, today).revision)
        assertTrue(repository.snapshot(context, today).transactions.isEmpty())
        assertNull(repository.receipt(context, id(210)))
        assertEquals(listOf(id(210)), repository.pendingWrites(context).map { it.operationId })
        account(2)
        val retried = repository.retryPending(context, id(210))
        assertEquals(id(10), retried.entities.single().id)
        assertTrue(repository.pendingWrites(context).isEmpty())
        assertEquals(retried, repository.retryPending(context, id(210)))
    }

    /** 다른 가계부에만 존재하는 같은 ID를 참조해도 현재 가계부에서 계좌를 찾은 것으로 처리하지 않는다. */
    @Test
    fun referencesCannotCrossLedgerBoundary() = runTest {
        account(1)
        val other = LedgerContext(id(800), id(801))
        repository.initialize(other)
        repository.createAccount(other, id(102), accountValue(2))
        assertEquals(
            LedgerConflict.MISSING_REFERENCE,
            assertFailsWith<LedgerConflictException> {
                repository.createTransaction(context, id(210), transfer())
            }.conflict,
        )
        assertTrue(repository.snapshot(context, today).transactions.isEmpty())
        assertTrue(repository.snapshot(other, today).transactions.isEmpty())
    }

    /** 보관 계좌로 신규 저장은 막지만 과거 참조와 거래 당시 성격은 현재 설정 변경 후에도 유지한다. */
    @Test
    fun archivedReferenceIsRejectedAndHistoricalNatureIsPreserved() = runTest {
        account(1)
        account(2)
        repository.createTransaction(context, id(210), transfer())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE accounts SET status='ARCHIVED', nature='INVESTMENT' WHERE ledgerId=? AND id=?",
            arrayOf(context.ledgerId, id(1)),
        )
        assertEquals(
            LedgerConflict.ARCHIVED_ACCOUNT,
            assertFailsWith<LedgerConflictException> {
                repository.createTransaction(context, id(211), transfer(11))
            }.conflict,
        )
        val snapshot = repository.snapshot(context, today)
        assertEquals(1, snapshot.transactions.size)
        assertEquals(AccountNature.GENERAL, snapshot.transactions.single().debitNature)
        assertEquals(AccountNature.INVESTMENT, snapshot.accounts.first { it.id == id(1) }.nature)
    }

    /** 다른 명령 ID로 기존 객체 ID를 덮어쓰지 않으며 이미 확정한 결과는 보존한다. */
    @Test
    fun duplicateEntityCannotBeReplaced() = runTest {
        val first = repository.createAccount(context, id(101), accountValue(1))
        assertEquals(
            LedgerConflict.DUPLICATE_ENTITY,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(context, id(201), accountValue(1).copy(name = "덮어쓰기"))
            }.conflict,
        )
        assertEquals(
            LedgerConflict.DUPLICATE_ENTITY,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(context, id(202), accountValue(2).copy(anchorId = id(51)))
            }.conflict,
        )
        assertEquals(first, repository.receipt(context, id(101)))
        assertEquals(listOf("같은 이름"), repository.snapshot(context, today).accounts.map { it.name })
        assertEquals(2L, repository.snapshot(context, today).revision)
    }

    /**
     * 목적: 거래와 성공 원장의 원자성을 검증한다. 조건: 성공 원장 INSERT에 실패 trigger.
     * 절차: 이체 저장을 호출하고 실패 상태를 확인한 후 trigger를 제거해 같은 의도를 재시도한다.
     * 기대: 첫 금융 변경/판 증가는 0이며 원래 의도로 거래 1개만 확정된다.
     */
    @Test
    fun operationFailureRollsBackTransferAndRevision() = runTest {
        account(1)
        account(2)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_operation BEFORE INSERT ON operations
            WHEN NEW.kind='CREATE_TRANSACTION'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
            """.trimIndent(),
        )
        assertFailsWith<SQLiteException> { repository.createTransaction(context, id(210), transfer()) }
        val snapshot = repository.snapshot(context, today)
        assertTrue(snapshot.transactions.isEmpty())
        assertEquals(3L, snapshot.revision)
        assertNull(repository.receipt(context, id(210)))
        assertEquals(1, repository.pendingWrites(context).size)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_operation")
        repository.retryPending(context, id(210))
        assertEquals(1, repository.snapshot(context, today).transactions.size)
    }

    /**
     * 목적: 입력별 16 KiB와 더 큰 캡처 JSON을 구분하고 원자성·재열기 복구를 검증한다.
     * 조건: Unicode 접두사와 제어 문자를 포함한 이름·사유가 각각 UTF-8 16 KiB이며 실패 trigger가 있다.
     * 절차: 저장 실패 확인 후 trigger 제거·DB 닫기/재열기·같은 캡처 재시도.
     * 기대: 실패 시 계좌/기준 0, 16 KiB를 넘는 원문·hash 보존, 재시도 후 원래 값의 계좌 1개·같은 receipt다.
     */
    @Test
    fun failedAccountAndItsAnchorRollbackAndRecoverAfterReopen() = runTest {
        val prefix = "계좌 \"한글\"\\\n💰"
        val name = prefix + "\u0001".repeat(16 * 1024 - prefix.toByteArray(Charsets.UTF_8).size)
        val value = accountValue(1, name).copy(unknownReason = "\u0002".repeat(16 * 1024))
        assertEquals(16 * 1024, value.name.toByteArray(Charsets.UTF_8).size)
        assertEquals(16 * 1024, value.unknownReason.toByteArray(Charsets.UTF_8).size)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_account BEFORE INSERT ON operations
            WHEN NEW.kind='UPDATE_ACCOUNT'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
            """.trimIndent(),
        )
        assertFailsWith<SQLiteException> { repository.createAccount(context, id(101), value) }
        assertTrue(database.ledgerDao().accounts(context.ledgerId).isEmpty())
        assertTrue(database.ledgerDao().anchors(context.ledgerId).isEmpty())
        assertEquals(1L, repository.snapshot(context, today).revision)
        val captured = repository.pendingWrites(context).single().requestJson
        assertTrue(captured.toByteArray(Charsets.UTF_8).size > 16 * 1024)
        val capturedHash = requestHash(captured)
        assertEquals(capturedHash, database.ledgerDao().submission(context.ledgerId, id(101))?.requestSha256)
        assertEquals(value, (decodeWrite(context, captured) as WriteCommand.Account).value)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_account")
        database.close()
        reopen()
        val receipt = repository.retryPending(context, id(101))
        val restored = repository.snapshot(context, today)
        assertEquals(value.name, restored.accounts.single().name)
        assertEquals(value.unknownReason, restored.accounts.single().anchor.unknownReason)
        assertEquals(2L, restored.revision)
        val operation = requireNotNull(database.ledgerDao().operation(context.ledgerId, id(101)))
        assertEquals(captured, operation.requestJson)
        assertEquals(capturedHash, operation.requestSha256)
        assertEquals(receipt, repository.retryPending(context, id(101)))
        assertEquals(1, repository.snapshot(context, today).accounts.size)
        assertTrue(repository.pendingWrites(context).isEmpty())
    }

    /**
     * 목적: commit 뒤 응답 유실의 재시도를 검증한다. 조건: 이미 저장된 계좌 생성 의도.
     * 절차: 첫 결과를 기준으로 DB를 닫고 재열기한 뒤 같은 명령을 호출한다.
     * 기대: 같은 receipt, 계좌 1개, 추가 판 증가 0이며 새 성공 저장으로 처리하지 않는다.
     */
    @Test
    fun committedRequestReplaysAfterReopen() = runTest {
        val value = accountValue(1)
        val first = repository.createAccount(context, id(101), value)
        database.close()
        reopen()
        assertEquals(first, repository.createAccount(context, id(101), value))
        assertEquals(1, repository.snapshot(context, today).accounts.size)
        assertEquals(2L, repository.snapshot(context, today).revision)
    }

    /**
     * 목적: 동일 의도 동시 실행의 중복을 차단한다. 조건: 실제 Room writer에 같은 ID/payload 두 요청.
     * 절차: 두 작업의 준비 신호를 제한 시간 안에 받고 공통 gate를 해제한 뒤 모두 await한다.
     * 기대: 같은 영속 결과·거래 한 건·판 증가 한 번이며 별도 의도는 별도 거래가 된다.
     */
    @Test
    fun concurrentSameRequestCommitsOnceAndDifferentIntentRemainsSeparate() = runTest {
        account(1)
        account(2)
        val results = coroutineScope {
            val ready = Channel<Unit>(2)
            val start = CompletableDeferred<Unit>()
            try {
                val jobs = List(2) {
                    async(Dispatchers.Default) {
                        ready.send(Unit)
                        start.await()
                        repository.createTransaction(context, id(210), transfer())
                    }
                }
                withContext(Dispatchers.Default) { withTimeout(10_000) { repeat(2) { ready.receive() } } }
                start.complete(Unit)
                jobs.awaitAll()
            } finally {
                start.complete(Unit)
                ready.close()
            }
        }
        assertEquals(results[0], results[1])
        assertEquals(1, repository.snapshot(context, today).transactions.size)
        assertEquals(4L, repository.snapshot(context, today).revision)
        repository.createTransaction(context, id(211), transfer(11))
        assertEquals(2, repository.snapshot(context, today).transactions.size)
        assertEquals(5L, repository.snapshot(context, today).revision)
    }

    /**
     * 목적: 저장 중 취소의 SQLite rollback을 검증한다. 조건: 유효 계좌·거래 삽입 후 commit 전 gate.
     * 절차: 시작 신호를 제한 시간 안에 확인하고 자식 작업을 cancelAndJoin한다.
     * 기대: 거래가 남지 않는다. gate는 finally에서 해제하고 취소를 예외 삼킴으로 성공 처리하지 않는다.
     */
    @Test
    fun cancellationBeforeCommitRollsBackInsertedRow() = runTest {
        account(1)
        account(2)
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val child = launch(Dispatchers.Default) {
            database.withTransaction {
                database.ledgerDao().insertTransaction(
                    TransactionEntity(
                        context.ledgerId, id(10),
                        "TRANSFER", today.toString(), 50, id(1), id(2), "GENERAL", "GENERAL", "", 1, 1,
                    ),
                )
                inserted.complete(Unit)
                release.await()
            }
        }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { inserted.await() } }
            child.cancelAndJoin()
            assertTrue(child.isCancelled)
            assertTrue(repository.snapshot(context, today).transactions.isEmpty())
        } finally {
            child.cancelAndJoin()
            release.complete(Unit)
        }
    }

    /** DB의 실제 FK도 참조 없는 거래를 거부하며 Repository 검사만 신뢰하지 않는다. */
    @Test
    fun sqliteForeignKeyRejectsMissingAccount() = runTest {
        account(1)
        assertFailsWith<SQLiteException> {
            database.ledgerDao().insertTransaction(
                TransactionEntity(
                    context.ledgerId, id(10),
                    "TRANSFER", today.toString(), 50, id(1), id(2), "GENERAL", "GENERAL", "", 1, 1,
                ),
            )
        }
        assertTrue(repository.snapshot(context, today).transactions.isEmpty())
    }

    /** writer 내구성 설정이 낮아졌으면 금융 변경뿐 아니라 새 제출 의도도 기록하지 않는다. */
    @Test
    fun refusesWriterWhenFullSynchronousSettingIsLost() = runTest {
        database.openHelper.writableDatabase.execSQL("PRAGMA synchronous=NORMAL")
        assertFailsWith<IllegalStateException> { repository.createAccount(context, id(101), accountValue(1)) }
        assertTrue(database.ledgerDao().accounts(context.ledgerId).isEmpty())
        assertTrue(repository.pendingWrites(context).isEmpty())
    }

    /** 판 증가가 불가능한 조건부 갱신 0행을 성공으로 바꾸지 않고 계좌/기준도 rollback한다. */
    @Test
    fun revisionExhaustionRollsBackAllFinancialRows() = runTest {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ledgers SET revision=? WHERE id=?",
            arrayOf<Any>(Long.MAX_VALUE, context.ledgerId),
        )
        assertEquals(
            LedgerConflict.STALE_REVISION,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(context, id(101), accountValue(1))
            }.conflict,
        )
        assertTrue(database.ledgerDao().accounts(context.ledgerId).isEmpty())
        assertTrue(database.ledgerDao().anchors(context.ledgerId).isEmpty())
        assertNull(repository.receipt(context, id(101)))
        assertEquals(Long.MAX_VALUE, repository.snapshot(context, today).revision)
    }

    /**
     * 목적: 세대 경계와 operationId 수명을 구별한다. 조건: 계좌 저장 후 합성 세대 전환.
     * 절차: 옛 context 조회/재시도와 새 context에서 같은 ID 재사용을 호출한다.
     * 기대: 옛 호출은 stale, 새 payload는 충돌이며 기존 계좌는 1개로 보존된다.
     */
    @Test
    fun generationFenceRejectsOldCallsAndOperationReuse() = runTest {
        account(1)
        val changed = context.copy(storageGeneration = id(902))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ledgers SET storageGeneration=? WHERE id=?",
            arrayOf(changed.storageGeneration, context.ledgerId),
        )
        assertEquals(
            LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> {
                repository.receipt(context, id(101))
            }.conflict,
        )
        assertEquals(
            LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> {
                repository.retryPending(context, id(101))
            }.conflict,
        )
        assertEquals(
            LedgerConflict.DUPLICATE_REQUEST,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(changed, id(101), accountValue(1))
            }.conflict,
        )
        assertEquals(1, repository.snapshot(changed, today).accounts.size)
    }

    /** 서울 오늘 이후/하한 이전 실제일은 저장하지 않으며 미확정 요청과 금융 성공을 구분한다. */
    @Test
    fun invalidActualDatesNeverCommitTransactions() = runTest {
        account(1)
        for ((number, date) in listOf(10L to today.plusDays(1), 11L to LocalDate.of(1899, 12, 31))) {
            assertFailsWith<IllegalArgumentException> {
                repository.createTransaction(context, id(200 + number), CreateTransaction(id(number), TransactionKind.INCOME, date, 10, creditAccountId = id(1)))
            }
            assertNull(repository.receipt(context, id(200 + number)))
        }
        assertTrue(repository.snapshot(context, today).transactions.isEmpty())
        assertEquals(2L, repository.snapshot(context, today).revision)
        assertEquals(2, repository.pendingWrites(context).size)
    }

    companion object {
        private fun id(number: Long): String = UUID(0, number).toString()
    }
}
