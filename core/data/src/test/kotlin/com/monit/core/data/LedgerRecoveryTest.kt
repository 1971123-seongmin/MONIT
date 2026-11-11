package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.AccountNature
import com.monit.core.model.CorruptedSubmission
import com.monit.core.model.CreateAccount
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerEntryProblem
import com.monit.core.model.LedgerEntryResult
import com.monit.core.model.PendingRecoveryItem
import com.monit.core.model.UnsupportedSubmission
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

/** ER1·ER4·ER6의 Repository 경계. 제품 화면·기기 종료 시험을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LedgerRecoveryTest {
    private lateinit var application: Application
    private lateinit var database: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val context = LedgerContext(id(900), id(901))
    private val coroutineContexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-02T15:30:00Z"), ZoneOffset.UTC)
    private val tables = listOf("ledgers", "accounts", "anchors", "transactions", "operations", "submissions")

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        application.deleteDatabase("recovery.db")
    }

    private fun reopen() {
        database = createLedgerDatabase(application, "recovery.db")
        repository = RoomLedgerRepository(database, clock, coroutineContexts)
    }

    private fun value(name: String = "합성 계좌") = CreateAccount(
        id(1), id(2), name, AccountNature.GENERAL, LocalDate.of(2026, 10, 31), "확인 전",
    )

    private fun capture(value: CreateAccount = value()) = captureWrite(context, WriteCommand.Account(value))

    private suspend fun pending(number: Long, raw: String, hash: String = requestHash(raw)) {
        database.ledgerDao().insertSubmission(SubmissionEntity(context.ledgerId, id(number), raw, hash))
    }

    /** 원문까지 비교해 조회 전후의 모든 행이 동일한지 관찰한다. */
    private fun rows(): List<List<List<String?>>> = tables.map { table ->
        database.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
            }
        }
    }

    @Test
    fun emptyStoreCreatesOnlyMetadataAndReopensSameContext() = runTest {
        val first = repository.openLedger() as LedgerEntryResult.Ready
        assertEquals(first, repository.openLedger())
        assertEquals(listOf(1, 0, 0, 0, 0, 0), rows().map { it.size })
        assertEquals(1L, database.ledgerDao().entryLedgers().single().revision)
        database.close()
        reopen()
        assertEquals(first, repository.openLedger())
    }

    /**
     * 목적: 서로 다른 Room 인스턴스의 최초 진입도 같은 context로 수렴한다.
     * 조건: 같은 빈 파일 DB를 연 두 Repository와 실제 Default dispatcher를 사용한다.
     * 절차: 준비 gate에 두 호출이 도착하면 함께 해제하고 10초 안에 두 결과를 기다린다.
     * 기대: Ready 결과가 같고 메타는 한 행이며 업무 기록은 없다.
     */
    @Test
    fun concurrentFirstEntryAcrossConnectionsReturnsOneContext() = runTest {
        val other = createLedgerDatabase(application, "recovery.db")
        val start = CompletableDeferred<Unit>()
        val ready = List(2) { CompletableDeferred<Unit>() }
        try {
            val repositories = listOf(repository, RoomLedgerRepository(other, clock, coroutineContexts))
            val calls = repositories.mapIndexed { index, repo ->
                async(Dispatchers.Default) {
                    ready[index].complete(Unit)
                    start.await()
                    repo.openLedger()
                }
            }
            val results = withContext(Dispatchers.Default) { withTimeout(10_000) {
                ready.forEach { it.await() }
                start.complete(Unit)
                calls.awaitAll()
            } }
            assertTrue(results.first() is LedgerEntryResult.Ready)
            assertEquals(results.first(), results.last())
            assertEquals(listOf(1, 0, 0, 0, 0, 0), rows().map { it.size })
        } finally {
            start.complete(Unit)
            other.close()
        }
    }

    @Test
    fun invalidMetadataIsPreservedWithoutCreatingReplacement() = runTest {
        val invalid = listOf(
            LedgerEntity("bad-id", context.storageGeneration, 1),
            LedgerEntity(context.ledgerId, "bad-generation", 1),
            LedgerEntity(context.ledgerId, context.storageGeneration, 0),
        )
        for (row in invalid) {
            database.ledgerDao().insertLedger(row)
            val before = rows()
            assertEquals(LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.INVALID_METADATA), repository.openLedger())
            assertEquals(before, rows())
            database.openHelper.writableDatabase.execSQL("DELETE FROM ledgers")
        }
    }

    @Test
    fun multipleLedgersRequireRecoveryWithoutChoosingFirst() = runTest {
        repository.initialize(context)
        repository.initialize(LedgerContext(id(902), id(903)))
        val before = rows()
        assertEquals(LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MULTIPLE_LEDGERS), repository.openLedger())
        assertEquals(before, rows())
    }

    @Test
    fun orphanInEveryBusinessTablePreventsFirstEntryCreation() = runTest {
        // 손상 파일 합성 전용. FK를 끄는 제품 경로를 추가하지 않는다.
        rows() // Room 스키마를 먼저 생성한다.
        val statements = listOf(
            "INSERT INTO accounts VALUES ('x','a','합성','GENERAL','ACTIVE',1)",
            "INSERT INTO anchors VALUES ('x','a','b','2026-10-31',NULL,NULL,'확인 전',1)",
            "INSERT INTO transactions VALUES ('x','a','INCOME','2026-11-01',1,NULL,'b',NULL,'GENERAL','',1,1)",
            "INSERT INTO operations VALUES ('x','a','g','UPDATE_ACCOUNT','{}','h','2026-11-01T00:00:00Z',1,'[]')",
            "INSERT INTO submissions VALUES ('x','a','{}','h')",
        )
        statements.forEachIndexed { index, statement ->
            database.close()
            SQLiteDatabase.openDatabase(application.getDatabasePath("recovery.db").path, null,
                SQLiteDatabase.OPEN_READWRITE).use { fixture ->
                fixture.execSQL("PRAGMA foreign_keys=OFF")
                fixture.execSQL(statement)
            }
            reopen()
            val before = rows()
            assertEquals(LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA), repository.openLedger())
            assertEquals(before, rows())
            database.openHelper.writableDatabase.execSQL("DELETE FROM ${tables[index + 1]}")
        }
    }

    /**
     * 목적: 첫 생성 실패를 정상 진입으로 바꾸지 않고 다음 호출에서 회복한다.
     * 조건: 빈 DB의 메타 삽입 trigger가 SQLite 오류를 낸다.
     * 절차: 실패와 무기록을 확인하고 trigger 제거 후 다시 진입한다.
     * 기대: SQLiteException 전파, 실패 뒤 모든 테이블 0행, 재호출은 Ready다.
     */
    @Test
    fun failedCreationRollsBackAndNextEntryCanCreate() = runTest {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_entry BEFORE INSERT ON ledgers BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        assertFailsWith<SQLiteException> { repository.openLedger() }
        assertEquals(List(6) { 0 }, rows().map { it.size })
        sql.execSQL("DROP TRIGGER fail_entry")
        assertTrue(repository.openLedger() is LedgerEntryResult.Ready)
    }

    /**
     * 목적: 대기 중 취소된 최초 진입이 나중에 메타를 생성하지 않는다.
     * 조건: 실제 Room 트랜잭션을 gate로 점유하고 진입을 예약한다.
     * 절차: 점유 진입 확인 → 호출 시작 → 취소 완료 → 점유 해제 순서이며 대기는 10초로 제한한다.
     * 기대: 호출이 취소되고 점유 해제 후에도 모든 테이블이 비어 있다.
     */
    @Test
    fun cancelledEntryDoesNotCreateMetadata() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val called = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            database.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) {
                entered.await()
                val entry = launch(Dispatchers.Default) { called.complete(Unit); repository.openLedger() }
                called.await()
                entry.cancelAndJoin()
                assertTrue(entry.isCancelled)
                release.complete(Unit)
                holder.join()
            } }
            assertEquals(List(6) { 0 }, rows().map { it.size })
        } finally {
            release.complete(Unit)
            holder.cancelAndJoin()
        }
    }

    @Test
    fun typedProjectionPreservesLargeCanonicalPayloadAndAllRows() = runTest {
        repository.initialize(context)
        val value = value("\u0001".repeat(16_384))
        val raw = capture(value)
        assertTrue(raw.toByteArray().size > 16_384)
        pending(10, raw)
        val before = rows()
        val result = repository.pendingRecovery(context)
        assertEquals(context, result.context)
        assertEquals(listOf(PendingRecoveryItem.AccountRegistration(id(10), value)), result.items)
        assertEquals(before, rows())
    }

    @Test
    fun corruptAndUnsupportedRowsRemainVisibleAndUnchanged() = runTest {
        repository.initialize(context)
        val raw = capture()
        pending(10, raw, "bad-hash")
        pending(11, raw.replace(context.storageGeneration, id(999)))
        pending(12, "$raw ")
        pending(13, raw.replace("\"version\":1", "\"version\":2"))
        pending(14, raw.replace("UPDATE_ACCOUNT", "FUTURE_KIND"))
        pending(15, "{")
        pending(16, raw.replace("2026-10-31", "bad-date"))
        pending(17, raw.replace("\"version\":1", "\"version\":\"1\""))
        pending(18, raw.replace("\"targetId\":\"${id(1)}\"", "\"targetId\":\"invalid\""))
        pending(19, raw.replace("\"kind\":\"UPDATE_ACCOUNT\"", "\"kind\":\"CREATE_TRANSACTION\""))
        database.ledgerDao().insertSubmission(SubmissionEntity(context.ledgerId, "invalid", raw, requestHash(raw)))
        val before = rows()
        assertEquals(listOf(
            PendingRecoveryItem.Corrupted(id(10), CorruptedSubmission.HASH_MISMATCH),
            PendingRecoveryItem.Corrupted(id(11), CorruptedSubmission.CONTEXT_MISMATCH),
            PendingRecoveryItem.Corrupted(id(12), CorruptedSubmission.INVALID_PAYLOAD),
            PendingRecoveryItem.Unsupported(id(13), UnsupportedSubmission.VERSION),
            PendingRecoveryItem.Unsupported(id(14), UnsupportedSubmission.KIND),
            PendingRecoveryItem.Corrupted(id(15), CorruptedSubmission.INVALID_PAYLOAD),
            PendingRecoveryItem.Corrupted(id(16), CorruptedSubmission.INVALID_PAYLOAD),
            PendingRecoveryItem.Corrupted(id(17), CorruptedSubmission.INVALID_PAYLOAD),
            PendingRecoveryItem.Corrupted(id(18), CorruptedSubmission.INVALID_PAYLOAD),
            PendingRecoveryItem.Unsupported(id(19), UnsupportedSubmission.KIND),
            PendingRecoveryItem.Corrupted(null, CorruptedSubmission.INVALID_OPERATION_ID),
        ), repository.pendingRecovery(context).items)
        assertEquals(before, rows())
    }

    @Test
    fun onlySuccessfulEmptyQueryReturnsEmptyList() = runTest {
        repository.initialize(context)
        assertEquals(emptyList(), repository.pendingRecovery(context).items)
        val wrongContext = context.copy(storageGeneration = id(999))
        assertEquals(LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> { repository.pendingRecovery(wrongContext) }.conflict)
        database.openHelper.writableDatabase.execSQL("DROP TABLE submissions")
        assertFailsWith<SQLiteException> { repository.pendingRecovery(context) }
        // 메타 조회 실패 역시 Ready로 바꾸지 않는다.
        database.openHelper.writableDatabase.execSQL("DROP TABLE ledgers")
        assertFailsWith<SQLiteException> { repository.openLedger() }
    }

    /**
     * 목적: typed 조회가 제출을 소비하지 않으며 기존 재시도 경계가 소비를 담당한다.
     * 조건: 유효한 계좌 생성 캡처 한 건을 저장한 실제 Room DB다.
     * 절차: 조회 → 기존 receipt 없음 확인 → 같은 ID 재시도 → 재조회·재시도한다.
     * 기대: 조회 때 원문 유지, 성공 후 빈 목록, 반복 재시도는 원래 영수증이며 revision이 같다.
     */
    @Test
    fun existingRetryConsumesInspectedSubmissionAndKeepsOriginalReceipt() = runTest {
        repository.initialize(context)
        pending(10, capture())
        assertEquals(listOf(PendingRecoveryItem.AccountRegistration(id(10), value())),
            repository.pendingRecovery(context).items)
        assertEquals(null, repository.receipt(context, id(10)))
        val receipt = repository.retryPending(context, id(10))
        assertEquals(emptyList(), repository.pendingRecovery(context).items)
        assertEquals(receipt, repository.retryPending(context, id(10)))
        assertEquals(receipt.ledgerRevision, database.ledgerDao().ledger(context.ledgerId)?.revision)
    }

    private companion object {
        fun id(number: Long): String = UUID(0, number).toString()
    }
}
