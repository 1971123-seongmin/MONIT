package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 실제 파일 Room·SQL trigger의 경계 시험. 단말 재부팅/전원 중단 시험은 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class FormDraftRepositoryTest {
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var drafts: RoomDraftRepository
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:30:00Z"), ZoneOffset.UTC)
    private val dispatchers = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val accountScope = DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS)
    private val transactionScope = DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS)
    private val sql get() = db.openHelper.writableDatabase
    private val oldTables = listOf("ledgers", "accounts", "anchors", "transactions", "operations", "submissions", "submission_references")
    private fun raw(name: String = "합성 계좌") = DraftContent.Account(name, AccountNature.GENERAL, "2026-11-10", "미확인")

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After fun teardown() {
        db.close()
        app.deleteDatabase("drafts.db")
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "drafts.db")
        ledger = RoomLedgerRepository(db, clock, dispatchers)
        drafts = RoomDraftRepository(db, ledger, dispatchers)
    }
    private suspend fun save(content: DraftContent = raw(), scope: DraftScope = accountScope, number: Int = 1) = drafts.saveDraft(context, id(number), 0, scope, content)
    private fun account(number: Int = 10) = CreateAccount(id(number), id(number + 1), "합성 계좌", AccountNature.GENERAL, LocalDate.of(2026, 11, 10), "미확인")
    private fun values(tables: List<String> = oldTables) = tables.associateWith { table ->
        sql.query("SELECT ${if (table == "transactions") "ledgerId,id,kind,date,amount,debitAccountId,creditAccountId,debitNature,creditNature,memo,revision,financialRevision" else "*"} FROM $table ORDER BY 1,2").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
            }
        }
    }

    /**
     * 목적: 실제 Job 취소에서도 최초 초안 잠금/pending/참조가 하나의 rollback이다.
     * 조건: SQL 참조 INSERT 관찰자가 호출 Job을 취소한다.
     * 절차: Default 스레드 제출 → 10초 안 종료 확인 → 파일 재열기.
     * 기대: 기존 여덟 테이블은 동일하고 원래 초안은 편집 가능하다.
     */
    @Test fun cancellationDuringDraftCaptureRollsBackEveryRow() = runTest {
        ledger.initialize(context)
        val form = save()
        val before = values(oldTables + "form_drafts")
        val job = AtomicReference<Job>()
        val observed = cancelOnQuery("INSERT OR ABORT INTO `submission_references`", job)
        val child = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            drafts.createAccountFromDraft(context, form.reference, accountScope, id(100), account())
        }
        job.set(child)
        child.start()
        withContext(Dispatchers.Default) { withTimeout(10_000) { child.join() } }
        assertTrue(observed.get())
        assertTrue(child.isCancelled)
        db.close()
        reopen()
        assertEquals(before, values(oldTables + "form_drafts"))
        assertEquals(DraftSnapshot.Editable(form), drafts.draft(context, form.reference.id))
    }

    /**
     * 목적: 금융 commit과 초안 소비 중 취소가 일부 성공을 남기지 않는다.
     * 조건: CONSUMED UPDATE 관찰자가 호출 Job을 취소한다.
     * 절차: 제출 → 실제 Job 종료 → 재열기 → 원래 pending 명시 재시도.
     * 기대: 최초에는 잠금/pending만 남고 금융 행은 없으며, 재시도 한 번으로 저장/소비한다.
     */
    @Test fun cancellationAtConsumptionPreservesPendingAndAllowsSameRetry() = runTest {
        ledger.initialize(context)
        val form = save()
        val job = AtomicReference<Job>()
        val observed = cancelOnQuery("UPDATE form_drafts SET revision = revision + 1, status =", job)
        val child = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            drafts.createAccountFromDraft(context, form.reference, accountScope, id(100), account())
        }
        job.set(child)
        child.start()
        withContext(Dispatchers.Default) { withTimeout(10_000) { child.join() } }
        assertTrue(observed.get())
        assertTrue(child.isCancelled)
        db.close()
        reopen()
        assertTrue(ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.isEmpty())
        assertNull(ledger.receipt(context, id(100)))
        assertEquals(DraftSnapshot.Submitted(form.reference, id(100)), drafts.draft(context, form.reference.id))
        val receipt = ledger.retryPending(context, id(100))
        assertEquals(receipt, ledger.retryPending(context, id(100)))
        assertEquals(1, ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.size)
        assertEquals(DraftSnapshot.Closed(form.reference.copy(revision = 2), id(100)), drafts.draft(context, form.reference.id))
    }

    /** 실제 Default 경합의 서로 다른 CAS 갱신은 한 revision만 올리고 패자 입력은 덮어쓰지 않는다. */
    @Test fun concurrentSavesAcceptOnlyOneDifferentRevision() = runTest {
        ledger.initialize(context)
        val form = save()
        val gate = CompletableDeferred<Unit>()
        val attempts = listOf("첫 경합", "두 번째 경합").map { name ->
            async(Dispatchers.Default) {
                gate.await()
                runCatching { drafts.saveDraft(context, form.reference.id, 1, accountScope, raw(name)) }
            }
        }
        gate.complete(Unit)
        val results = withContext(Dispatchers.Default) { withTimeout(10_000) { attempts.awaitAll() } }
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is DraftConflictException)
        val winner = results.single { it.isSuccess }.getOrThrow()
        assertEquals(2, winner.reference.revision)
        assertEquals(DraftSnapshot.Editable(winner), drafts.draft(context, form.reference.id))
    }

    /** 같은 초안/operation의 실제 경합 제출 두 개는 같은 영수증과 금융 행 하나만 만든다. */
    @Test fun concurrentSameSubmissionIsAtomicAndIdempotent() = runTest {
        ledger.initialize(context)
        val form = save()
        val gate = CompletableDeferred<Unit>()
        val attempts = List(2) {
            async(Dispatchers.Default) {
                gate.await()
                drafts.createAccountFromDraft(context, form.reference, accountScope, id(100), account())
            }
        }
        gate.complete(Unit)
        val receipts = withContext(Dispatchers.Default) { withTimeout(10_000) { attempts.awaitAll() } }
        assertEquals(receipts.first(), receipts.last())
        assertEquals(1, ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.size)
        assertTrue(ledger.pendingWrites(context).isEmpty())
        assertEquals(DraftSnapshot.Closed(form.reference.copy(revision = 2), id(100)), drafts.draft(context, form.reference.id))
    }

    /** 기존 금융 pending에 새 폼을 사후 연결하지 않고 양쪽 원문과 ID를 보존한다. */
    @Test fun newDraftCannotAdoptExistingLegacyPending() = runTest {
        ledger.initialize(context)
        sql.execSQL("CREATE TRIGGER fail_account BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<SQLiteException> { ledger.createAccount(context, id(100), account()) }
        val form = save()
        val before = values(oldTables + "form_drafts")
        assertFailsWith<DraftConflictException> { drafts.createAccountFromDraft(context, form.reference, accountScope, id(100), account()) }
        assertEquals(before, values(oldTables + "form_drafts"))
    }

    private fun cancelOnQuery(prefix: String, job: AtomicReference<Job>): AtomicBoolean {
        db.close()
        val observed = AtomicBoolean()
        db = Room.databaseBuilder(app, LedgerDatabase::class.java, "drafts.db")
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
                    job.get().cancel(CancellationException("Synthetic SQL cancellation"))
                }
            }, Executor { it.run() })
            .build()
        ledger = RoomLedgerRepository(db, clock, dispatchers)
        drafts = RoomDraftRepository(db, ledger, dispatchers)
        return observed
    }

    /** 유효하지 않은 편집 원문도 새 DB 연결에서 복구되며 금융 값/ledger revision을 만들지 않는다. */
    @Test fun rawInputSurvivesReopenOutsideFinancialTotals() = runTest {
        ledger.initialize(context)
        val before = values()
        val content = DraftContent.Transaction(TransactionKind.EXPENSE, " 00.불완전 ", "2026-02-30", null, null, "\n메모  ")
        val saved = save(content, transactionScope)
        db.close()
        reopen()
        assertEquals(DraftSnapshot.Editable(saved), drafts.draft(context, saved.reference.id))
        assertEquals(before, values())
        assertEquals(0, ledger.snapshot(context, LocalDate.of(2026, 11, 12)).transactions.size)
    }

    /** CAS의 오래된 다른 값과 다른 폼/호출 문맥은 기존 초안을 덮어쓰지 않는다. */
    @Test fun revisionAndScopeRejectStaleWriters() = runTest {
        ledger.initialize(context)
        val first = save()
        val second = drafts.saveDraft(context, id(1), first.reference.revision, accountScope, raw("새 입력"))
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 1, accountScope, raw("늦은 입력")) }
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), second.reference.revision, transactionScope, DraftContent.Transaction(TransactionKind.INCOME, "1", "", null, null, "")) }
        assertEquals(DraftSnapshot.Editable(second), drafts.draft(context, id(1)))
        assertEquals(second, drafts.saveDraft(context, id(1), 1, accountScope, raw("새 입력")))
    }

    /** 명시 폐기 뒤 늦은 최초 INSERT와 UPDATE 모두 fence를 넘지 못하고 다른 초안은 남는다. */
    @Test fun discardedDraftCannotBeResurrected() = runTest {
        ledger.initialize(context)
        val saved = save()
        val other = save(number = 2)
        drafts.discardDraft(context, saved.reference, accountScope)
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 0, accountScope, raw()) }
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 1, accountScope, raw("늦은 값")) }
        assertTrue(drafts.draft(context, id(1)) is DraftSnapshot.Closed)
        assertEquals(listOf(DraftSnapshot.Editable(other)), drafts.drafts(context))
        sql.query("SELECT payloadJson,payloadSha256 FROM form_drafts WHERE id='${id(1)}'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0) && it.isNull(1))
        }
    }

    /**
     * 목적: 초안 잠금과 pending/참조의 최초 기록이 함께 실패한다.
     * 조건: 참조 INSERT가 SQL trigger로 실패한다.
     * 절차: 동일 초안의 계좌 제출 → SQLite 실패 → DB 재열기.
     * 기대: 초안은 여전히 편집 가능, 금융/pending/참조 변경은 없다.
     */
    @Test fun captureFailureRollsBackDraftLockAndPending() = runTest {
        ledger.initialize(context)
        val saved = save()
        val before = values()
        sql.execSQL("CREATE TRIGGER fail_ref BEFORE INSERT ON submission_references BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<SQLiteException> { drafts.createAccountFromDraft(context, saved.reference, accountScope, id(100), account()) }
        db.close()
        reopen()
        assertEquals(DraftSnapshot.Editable(saved), drafts.draft(context, id(1)))
        assertEquals(before, values())
    }

    /**
     * 목적: 금융 결과와 초안 소비는 하나의 commit이다.
     * 조건: 초안을 CONSUMED로 바꾸는 SQL UPDATE가 실패한다.
     * 절차: 제출 → 실패 → 재열기 → 같은 operationId로 retryPending.
     * 기대: 실패 시 금융/operation 0, 잠금/pending/참조 유지; 재시도 성공은 한 번 저장/소비한다.
     */
    @Test fun financialRollbackPreservesLockedDraftAndRetryConsumesIt() = runTest {
        ledger.initialize(context)
        val saved = save()
        sql.execSQL("CREATE TRIGGER fail_consume BEFORE UPDATE OF status ON form_drafts WHEN NEW.status='CONSUMED' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<SQLiteException> { drafts.createAccountFromDraft(context, saved.reference, accountScope, id(100), account()) }
        db.close()
        reopen()
        assertTrue(ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.isEmpty())
        assertNull(ledger.receipt(context, id(100)))
        assertEquals(DraftSnapshot.Submitted(saved.reference, id(100)), drafts.draft(context, id(1)))
        assertFailsWith<DraftConflictException> { drafts.discardDraft(context, saved.reference, accountScope) }
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 1, accountScope, raw("수정 금지")) }
        sql.execSQL("DROP TRIGGER fail_consume")
        val receipt = ledger.retryPending(context, id(100))
        assertEquals(receipt, ledger.retryPending(context, id(100)))
        assertEquals(receipt, drafts.createAccountFromDraft(context, saved.reference, accountScope, id(100), account()))
        assertEquals(1, ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.size)
        assertTrue(drafts.drafts(context).isEmpty())
        assertEquals(DraftSnapshot.Closed(DraftReference(id(1), 2), id(100)), drafts.draft(context, id(1)))
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 0, accountScope, raw()) }
    }

    /** 다른 revision/원문/호출 문맥/operation 재사용은 금융 첫 쓰기 전에 거부한다. */
    @Test fun mismatchedSubmissionCannotConsumeAnotherDraft() = runTest {
        ledger.initialize(context)
        val saved = save()
        assertFailsWith<IllegalStateException> { drafts.createAccountFromDraft(context, saved.reference, accountScope, id(100), account().copy(name = "다른 내용")) }
        assertFailsWith<DraftConflictException> { drafts.createAccountFromDraft(context, DraftReference(id(1), 2), accountScope, id(100), account()) }
        assertTrue(ledger.pendingWrites(context).isEmpty())
        val receipt = drafts.createAccountFromDraft(context, saved.reference, accountScope, id(100), account())
        val other = save(number = 2)
        assertFailsWith<DraftConflictException> { drafts.createAccountFromDraft(context, other.reference, accountScope, id(100), account()) }
        assertEquals(receipt, ledger.receipt(context, id(100)))
        assertEquals(DraftSnapshot.Editable(other), drafts.draft(context, id(2)))
    }

    /** 세대가 다른 초안은 조회 목록에서도 차단되며 새 context로 바꿔 쓰지 않는다. */
    @Test fun generationMismatchPreservesOriginalRow() = runTest {
        ledger.initialize(context)
        save()
        sql.execSQL("UPDATE form_drafts SET storageGeneration='${id(999)}'")
        val before = values(listOf("form_drafts"))
        assertEquals(listOf(DraftSnapshot.Blocked(id(1), DraftProblem.WRONG_CONTEXT)), drafts.drafts(context))
        assertFailsWith<DraftConflictException> { drafts.saveDraft(context, id(1), 1, accountScope, raw("교체 금지")) }
        assertEquals(before, values(listOf("form_drafts")))
    }

    /** 부모 명시 폐기는 잠긴 자식이나 기존 금융 행을 지우지 않으며 원래 자식 재시도는 유지한다. */
    @Test fun parentDiscardPreservesSubmittedChild() = runTest {
        ledger.initialize(context)
        val parent = save(DraftContent.Transaction(TransactionKind.EXPENSE, "", "잘못된 날짜", null, null, "", DraftSlot.DEBIT, id(80)), transactionScope)
        val childScope = DraftScope(DraftKind.ACCOUNT, DraftOrigin.TRANSACTION_ENTRY, parent.reference.id, id(80), DraftSlot.DEBIT)
        val child = save(scope = childScope, number = 2)
        sql.execSQL("CREATE TRIGGER fail_account BEFORE INSERT ON accounts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFailsWith<SQLiteException> { drafts.createAccountFromDraft(context, child.reference, childScope, id(100), account()) }
        drafts.discardDraft(context, parent.reference, transactionScope)
        assertEquals(DraftSnapshot.Submitted(child.reference, id(100)), drafts.draft(context, id(2)))
        sql.execSQL("DROP TRIGGER fail_account")
        ledger.retryPending(context, id(100))
        assertEquals(1, ledger.snapshot(context, LocalDate.of(2026, 11, 12)).accounts.size)
        assertTrue(drafts.draft(context, id(1)) is DraftSnapshot.Closed)
    }

    /** 잘못된 부모 선택 요청으로 자식 초안을 만들거나 저장할 수 없다. */
    @Test fun childRequiresTheExactParentRequestAndSlot() = runTest {
        ledger.initialize(context)
        val parent = save(DraftContent.Transaction(TransactionKind.EXPENSE, "", "", null, null, "", DraftSlot.DEBIT, id(80)), transactionScope)
        val wrong = DraftScope(DraftKind.ACCOUNT, DraftOrigin.TRANSACTION_ENTRY, parent.reference.id, id(81), DraftSlot.CREDIT)
        assertFailsWith<DraftConflictException> { save(scope = wrong, number = 2) }
        assertEquals(1, drafts.drafts(context).size)
    }

    /** v2의 모든 금융/원문/hash/참조를 schema3 추가 Migration이 그대로 유지한다. */
    @Test fun versionTwoMigrationPreservesEveryExistingRow() = runTest { migrationFixture(2) }

    /** v1→2→3 연결에서도 원래 원문/ID는 그대로이고 과거 성공 참조는 역생성하지 않는다. */
    @Test fun versionOneMigrationChainPreservesEveryExistingRow() = runTest { migrationFixture(1) }

    private suspend fun migrationFixture(version: Int) {
        db.close()
        app.deleteDatabase("drafts.db")
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/$version.json").readText()).getJSONObject("database")
        val file = app.getDatabasePath("drafts.db")
        file.parentFile!!.mkdirs()
        val raw = captureWrite(context, WriteCommand.Account(account()))
        val tables = if (version == 1) oldTables.dropLast(1) else oldTables
        val expected = SQLiteDatabase.openOrCreateDatabase(file, null).use { fixture ->
            val entities = schema.getJSONArray("entities")
            for (n in 0 until entities.length()) {
                val e = entities.getJSONObject(n)
                val name = e.getString("tableName")
                fixture.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = e.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) fixture.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) fixture.execSQL(setup.getString(i))
            fixture.execSQL("INSERT INTO ledgers VALUES (?,?,1)", arrayOf(context.ledgerId, context.storageGeneration))
            fixture.execSQL("INSERT INTO accounts VALUES (?,?,?,'GENERAL','ACTIVE',1)", arrayOf(context.ledgerId, id(10), "합성 계좌"))
            fixture.execSQL("INSERT INTO anchors VALUES (?,?,?,'2026-11-10',NULL,NULL,'미확인',1)", arrayOf(context.ledgerId, id(11), id(10)))
            fixture.execSQL("INSERT INTO transactions VALUES (?,?,'INCOME','2026-11-11',12,NULL,?,NULL,'GENERAL','원문',1,1)", arrayOf(context.ledgerId, id(20), id(10)))
            fixture.execSQL("INSERT INTO operations VALUES (?,?,?,'UPDATE_ACCOUNT',?,?,?,1,?)", arrayOf(context.ledgerId, id(100), context.storageGeneration, raw, requestHash(raw), clock.instant().toString(), encodeRefs(listOf(EntityRef("accounts", id(10), 1), EntityRef("anchors", id(11), 1)))))
            fixture.execSQL("INSERT INTO submissions VALUES (?,?,?,?)", arrayOf(context.ledgerId, id(101), raw, requestHash(raw)))
            if (version == 2) fixture.execSQL("INSERT INTO submission_references VALUES (?,?,?)", arrayOf(context.ledgerId, id(101), context.storageGeneration))
            fixture.version = version
            tables.associateWith { table ->
                fixture.rawQuery("SELECT ${if (table == "transactions") "ledgerId,id,kind,date,amount,debitAccountId,creditAccountId,debitNature,creditNature,memo,revision,financialRevision" else "*"} FROM $table ORDER BY 1,2", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                    }
                }
            }
        }
        reopen()
        assertEquals(expected, values(tables))
        assertTrue(drafts.drafts(context).isEmpty())
        sql.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals(6, it.getInt(0))
        }
        db.close()
        reopen()
        assertEquals(expected, values(tables))
        assertTrue(drafts.drafts(context).isEmpty())
    }

    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
