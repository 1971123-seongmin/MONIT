package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.io.File
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
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 합성자료의 실제 파일 Room 검증. 단말 전원 중단 검증은 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TransactionChangesTest {
    private val context = LedgerContext(id(900), id(901))
    private val day = LocalDate.of(2026, 11, 11)
    private val clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var repository: RoomLedgerRepository
    private val dao get() = db.ledgerDao()
    private val sql get() = db.openHelper.writableDatabase
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private fun original() = CreateTransaction(id(30), TransactionKind.TRANSFER, day, 100, id(10), id(20), "합성 원문")
    private fun reopen() {
        db = createLedgerDatabase(app, "changes.db")
        repository = RoomLedgerRepository(db, clock, contexts)
    }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After fun teardown() {
        db.close()
        app.deleteDatabase("changes.db")
    }
    private suspend fun ready() {
        repository.initialize(context)
        for (n in listOf(10, 20)) {
            repository.createAccount(context, id(n + 100), CreateAccount(id(n), id(n + 1), "합성 계좌 $n", AccountNature.GENERAL, day.minusDays(1), "미확인"))
            sql.execSQL("UPDATE anchors SET amount=1000, confirmedAt=?, unknownReason=NULL WHERE id=?", arrayOf(clock.instant().toString(), id(n + 1)))
        }
        repository.createTransaction(context, id(130), original())
    }

    /** 메모 수정은 객체 revision만 증가하고 금융 snapshot과 원래 영수증은 유지한다. */
    @Test fun memoOnlyPreservesFinancialRevisionAndOriginalReceipt() = runTest {
        ready()
        val before = repository.snapshot(context, day)
        val originalReceipt = repository.receipt(context, id(130))
        val receipt = repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "수정 메모"), 1))
        val after = repository.snapshot(context, day)
        assertEquals(2, after.transactions.single().revision)
        assertEquals(1, after.transactions.single().financialRevision)
        assertEquals(before.balances, after.balances)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(EntityRef("transactions", id(30), 2), receipt.entities.single())
        assertEquals(original().memo, dao.transactionHistory(context.ledgerId, id(30)).single().before.memo)
        assertEquals(originalReceipt, repository.receipt(context, id(130)))
    }

    /** 이체 변경은 양쪽 잔액·이전값·영수증을 한 번만 변경한다. */
    @Test fun transferUpdateAndLostReplyReplayAreAtomicAndIdempotent() = runTest {
        ready()
        val change = UpdateTransaction(original().copy(amount = 250), 1)
        val first = repository.updateTransaction(context, id(140), change)
        db.close()
        reopen()
        assertEquals(first, repository.updateTransaction(context, id(140), change))
        assertEquals(first, repository.retryPending(context, id(140)))
        val after = repository.snapshot(context, day)
        assertEquals(mapOf(id(10) to 750L, id(20) to 1250L), after.balances)
        assertEquals(0, after.income)
        assertEquals(0, after.expense)
        assertEquals(2, after.transactions.single().financialRevision)
        assertEquals(100, dao.transactionHistory(context.ledgerId, id(30)).single().before.amount)
    }

    /** 삭제는 원행·이력·과거 영수증을 보존하고 이체 양쪽 효과만 제거한다. */
    @Test fun deleteKeepsRowsAndReceiptsButRemovesAllFinancialEffects() = runTest {
        ready()
        val old = repository.receipt(context, id(130))
        val command = DeleteTransaction(id(30), 1)
        val deleted = repository.deleteTransaction(context, id(150), command)
        db.close()
        reopen()
        assertEquals(deleted, repository.deleteTransaction(context, id(150), command))
        assertEquals(old, repository.receipt(context, id(130)))
        assertEquals(old, repository.createTransaction(context, id(130), original()))
        assertTrue(repository.snapshot(context, day).transactions.isEmpty())
        assertEquals(mapOf(id(10) to 1000L, id(20) to 1000L), repository.snapshot(context, day).balances)
        val row = assertNotNull(dao.transaction(context.ledgerId, id(30)))
        assertEquals(clock.instant().toString(), row.deletedAt)
        assertEquals(2, row.revision)
        assertEquals(2, row.financialRevision)
        assertEquals(100, row.amount)
        assertEquals(id(150), dao.transactionHistory(context.ledgerId, id(30)).single().operationId)
        assertTrue(repository.submissionRecovery(context).items.all { it is SubmissionRecoveryItem.Committed })
    }

    /** stale/new operation과 동일 operation의 다른 원문 모두 금융 변경 없이 거부한다. */
    @Test fun staleRevisionAndDifferentPayloadDoNotOverwriteOrResurrect() = runTest {
        ready()
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "수정"), 1))
        assertEquals(
            1,
            assertFailsWith<TransactionRejectedException> {
                repository.deleteTransaction(context, id(150), DeleteTransaction(id(30), 1))
            }.rejection.expectedRevision,
        )
        assertEquals(
            LedgerConflict.DUPLICATE_REQUEST,
            assertFailsWith<LedgerConflictException> {
                repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(amount = 200), 1))
            }.conflict,
        )
        repository.deleteTransaction(context, id(151), DeleteTransaction(id(30), 2))
        assertEquals(
            3,
            assertFailsWith<TransactionRejectedException> {
                repository.updateTransaction(context, id(152), UpdateTransaction(original(), 3))
            }.rejection.expectedRevision,
        )
        assertEquals(2, dao.transactionHistory(context.ledgerId, id(30)).size)
        assertEquals(3, dao.transaction(context.ledgerId, id(30))!!.revision)
    }

    /** 날짜·종류 변경 시 이전 월과 새 월 합계 모두 원본에서 재계산한다. */
    @Test fun monthAndKindChangesRemoveOldMonthAndCountNewMonth() = runTest {
        ready()
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(kind = TransactionKind.EXPENSE, date = LocalDate.of(2026, 10, 31), creditAccountId = null), 1))
        assertEquals(100, repository.snapshot(context, LocalDate.of(2026, 10, 31)).expense)
        assertEquals(0, repository.snapshot(context, day).expense)
        repository.deleteTransaction(context, id(150), DeleteTransaction(id(30), 2))
        assertEquals(0, repository.snapshot(context, LocalDate.of(2026, 10, 31)).expense)
    }

    /**
     * 목적: 두 편집이 같은 revision을 함께 덮어쓰지 못하게 한다.
     * 조건: 실제 Default dispatcher에서 같은 거래·revision의 서로 다른 두 제출이다.
     * 절차: 공통 gate를 열고 실제 10초 상한 안에 두 결과를 모두 기다린다.
     * 기대: 성공 한 건·확정 충돌 한 건이며 변경 전 이력은 한 행이다.
     */
    @Test fun concurrentSameRevisionHasExactlyOneFinancialWinner() = runTest {
        ready()
        val gate = CompletableDeferred<Unit>()
        val results = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val jobs = listOf(140, 141).map { n ->
                    async {
                        gate.await()
                        runCatching { repository.updateTransaction(context, id(n), UpdateTransaction(original().copy(amount = n.toLong()), 1)) }
                    }
                }
                gate.complete(Unit)
                jobs.awaitAll()
            }
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, (results.single { it.isFailure }.exceptionOrNull() as TransactionRejectedException).rejection.expectedRevision)
        assertEquals(1, dao.transactionHistory(context.ledgerId, id(30)).size)
    }

    /**
     * 목적: 같은 operation 재전달의 금융 중복을 방지한다.
     * 조건: 실제 Default dispatcher에서 동일 operationId·내용을 두 번 제출한다.
     * 절차: 두 async 결과를 실제 10초 상한 안에 함께 기다린다.
     * 기대: 반환 영수증이 같고 이력은 한 행이며 revision은 한 번만 증가한다.
     */
    @Test fun concurrentSameOperationReturnsOneReceipt() = runTest {
        ready()
        val command = UpdateTransaction(original().copy(amount = 170), 1)
        val receipts = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                List(2) { async { repository.updateTransaction(context, id(140), command) } }.awaitAll()
            }
        }
        assertEquals(receipts[0], receipts[1])
        assertEquals(1, dao.transactionHistory(context.ledgerId, id(30)).size)
        assertEquals(2, dao.transaction(context.ledgerId, id(30))!!.revision)
    }

    /** SQL 실패는 history/거래/원장을 모두 rollback하고 캡처만 명시 재시도용으로 남긴다. */
    @Test fun sqlFailureRollsBackAndTypedPendingCanExplicitlyRetry() = runTest {
        ready()
        val before = repository.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER reject_change BEFORE INSERT ON operations WHEN NEW.kind='UPDATE_TRANSACTION' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val command = UpdateTransaction(original().copy(amount = 400), 1)
        assertFailsWith<Exception> { repository.updateTransaction(context, id(140), command) }
        assertEquals(before, repository.snapshot(context, day))
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
        assertEquals(PendingRecoveryItem.TransactionUpdate(id(140), command), repository.pendingRecovery(context).items.single())
        sql.execSQL("DROP TRIGGER reject_change")
        val receipt = repository.retryPending(context, id(140))
        assertEquals(receipt, repository.receipt(context, id(140)))
        assertTrue(repository.pendingWrites(context).isEmpty())
    }

    /** 삭제 실패 뒤 재열기에서도 삭제 typed 요청은 같은 ID/revision만 재시도한다. */
    @Test fun failedDeleteRecoversWithoutCreatingAnotherCommand() = runTest {
        ready()
        sql.execSQL("CREATE TRIGGER reject_delete BEFORE INSERT ON transaction_history BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val command = DeleteTransaction(id(30), 1)
        assertFailsWith<Exception> { repository.deleteTransaction(context, id(150), command) }
        assertNull(dao.transaction(context.ledgerId, id(30))!!.deletedAt)
        sql.execSQL("DROP TRIGGER reject_delete")
        db.close()
        reopen()
        assertEquals(PendingRecoveryItem.TransactionDeletion(id(150), command), repository.pendingRecovery(context).items.single())
        repository.retryPending(context, id(150))
        assertTrue(repository.snapshot(context, day).transactions.isEmpty())
    }

    /** 수정 초안은 대상·revision에 묶이고 제출과 함께 소비된다. 다른 생성 폼으로 재해석하지 않는다. */
    @Test fun editDraftPreservesRawInputAndConsumesOnlyMatchingTarget() = runTest {
        ready()
        val drafts = RoomDraftRepository(db, repository, contexts)
        val scope = DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS, transactionId = id(30), transactionRevision = 1)
        val invalid = DraftContent.Transaction(TransactionKind.TRANSFER, "잘못된 금액", "날짜 입력중", id(10), id(20), "수정 초안")
        val first = drafts.saveDraft(context, id(160), 0, scope, invalid)
        db.close()
        reopen()
        val currentDrafts = RoomDraftRepository(db, repository, contexts)
        assertEquals(first, (currentDrafts.draft(context, id(160)) as DraftSnapshot.Editable).draft)
        val valid = invalid.copy(amount = "200", date = day.toString())
        val saved = currentDrafts.saveDraft(context, id(160), first.reference.revision, scope, valid)
        assertFailsWith<IllegalStateException> { currentDrafts.createTransactionFromDraft(context, saved.reference, scope, id(161), original().copy(id = id(31), amount = 200, memo = valid.memo)) }
        assertTrue(repository.pendingWrites(context).isEmpty())
        val receipt = currentDrafts.updateTransactionFromDraft(context, saved.reference, scope, id(140), UpdateTransaction(original().copy(amount = 200, memo = valid.memo), 1))
        assertEquals(receipt, repository.retryPending(context, id(140)))
        assertTrue(currentDrafts.draft(context, id(160)) is DraftSnapshot.Closed)
        assertEquals(200, repository.snapshot(context, day).transactions.single().amount)
    }

    /** 생성 v1 원문/hash를 바꾸지 않고 v2만 새 명령을 해석하며 미지원/비정규 형식은 차단한다. */
    @Test fun codecVersionsRemainStrictAndLegacyBytesStayUnchanged() = runTest {
        ready()
        val legacy = dao.operation(context.ledgerId, id(130))!!
        assertTrue(legacy.requestJson.startsWith("{\"version\":1,"))
        assertEquals(legacy.requestJson, captureWrite(context, WriteCommand.Transaction(original())))
        for (command in listOf(WriteCommand.Update(UpdateTransaction(original(), 1)), WriteCommand.Delete(DeleteTransaction(id(30), 1)))) {
            val raw = captureWrite(context, command)
            assertEquals(command, decodeWrite(context, raw))
            val future = raw.replace("\"version\":2", "\"version\":99")
            assertEquals(PendingRecoveryItem.Unsupported(id(180), UnsupportedSubmission.VERSION), SubmissionEntity(context.ledgerId, id(180), future, requestHash(future)).toRecoveryItem(context))
            val malformed = raw + " "
            assertTrue(SubmissionEntity(context.ledgerId, id(180), malformed, requestHash(malformed)).toRecoveryItem(context) is PendingRecoveryItem.Corrupted)
        }
        val invalidV2 = legacy.requestJson.replace("\"version\":1", "\"version\":2")
        assertTrue(SubmissionEntity(context.ledgerId, id(180), invalidV2, requestHash(invalidV2)).toRecoveryItem(context) is PendingRecoveryItem.Corrupted)
    }

    /** 다른 저장 세대는 명령 캡처도 만들지 않으며 미래 날짜는 금융 변경을 하지 않는다. */
    @Test fun wrongGenerationAndInvalidDatePreserveFinance() = runTest {
        ready()
        val before = repository.snapshot(context, day)
        assertEquals(
            LedgerConflict.STALE_GENERATION,
            assertFailsWith<LedgerConflictException> {
                repository.deleteTransaction(context.copy(storageGeneration = id(999)), id(150), DeleteTransaction(id(30), 1))
            }.conflict,
        )
        assertTrue(repository.pendingWrites(context).isEmpty())
        assertFailsWith<IllegalArgumentException> { repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(date = day.plusDays(1)), 1)) }
        assertEquals(before, repository.snapshot(context, day))
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
    }

    /** 보관 계좌의 과거 거래는 메모만 바꿀 수 있고 금융값 변경은 거부한다. */
    @Test fun archivedAccountAllowsMemoButRejectsFinancialChanges() = runTest {
        ready()
        sql.execSQL("UPDATE accounts SET status='ARCHIVED' WHERE id=?", arrayOf(id(10)))
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "메모"), 1))
        assertEquals(
            LedgerConflict.ARCHIVED_ACCOUNT,
            assertFailsWith<LedgerConflictException> {
                repository.updateTransaction(context, id(141), UpdateTransaction(original().copy(amount = 200), 2))
            }.conflict,
        )
        assertEquals(1, dao.transactionHistory(context.ledgerId, id(30)).size)
        repository.deleteTransaction(context, id(150), DeleteTransaction(id(30), 2))
        assertTrue(repository.snapshot(context, day).transactions.isEmpty())
    }

    /**
     * 목적: 금융 트랜잭션 중 취소되면 history·거래·revision·영수증은 함께 rollback한다.
     * 조건: 실제 파일 Room의 UPDATE 실행 콜백에서 해당 요청 Job만 취소한다.
     * 절차: LAZY 자식 Job 참조를 먼저 설정한 뒤 실행·종료를 기다리고 같은 DB를 다시 읽는다.
     * 기대: 취소 관찰, 금융 snapshot 불변, 이력 없음, 원래 pending 한 건 보존.
     */
    @Test fun cancellationDuringUpdateRollsBackHistoryAndKeepsCapturedIntent() = runTest {
        ready()
        val before = repository.snapshot(context, day)
        db.close()
        val jobRef = AtomicReference<Job>()
        val observed = AtomicBoolean()
        db = Room.databaseBuilder(app, LedgerDatabase::class.java, "changes.db")
            .addMigrations(LedgerMigration1To2, LedgerMigration2To3, LedgerMigration3To4)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            })
            .setQueryCallback({ query, _ ->
                if (query.startsWith("UPDATE OR ABORT `transactions`")) {
                    observed.set(true)
                    jobRef.get()?.cancel()
                }
            }, Executor { it.run() })
            .build()
        repository = RoomLedgerRepository(db, clock, contexts)
        val job = launch(start = CoroutineStart.LAZY) { repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(amount = 300), 1)) }
        jobRef.set(job)
        job.start()
        job.join()
        assertTrue(observed.get())
        assertTrue(job.isCancelled)
        assertNull(repository.rejection(context, id(140)))
        assertEquals(before, repository.snapshot(context, day))
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
        assertEquals(listOf(id(140)), repository.pendingWrites(context).map { it.operationId })
    }

    /**
     * 목적: 실제 v3 파일을 v4로 열어 기존 금융·제출·초안을 모든 기존 열 단위로 보존한다.
     * 조건: 보존한 v3 JSON schema로 생성한 파일에 합성 계좌·거래·영수증·pending·초안을 넣는다.
     * 절차: Migration 후 기존 열/원문/hash를 비교하고 재열기·복구 조회를 반복한다.
     * 기대: 기존 값 동일, deletedAt=null, 이력 빈 테이블, 과거 영수증과 편집 초안 사용 가능.
     */
    @Test fun actualVersionThreeFileMigratesAndReopensWithoutInventingHistory() = runTest {
        db.close()
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/3.json").readText()).getJSONObject("database")
        app.deleteDatabase("changes.db")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
        }
        fun rows(connection: SupportSQLiteDatabase) = columns.mapValues { (table, names) ->
            connection.query("SELECT $names FROM $table ORDER BY 1,2").use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) } }
        }
        val create = captureWrite(context, WriteCommand.Transaction(original()))
        val pending = captureWrite(context, WriteCommand.Transaction(original().copy(id = id(31))))
        val scope = DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS)
        val content = DraftContent.Transaction(TransactionKind.EXPENSE, "입력중", "2026-", null, null, "원문\n")
        val draftPayload = captureDraft(context, id(160), scope, content)
        val expected = SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("changes.db"), null).use { fixture ->
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                fixture.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) fixture.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) fixture.execSQL(setup.getString(i))
            fixture.execSQL("INSERT INTO ledgers VALUES (?,?,4)", arrayOf(context.ledgerId, context.storageGeneration))
            for (n in listOf(10, 20)) {
                fixture.execSQL("INSERT INTO accounts VALUES (?,?,?,'GENERAL','ACTIVE',1)", arrayOf(context.ledgerId, id(n), "합성 $n"))
                fixture.execSQL("INSERT INTO anchors VALUES (?,?,?,'2026-11-10',NULL,NULL,'미확인',1)", arrayOf(context.ledgerId, id(n + 1), id(n)))
            }
            fixture.execSQL("INSERT INTO transactions VALUES (?,?,'TRANSFER','2026-11-11',100,?,?,'GENERAL','GENERAL','합성 원문',1,1)", arrayOf(context.ledgerId, id(30), id(10), id(20)))
            fixture.execSQL("INSERT INTO operations VALUES (?,?,?,'CREATE_TRANSACTION',?,?,?,4,?)", arrayOf(context.ledgerId, id(130), context.storageGeneration, create, requestHash(create), clock.instant().toString(), encodeRefs(listOf(EntityRef("transactions", id(30), 1)))))
            fixture.execSQL("INSERT INTO submissions VALUES (?,?,?,?)", arrayOf(context.ledgerId, id(131), pending, requestHash(pending)))
            fixture.execSQL("INSERT INTO submission_references VALUES (?,?,?)", arrayOf(context.ledgerId, id(131), context.storageGeneration))
            fixture.execSQL("INSERT INTO form_drafts VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(context.ledgerId, context.storageGeneration, id(160), 1, captureDraftScope(scope), draftPayload, requestHash(draftPayload), "EDITING", null))
            fixture.version = 3
            columns.mapValues { (table, names) ->
                fixture.rawQuery("SELECT $names FROM $table ORDER BY 1,2", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
                }
            }
        }
        reopen()
        val first = rows(sql)
        assertEquals(expected, first)
        assertNull(dao.transaction(context.ledgerId, id(30))!!.deletedAt)
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
        assertEquals(create, dao.operation(context.ledgerId, id(130))!!.requestJson)
        assertEquals(requestHash(create), dao.operation(context.ledgerId, id(130))!!.requestSha256)
        assertEquals(pending, repository.pendingWrites(context).single().requestJson)
        assertEquals(content, ((RoomDraftRepository(db, repository, contexts).draft(context, id(160)) as DraftSnapshot.Editable).draft.content))
        assertEquals(1, repository.receipt(context, id(130))!!.entities.single().revision)
        db.close()
        reopen()
        assertEquals(first, rows(sql))
        assertNull(dao.transaction(context.ledgerId, id(30))!!.deletedAt)
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
    }

    /**
     * 목적: 확정 stale 거절은 금융 성공과 구분해 보존하고 명시 종료 후 재발견하지 않는다.
     * 조건: revision 2를 만든 후 expectedRevision 1로 삭제하며 다른 활성 성공 참조도 존재한다.
     * 절차: 거절 commit 후 응답을 보관하지 않고 재열기·동일 요청/ID 재전달·명시 종료를 반복한다.
     * 기대: 금융·이력 불변, 같은 거절, 다른 원문 충돌, 원문 보존, 참조 부활/legacy pending 없음.
     */
    @Test fun rejectionLostReplyReopenAndExplicitFinishPreserveRequestWithoutResurrection() = runTest {
        ready()
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "최신"), 1))
        val before = repository.snapshot(context, day)
        val history = dao.transactionHistory(context.ledgerId, id(30))
        val command = DeleteTransaction(id(30), 1)
        val rejected = assertFailsWith<TransactionRejectedException> { repository.deleteTransaction(context, id(150), command) }.rejection
        val raw = dao.submission(context.ledgerId, id(150))!!
        db.close()
        reopen()
        assertEquals(rejected, repository.rejection(context, id(150)))
        assertNull(repository.receipt(context, id(150)))
        assertEquals(rejected, assertFailsWith<TransactionRejectedException> { repository.retryPending(context, id(150)) }.rejection)
        assertEquals(rejected, assertFailsWith<TransactionRejectedException> { repository.deleteTransaction(context, id(150), command) }.rejection)
        assertEquals(LedgerConflict.DUPLICATE_REQUEST, assertFailsWith<LedgerConflictException> { repository.deleteTransaction(context, id(150), command.copy(expectedRevision = 2)) }.conflict)
        assertEquals(before, repository.snapshot(context, day))
        assertEquals(history, dao.transactionHistory(context.ledgerId, id(30)))
        assertTrue(repository.pendingWrites(context).isEmpty())
        assertTrue(repository.pendingRecovery(context).items.isEmpty())
        assertTrue(repository.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.Rejected && it.rejection == rejected })
        val otherReferences = dao.submissionReferences(context.ledgerId).filter { it.operationId != id(150) }
        assertEquals(FinishSubmissionRecoveryResult.Finished, repository.finishSubmissionRecovery(context, id(150)))
        assertEquals(FinishSubmissionRecoveryResult.AlreadyInactive, repository.finishSubmissionRecovery(context, id(150)))
        assertEquals(otherReferences, dao.submissionReferences(context.ledgerId))
        assertEquals(raw, dao.submission(context.ledgerId, id(150)))
        assertTrue(repository.submissionRecovery(context).items.none { it is SubmissionRecoveryItem.Rejected })
        assertEquals(rejected, assertFailsWith<TransactionRejectedException> { repository.deleteTransaction(context, id(150), command) }.rejection)
        assertEquals(otherReferences, dao.submissionReferences(context.ledgerId))
    }

    /** 거절에 연결된 초안 원문을 보존하면서 새 독립 폼을 막지 않고 자동 재제출하지 않는다. */
    @Test fun rejectedDraftRetainsRawContentAndCannotBeResubmitted() = runTest {
        ready()
        val drafts = RoomDraftRepository(db, repository, contexts)
        val scope = DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS, transactionId = id(30), transactionRevision = 1)
        val content = DraftContent.Transaction(TransactionKind.TRANSFER, "200", day.toString(), id(10), id(20), "충돌 입력")
        val draft = drafts.saveDraft(context, id(160), 0, scope, content)
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "다른 수정"), 1))
        assertFailsWith<TransactionRejectedException> { drafts.updateTransactionFromDraft(context, draft.reference, scope, id(141), UpdateTransaction(original().copy(amount = 200, memo = content.memo), 1)) }
        val rejected = drafts.draft(context, id(160)) as DraftSnapshot.Rejected
        assertEquals(content, rejected.draft.content)
        assertEquals(draft.reference.revision + 1, rejected.draft.reference.revision)
        assertTrue(drafts.drafts(context).none { it is DraftSnapshot.Submitted || it is DraftSnapshot.Blocked })
        repository.finishSubmissionRecovery(context, id(141))
        assertEquals(content, (drafts.draft(context, id(160)) as DraftSnapshot.Rejected).draft.content)
        val newer = scope.copy(transactionRevision = 2)
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(160), rejected.draft.reference.revision, newer, content) }
        assertEquals(newer, drafts.saveDraft(context, id(161), 0, newer, content).scope)
    }

    /** I/O/제약 실패와 ledger CAS 실패는 확정 대상 충돌로 영속화하지 않는다. */
    @Test fun financialFailureAndLedgerCasFailureAreNeverTerminalRejections() = runTest {
        ready()
        val before = repository.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER reject_ledger BEFORE UPDATE ON ledgers BEGIN SELECT RAISE(IGNORE); END")
        assertEquals(LedgerConflict.STALE_REVISION, assertFailsWith<LedgerConflictException> { repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(amount = 200), 1)) }.conflict)
        assertNull(repository.rejection(context, id(140)))
        assertEquals(before, repository.snapshot(context, day))
        assertTrue(dao.transactionHistory(context.ledgerId, id(30)).isEmpty())
        sql.execSQL("DROP TRIGGER reject_ledger")
        repository.retryPending(context, id(140))
        assertNotNull(repository.receipt(context, id(140)))
    }

    /** 성공·거절 동시 존재와 변조된 거절은 차단하고 참조 종료로 숨기지 않는다. */
    @Test fun corruptOrConflictingRejectionsRemainBlocked() = runTest {
        ready()
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "최신"), 1))
        assertFailsWith<TransactionRejectedException> { repository.deleteTransaction(context, id(150), DeleteTransaction(id(30), 1)) }
        sql.execSQL("UPDATE submission_rejections SET requestSha256='invalid' WHERE operationId=?", arrayOf(id(150)))
        assertTrue(repository.submissionRecovery(context).items.any { it is SubmissionRecoveryItem.Blocked })
        assertTrue(repository.finishSubmissionRecovery(context, id(150)) is FinishSubmissionRecoveryResult.NotReady)
        assertNotNull(dao.submissionReference(context.ledgerId, id(150)))
        val success = dao.operation(context.ledgerId, id(140))!!
        dao.insertRejection(SubmissionRejectionEntity(context.ledgerId, id(140), context.storageGeneration, success.requestJson, success.requestSha256, id(30), 1, 2, null, clock.instant().toString()))
        assertFailsWith<IllegalStateException> { repository.receipt(context, id(140)) }
        assertTrue(repository.finishSubmissionRecovery(context, id(140)) is FinishSubmissionRecoveryResult.NotReady)
    }

    /** 거절 결과 저장 실패는 terminal 상태를 만들지 않고 캡처만 보존한다. */
    @Test fun rejectionCommitFailureCanRetrySameCapturedRequest() = runTest {
        ready()
        repository.updateTransaction(context, id(140), UpdateTransaction(original().copy(memo = "최신"), 1))
        val before = repository.snapshot(context, day)
        sql.execSQL("CREATE TRIGGER reject_outcome BEFORE INSERT ON submission_rejections BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<Exception> { repository.deleteTransaction(context, id(150), DeleteTransaction(id(30), 1)) }
        assertNull(repository.rejection(context, id(150)))
        assertEquals(before, repository.snapshot(context, day))
        sql.execSQL("DROP TRIGGER reject_outcome")
        assertFailsWith<TransactionRejectedException> { repository.retryPending(context, id(150)) }
        assertNotNull(repository.rejection(context, id(150)))
    }
}
