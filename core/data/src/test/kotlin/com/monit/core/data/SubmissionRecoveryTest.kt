package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** R1–R12의 실제 파일/트랜잭션 경계. task 재생성과 기기 강제 종료 자체를 시험하지는 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SubmissionRecoveryTest {
    private lateinit var application: Application
    private lateinit var database: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val context = LedgerContext(id(900), id(901))
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-02T15:30:00Z"), ZoneOffset.UTC)
    private val oldTables =
        listOf("ledgers", "accounts", "anchors", "transactions", "operations", "submissions")
    private val allTables = oldTables + "submission_references"
    private val dao get() = database.ledgerDao()
    private val sql get() = database.openHelper.writableDatabase

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        application.deleteDatabase("discovery.db")
    }

    private fun reopen() {
        database = createLedgerDatabase(application, "discovery.db")
        repository = RoomLedgerRepository(database, clock, contexts)
    }

    private fun account(number: Long = 1) = CreateAccount(
        id(number),
        id(number + 1),
        "합성 계좌 $number",
        AccountNature.GENERAL,
        LocalDate.of(2026, 10, 31),
        "확인 전",
    )

    private suspend fun pending(
        operation: Long,
        value: CreateAccount = account(),
        reference: Boolean = true,
    ) {
        val raw = captureWrite(context, WriteCommand.Account(value))
        dao.insertSubmission(
            SubmissionEntity(
                context.ledgerId,
                id(operation),
                raw,
                requestHash(raw),
            ),
        )
        if (reference) dao.insertSubmissionReference(
            SubmissionReferenceEntity(
                context.ledgerId,
                id(operation),
                context.storageGeneration,
            ),
        )
    }

    private fun rows(tables: List<String> = allTables): List<List<List<String?>>> = tables.map { table ->
        sql.query("SELECT ${if (table == "transactions") "ledgerId,id,kind,date,amount,debitAccountId,creditAccountId,debitNature,creditNature,memo,revision,financialRevision" else "*"} FROM $table ORDER BY 1, 2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    (0 until cursor.columnCount).map {
                        cursor.getString(
                            it,
                        )
                    },
                )
            }
        }
    }

    /**
     * 목적: R1의 capture 오류가 pending만 남기지 않는다.
     * 조건: 기존 성공/참조가 있고 새 참조 INSERT만 SQLite trigger로 실패시킨다.
     * 절차: 새 계좌 명령을 실행하고 오류와 전후 일곱 테이블을 비교한다.
     * 기대: SQLite 오류가 전파되며 기존 원장·참조·revision 모두 동일하다.
     */
    @Test
    fun captureFailureRollsBackPendingAndPreservesOtherSubmissions() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val before = rows()
        sql.execSQL("CREATE TRIGGER fail_ref BEFORE INSERT ON submission_references BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        assertFailsWith<SQLiteException> { repository.createAccount(context, id(101), account(3)) }
        assertEquals(before, rows())
    }

    /**
     * 목적: R1의 실제 Job 취소가 capture의 pending과 참조를 함께 rollback한다.
     * 조건: Room 쿼리 관찰자가 참조 INSERT 시점에 호출 Job을 취소한다.
     * 절차: 실제 Default 스레드에서 저장을 실행하고 제한 시간 안에 Job 종료를 기다린다.
     * 기대: 관찰자가 실행됐고 Job은 취소됐으며 기존 일곱 테이블이 모두 그대로다.
     */
    @Test
    fun cancellationDuringCaptureRollsBackBothRows() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val before = rows()
        val job = AtomicReference<Job>()
        val observed = cancelOnQuery("INSERT OR ABORT INTO `submission_references`", job)
        val child = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            repository.createAccount(context, id(101), account(3))
        }
        job.set(child)
        child.start()
        withContext(Dispatchers.Default) { withTimeout(10_000) { child.join() } }
        assertTrue(observed.get())
        assertTrue(child.isCancelled)
        assertEquals(before, rows())
    }

    /**
     * 목적: R2의 capture commit 뒤 중단을 실제 두 단계 저장에서 검증한다.
     * 조건: 주입 Clock이 금융 트랜잭션의 성공 기록 직전에 CancellationException을 던진다.
     * 절차: 취소 전파 후 DB를 닫고 새 Repository에서 발견 목록과 원문을 조회한다.
     * 기대: 금융 행/성공은 rollback, 원래 pending·참조는 함께 남고 조회로 재시도하지 않는다.
     */
    @Test
    fun cancellationAfterCaptureReopensOriginalPendingWithoutFinancialWrite() = runTest {
        repository.initialize(context)
        val interruptedClock = object : Clock() {
            override fun getZone(): ZoneId = clock.zone
            override fun withZone(zone: ZoneId): Clock = clock.withZone(zone)
            override fun instant(): Instant = throw CancellationException("synthetic interruption")
        }
        val interrupted = RoomLedgerRepository(database, interruptedClock, contexts)
        assertFailsWith<CancellationException> {
            interrupted.createAccount(
                context,
                id(100),
                account(),
            )
        }
        database.close()
        reopen()
        val before = rows()
        val item =
            repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Pending
        assertEquals(SubmissionReference(context, id(100)), item.reference)
        assertEquals(PendingRecoveryItem.AccountRegistration(id(100), account()), item.submission)
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 1), before.map { it.size })
        assertEquals(before, rows())
    }

    /**
     * 목적: R3·R5·R7–R9의 성공 발견·선택 종료·재요청 멱등성을 확인한다.
     * 조건: 두 계좌 저장 응답을 UI에 보관하지 않고 DB를 재연다.
     * 절차: 참조로 영수증을 찾고 한 건만 종료/종료 재요청/같은 금융 요청 재전달한다.
     * 기대: 다른 참조와 금융 원장 보존, 종료 후 참조 부활 없음, 다른 요청은 충돌이다.
     */
    @Test
    fun lostSuccessResponseIsDiscoveredAndOnlySelectedReferenceFinishes() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        repository.createAccount(context, id(101), account(3))
        database.close()
        reopen()
        val before = rows()
        val discovered =
            repository.submissionRecovery(context).items.map { it as SubmissionRecoveryItem.Committed }
        assertEquals(listOf(id(100), id(101)), discovered.map { it.reference.operationId })
        assertEquals(listOf(2L, 3L), discovered.map { it.receipt.ledgerRevision })
        assertTrue(repository.pendingWrites(context).isEmpty())
        assertEquals(before, rows())
        val receipt = discovered.first().receipt
        assertEquals(
            FinishSubmissionRecoveryResult.Finished,
            repository.finishSubmissionRecovery(context, id(100)),
        )
        val after = rows()
        assertEquals(before.take(6), after.take(6))
        assertEquals(
            listOf(id(101)),
            dao.submissionReferences(context.ledgerId).map { it.operationId },
        )
        assertEquals(
            FinishSubmissionRecoveryResult.AlreadyInactive,
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertEquals(receipt, repository.createAccount(context, id(100), account()))
        val error = assertFailsWith<LedgerConflictException> {
            repository.createAccount(context, id(100), account().copy(name = "다른 의도"))
        }
        assertEquals(LedgerConflict.DUPLICATE_REQUEST, error.conflict)
        assertEquals(after, rows())
    }

    @Test
    fun pendingFinishAndReferenceWithoutEvidenceDoNotEraseOrRepairAnything() = runTest {
        repository.initialize(context)
        pending(100)
        dao.insertSubmissionReference(
            SubmissionReferenceEntity(
                context.ledgerId,
                id(101),
                context.storageGeneration,
            ),
        )
        val before = rows()
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.PENDING),
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.MISSING_RECORD),
            repository.finishSubmissionRecovery(context, id(101)),
        )
        assertFailsWith<IllegalStateException> {
            repository.createAccount(
                context,
                id(101),
                account(3),
            )
        }
        val items = repository.submissionRecovery(context).items
        assertTrue(items.first() is SubmissionRecoveryItem.Pending)
        assertEquals(
            SubmissionRecoveryItem.Blocked(
                SubmissionReference(context, id(101)),
                SubmissionRecoveryProblem.MISSING_RECORD,
            ),
            items.last(),
        )
        assertEquals(before, rows())
    }

    /**
     * 목적: R6의 원래 세대를 유지하고 현재 세대 재등록·종료를 막는다.
     * 조건: 기존 pending+참조를 남긴 채 메타의 세대만 교체한 손상/복원 상황이다.
     * 절차: 새 context로 조회·종료·동일 요청을 실행하고 이전 context도 조회한다.
     * 기대: 원래 참조를 노출하되 Blocked, 종료 NotReady, 쓰기/옛 context는 충돌이며 모든 행 보존.
     */
    @Test
    fun generationMismatchRetainsOriginalContextAndRejectsRetryAndFinish() = runTest {
        repository.initialize(context)
        pending(100)
        sql.execSQL("UPDATE ledgers SET storageGeneration = ?", arrayOf(id(902)))
        val replacement = LedgerContext(context.ledgerId, id(902))
        val before = rows()
        assertEquals(
            SubmissionRecoveryItem.Blocked(
                SubmissionReference(context, id(100)),
                SubmissionRecoveryProblem.GENERATION_MISMATCH,
            ),
            repository.submissionRecovery(replacement).items.single(),
        )
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.GENERATION_MISMATCH),
            repository.finishSubmissionRecovery(replacement, id(100)),
        )
        assertEquals(
            LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> {
                repository.createAccount(
                    replacement,
                    id(100),
                    account(),
                )
            }.conflict,
        )
        assertFailsWith<LedgerConflictException> { repository.submissionRecovery(context) }
        assertEquals(before, rows())
    }

    /**
     * 목적: R8의 종료 저장 실패가 성공으로 바뀌거나 참조를 잃지 않는다.
     * 조건: 검증된 성공과 참조가 있고 참조 DELETE trigger가 실패한다.
     * 절차: 종료 오류/전후 행 동일성을 확인한 뒤 trigger 제거 후 재요청한다.
     * 기대: 오류 전파·rollback, 이후 Finished이고 금융 원장은 동일하다.
     */
    @Test
    fun finishWriteFailureRollsBackAndCanBeRetried() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val before = rows()
        sql.execSQL("CREATE TRIGGER fail_finish BEFORE DELETE ON submission_references BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        assertFailsWith<SQLiteException> { repository.finishSubmissionRecovery(context, id(100)) }
        assertEquals(before, rows())
        sql.execSQL("DROP TRIGGER fail_finish")
        assertEquals(
            FinishSubmissionRecoveryResult.Finished,
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertEquals(before.take(6), rows().take(6))
    }

    /**
     * 목적: R8의 종료 취소가 활성 참조를 지우지 않는다.
     * 조건: 검증된 성공의 참조 DELETE 시점에 실제 호출 Job을 취소한다.
     * 절차: 종료 Job을 완료까지 기다린 뒤 DB를 재열어 참조와 금융 행을 비교한다.
     * 기대: 취소 전파 후 일곱 테이블이 모두 보존된다.
     */
    @Test
    fun cancellationDuringFinishPreservesReferenceAfterReopen() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val before = rows()
        val job = AtomicReference<Job>()
        val observed = cancelOnQuery("DELETE FROM submission_references", job)
        val child = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            repository.finishSubmissionRecovery(context, id(100))
        }
        job.set(child)
        child.start()
        withContext(Dispatchers.Default) { withTimeout(10_000) { child.join() } }
        assertTrue(observed.get())
        assertTrue(child.isCancelled)
        database.close()
        reopen()
        assertEquals(before, rows())
    }

    @Test
    fun emptyMissingDuplicateAndIoFailureAreNotInterchangeable() = runTest {
        repository.initialize(context)
        assertTrue(repository.submissionRecovery(context).items.isEmpty())
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.MISSING_RECORD),
            repository.finishSubmissionRecovery(context, id(100)),
        )
        repository.createAccount(context, id(100), account())
        pending(100, reference = false)
        val before = rows()
        assertEquals(
            SubmissionRecoveryProblem.CONFLICTING_RECORDS,
            (repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Blocked).reason,
        )
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.CONFLICTING_RECORDS),
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertFailsWith<IllegalStateException> { repository.retryPending(context, id(100)) }
        assertEquals(before, rows())
        sql.execSQL("DROP TABLE submissions")
        assertFailsWith<SQLiteException> { repository.submissionRecovery(context) }
        assertEquals(1, dao.submissionReferences(context.ledgerId).size)
    }

    @Test
    fun pendingKindsCorruptionAndInvalidIdentityAreBlockedWithoutMutatingRecords() = runTest {
        repository.initialize(context)
        pending(100)
        pending(101, reference = false)
        val original = dao.submission(context.ledgerId, id(100))!!
        val changed = listOf(
            original.copy(requestSha256 = "bad") to SubmissionRecoveryProblem.CORRUPTED,
            original.copy(
                requestJson = "{}",
                requestSha256 = requestHash("{}"),
            ) to SubmissionRecoveryProblem.CORRUPTED,
            original.requestJson.replace("\"version\":1", "\"version\":99").let {
                original.copy(
                    requestJson = it,
                    requestSha256 = requestHash(it),
                ) to SubmissionRecoveryProblem.UNSUPPORTED
            },
            original.requestJson.replace("UPDATE_ACCOUNT", "FUTURE_KIND").let {
                original.copy(
                    requestJson = it,
                    requestSha256 = requestHash(it),
                ) to SubmissionRecoveryProblem.UNSUPPORTED
            },
        )
        for ((record, reason) in changed) {
            sql.execSQL(
                "UPDATE submissions SET requestJson=?, requestSha256=? WHERE operationId=?",
                arrayOf(record.requestJson, record.requestSha256, id(100)),
            )
            val before = rows()
            assertEquals(
                reason,
                (repository.submissionRecovery(context).items.first() as SubmissionRecoveryItem.Blocked).reason,
            )
            assertEquals(
                FinishSubmissionRecoveryResult.NotReady(reason),
                repository.finishSubmissionRecovery(context, id(100)),
            )
            assertTrue(repository.submissionRecovery(context).items.last() is SubmissionRecoveryItem.LegacyPending)
            assertEquals(before, rows())
        }
        sql.execSQL("UPDATE submission_references SET operationId='bad-id'")
        assertEquals(
            SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED),
            repository.submissionRecovery(context).items.first(),
        )
    }

    @Test
    fun committedReceiptMustMatchCanonicalRequestKindGenerationAndTargetReferences() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val original = dao.operation(context.ledgerId, id(100))!!
        val variants = listOf(
            original.copy(resultJson = encodeRefs(listOf(EntityRef("accounts", id(88), 1)))),
            original.copy(kind = "CREATE_TRANSACTION"),
            original.copy(requestSha256 = "bad"),
            original.copy(requestJson = "{}", requestSha256 = requestHash("{}")),
            original.copy(storageGeneration = id(902)),
            original.copy(ledgerRevision = 999),
            original.copy(committedAt = "bad"),
            original.requestJson.replace("\"version\":1", "\"version\":99").let {
                original.copy(requestJson = it, requestSha256 = requestHash(it))
            },
        )
        for (record in variants) {
            sql.execSQL(
                "UPDATE operations SET resultJson=?,kind=?,requestJson=?,requestSha256=?,storageGeneration=?,ledgerRevision=?,committedAt=?",
                arrayOf<Any?>(
                    record.resultJson,
                    record.kind,
                    record.requestJson,
                    record.requestSha256,
                    record.storageGeneration,
                    record.ledgerRevision,
                    record.committedAt,
                ),
            )
            val before = rows()
            assertTrue(repository.submissionRecovery(context).items.single() is SubmissionRecoveryItem.Blocked)
            assertTrue(
                repository.finishSubmissionRecovery(
                    context,
                    id(100),
                ) is FinishSubmissionRecoveryResult.NotReady,
            )
            assertFailsWith<IllegalStateException> { repository.receipt(context, id(100)) }
            assertEquals(before, rows())
        }
    }

    @Test
    fun committedTransactionRemainsDiscoverableWithTypedPendingSupport() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        val receipt = repository.createTransaction(
            context,
            id(101),
            CreateTransaction(
                id(5),
                TransactionKind.INCOME,
                LocalDate.of(2026, 11, 1),
                15,
                creditAccountId = id(1),
            ),
        )
        val item =
            repository.submissionRecovery(context).items.last() as SubmissionRecoveryItem.Committed
        assertEquals(receipt, item.receipt)
        assertEquals(
            FinishSubmissionRecoveryResult.Finished,
            repository.finishSubmissionRecovery(context, id(101)),
        )
    }

    @Test
    fun missingAccountBlocksDiscoveryAndFinishWithoutErasingReference() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        removeTarget("accounts", id(1))
        assertMissingTargetIsBlocked(id(100))
    }

    /** 과거 영수증은 현재 객체의 revision·보관 상태를 덮어쓰거나 같다고 요구하지 않는다. */
    @Test
    fun laterEntityRevisionsPreserveTheHistoricalReceiptAndCurrentRows() = runTest {
        repository.initialize(context)
        val receipt = repository.createAccount(context, id(100), account())
        sql.execSQL("UPDATE accounts SET revision=2, status='ARCHIVED'")
        sql.execSQL("UPDATE anchors SET revision=2")
        sql.execSQL("UPDATE ledgers SET revision=3")
        val before = rows()
        val item =
            repository.submissionRecovery(context).items.single() as SubmissionRecoveryItem.Committed
        assertEquals(receipt, item.receipt)
        assertEquals(receipt, repository.createAccount(context, id(100), account()))
        assertEquals(before, rows())
        assertEquals(
            FinishSubmissionRecoveryResult.Finished,
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertEquals(before.take(6), rows().take(6))
    }

    @Test
    fun missingAnchorBlocksDiscoveryAndFinishWithoutErasingReference() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        removeTarget("anchors", id(2))
        assertMissingTargetIsBlocked(id(100))
    }

    @Test
    fun missingTransactionBlocksDiscoveryAndFinishWithoutErasingReference() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        repository.createTransaction(
            context,
            id(101),
            CreateTransaction(
                id(5),
                TransactionKind.INCOME,
                LocalDate.of(2026, 11, 1),
                15,
                creditAccountId = id(1),
            ),
        )
        removeTarget("transactions", id(5))
        assertMissingTargetIsBlocked(id(101))
    }

    /**
     * 목적: 종료 재요청의 AlreadyInactive도 검증된 성공이 있을 때만 반환한다.
     * 조건: 정상 종료 후 실제 금융 대상 행이 유실된 합성 손상 DB다.
     * 절차: 종료를 재요청하고 성공 명령도 같은 ID로 재전달한다.
     * 기대: NotReady(CORRUPTED)와 예외이며 참조 부활이나 다른 행 변경이 없다.
     */
    @Test
    fun inactiveReferenceWithMissingTargetIsNotAcknowledgedAsSuccess() = runTest {
        repository.initialize(context)
        repository.createAccount(context, id(100), account())
        assertEquals(
            FinishSubmissionRecoveryResult.Finished,
            repository.finishSubmissionRecovery(context, id(100)),
        )
        removeTarget("anchors", id(2))
        val before = rows()
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.CORRUPTED),
            repository.finishSubmissionRecovery(context, id(100)),
        )
        assertFailsWith<IllegalStateException> {
            repository.createAccount(
                context,
                id(100),
                account(),
            )
        }
        assertEquals(before, rows())
    }

    /** 실제 파일의 선택 대상만 제거하는 손상 fixture이며 제품 삭제 API가 아니다. */
    private fun removeTarget(table: String, targetId: String) {
        database.close()
        SQLiteDatabase.openDatabase(
            application.getDatabasePath("discovery.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use {
            it.execSQL("PRAGMA foreign_keys=OFF")
            it.execSQL(
                "DELETE FROM $table WHERE ledgerId=? AND id=?",
                arrayOf(context.ledgerId, targetId),
            )
        }
        reopen()
    }

    /** 재조회·종료·직접 영수증 확인·재시도 모두 손상을 숨기지 않으며 일곱 테이블을 보존한다. */
    private suspend fun assertMissingTargetIsBlocked(operationId: String) {
        val before = rows()
        val item = repository.submissionRecovery(context).items.single {
            when (it) {
                is SubmissionRecoveryItem.Committed -> it.reference.operationId == operationId
                is SubmissionRecoveryItem.Blocked -> it.reference?.operationId == operationId
                else -> false
            }
        }
        assertEquals(
            SubmissionRecoveryItem.Blocked(
                SubmissionReference(context, operationId),
                SubmissionRecoveryProblem.CORRUPTED,
            ),
            item,
        )
        assertEquals(
            FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.CORRUPTED),
            repository.finishSubmissionRecovery(context, operationId),
        )
        assertFailsWith<IllegalStateException> { repository.receipt(context, operationId) }
        assertFailsWith<IllegalStateException> { repository.retryPending(context, operationId) }
        assertEquals(before, rows())
    }

    @Test
    fun orphanReferencePreventsBootstrapWithOrWithoutOtherValidMetadata() = runTest {
        rows()
        database.close()
        SQLiteDatabase.openDatabase(
            application.getDatabasePath("discovery.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use {
            it.execSQL("PRAGMA foreign_keys=OFF")
            it.execSQL(
                "INSERT INTO submission_references VALUES (?,?,?)",
                arrayOf(context.ledgerId, id(100), context.storageGeneration),
            )
        }
        reopen()
        val before = rows()
        assertEquals(
            LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA),
            repository.openLedger(),
        )
        assertEquals(before, rows())
        repository.initialize(LedgerContext(id(902), id(903)))
        val withOther = rows()
        assertEquals(
            LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA),
            repository.openLedger(),
        )
        assertEquals(withOther, rows())
    }

    /**
     * 목적: R10의 실제 v1→v2 Migration이 원문/금융 기록을 보존한다.
     * 조건: 보존된 v1 schema JSON으로 파일을 생성하고 여섯 테이블에 합성 행을 넣는다.
     * 절차: Room v2로 열어 모든 옛 행을 비교하고 재열기 후 legacy pending을 선택 재시도한다.
     * 기대: Migration은 빈 참조만 추가, 과거 성공은 목록에 없고 재시도한 한 건만 참조 등록된다.
     */
    @Test
    fun versionOneFileMigratesWithoutRewritingRowsOrInventingHistoricalReferences() = runTest {
        database.close()
        val schema = JSONObject(
            java.io.File(
                System.getProperty("monit.roomSchemas"),
                "com.monit.core.data.LedgerDatabase/1.json",
            ).readText(),
        ).getJSONObject("database")
        val raw = captureWrite(context, WriteCommand.Account(account()))
        val pendingRaw = captureWrite(context, WriteCommand.Account(account(10)))
        val expected: List<List<List<String?>>>
        val file = application.getDatabasePath("discovery.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { fixture ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val name = entity.getString("tableName")
                fixture.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) fixture.execSQL(
                    indices.getJSONObject(
                        i,
                    ).getString("createSql").replace("\${TABLE_NAME}", name),
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) fixture.execSQL(setup.getString(i))
            fixture.execSQL(
                "INSERT INTO ledgers VALUES (?,?,3)",
                arrayOf(context.ledgerId, context.storageGeneration),
            )
            fixture.execSQL(
                "INSERT INTO accounts VALUES (?,?,?,'GENERAL','ACTIVE',1)",
                arrayOf(context.ledgerId, id(1), account().name),
            )
            fixture.execSQL(
                "INSERT INTO anchors VALUES (?,?,?,'2026-10-31',NULL,NULL,?,1)",
                arrayOf(context.ledgerId, id(2), id(1), account().unknownReason),
            )
            fixture.execSQL(
                "INSERT INTO transactions VALUES (?,?,'INCOME','2026-11-01',15,NULL,?,NULL,'GENERAL','',1,1)",
                arrayOf(context.ledgerId, id(5), id(1)),
            )
            fixture.execSQL(
                "INSERT INTO operations VALUES (?,?,?,'UPDATE_ACCOUNT',?,?,?,2,?)",
                arrayOf(
                    context.ledgerId,
                    id(100),
                    context.storageGeneration,
                    raw,
                    requestHash(raw),
                    clock.instant().toString(),
                    encodeRefs(
                        listOf(
                            EntityRef("accounts", id(1), 1),
                            EntityRef("anchors", id(2), 1),
                        ),
                    ),
                ),
            )
            fixture.execSQL(
                "INSERT INTO submissions VALUES (?,?,?,?)",
                arrayOf(context.ledgerId, id(101), pendingRaw, requestHash(pendingRaw)),
            )
            fixture.version = 1
            expected = oldTables.map { table ->
                fixture.rawQuery("SELECT * FROM $table ORDER BY 1, 2", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(
                            (0 until cursor.columnCount).map {
                                cursor.getString(
                                    it,
                                )
                            },
                        )
                    }
                }
            }
        }
        reopen()
        assertEquals(LedgerEntryResult.Ready(context), repository.openLedger())
        assertEquals(expected, rows(oldTables))
        assertTrue(dao.submissionReferences(context.ledgerId).isEmpty())
        database.close()
        reopen()
        assertEquals(expected, rows(oldTables))
        assertTrue(repository.submissionRecovery(context).items.single() is SubmissionRecoveryItem.LegacyPending)
        assertTrue(repository.pendingRecovery(context).items.single() is PendingRecoveryItem.AccountRegistration)
        repository.retryPending(context, id(101))
        assertEquals(
            listOf(id(101)),
            dao.submissionReferences(context.ledgerId).map { it.operationId },
        )
        assertTrue(repository.submissionRecovery(context).items.single() is SubmissionRecoveryItem.Committed)
        assertEquals(raw, dao.operation(context.ledgerId, id(100))!!.requestJson)
    }

    private fun id(number: Long) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

    /** 제품에 시험 hook을 추가하지 않고 실제 SQL 실행과 호출 Job 취소를 연결한다. */
    private fun cancelOnQuery(prefix: String, job: AtomicReference<Job>): AtomicBoolean {
        database.close()
        val observed = AtomicBoolean()
        database = Room.databaseBuilder(application, LedgerDatabase::class.java, "discovery.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(LedgerMigration1To2, LedgerMigration2To3, LedgerMigration3To4, LedgerMigration4To5)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .setQueryCallback({ query, _ ->
                if (query.startsWith(prefix)) {
                    observed.set(true)
                    job.get().cancel(CancellationException("synthetic SQL boundary"))
                }
            }, Executor { it.run() })
            .build()
        repository = RoomLedgerRepository(database, clock, contexts)
        return observed
    }
}
