package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PaymentMigrationTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val dispatchers = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    /**
     * 목적: 실제 보존 v4 schema로 만든 파일에서 v5가 기존 10개 테이블의 모든 열을 보존한다.
     * 조건: 합성 금융·삭제/수정 이력·v1/v2 원문/hash·활성 pending/거절·불완전 원문 초안이 있다.
     * 절차: Migration·읽기 복구·재열기 전후 기존 열을 비교하고 새 납입을 한 번만 확정한다.
     * 기대: 기존 값 동일, 새 6개 테이블 처음에는 비어 있음, 기존 영수증/거절/초안 유효, 새 거래도 한 건이다.
     */
    @Test fun actualVersionFourRowsAndOriginalProtocolsSurviveVersionFiveMigration() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val name = "payment-migration.db"
        app.deleteDatabase(name)
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/4.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { i ->
            val entity = entities.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).joinToString(",") { fields.getJSONObject(it).getString("columnName") }
        }
        val original = CreateTransaction(id(50), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 12), 30_000, id(10), memo = "이전 메모")
        val updated = original.copy(memo = "변경 메모")
        val createRaw = captureWrite(context, WriteCommand.Transaction(original))
        val updateRaw = captureWrite(context, WriteCommand.Update(UpdateTransaction(updated, 1)))
        val pendingRaw = captureWrite(context, WriteCommand.Account(CreateAccount(id(60), id(61), "합성 새 계좌", AccountNature.GENERAL, LocalDate.of(2026, 11, 12), "미확인")))
        val rejectionRaw = captureWrite(context, WriteCommand.Delete(DeleteTransaction(id(50), 1)))
        val scope = DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS)
        val content = DraftContent.Account(" 입력중 ", AccountNature.GENERAL, "2026-", "")
        val draftRaw = captureDraft(context, id(80), scope, content)
        val expected = SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { db ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO ledgers VALUES (?,?,6)", arrayOf(context.ledgerId, context.storageGeneration))
            for ((n, nature) in listOf(10 to "GENERAL", 20 to "SAVINGS")) {
                db.execSQL("INSERT INTO accounts VALUES (?,?,?,?,'ACTIVE',1)", arrayOf(context.ledgerId, id(n), "합성 $n", nature))
                db.execSQL("INSERT INTO anchors VALUES (?,?,?,'2026-11-11',500000,?,NULL,1)", arrayOf(context.ledgerId, id(n + 1), id(n), clock.instant().toString()))
            }
            db.execSQL("INSERT INTO transactions VALUES (?,?,'EXPENSE','2026-11-12',30000,?,NULL,'GENERAL',NULL,'변경 메모',2,1,NULL)", arrayOf(context.ledgerId, id(50), id(10)))
            for ((op, raw, kind, revision) in listOf(listOf(150, createRaw, "CREATE_TRANSACTION", 1), listOf(151, updateRaw, "UPDATE_TRANSACTION", 2))) {
                val number = op as Int
                val payload = raw as String
                db.execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?,?,?,?)", arrayOf(context.ledgerId, id(number), context.storageGeneration, kind, payload, requestHash(payload), clock.instant().toString(), number - 146, encodeRefs(listOf(EntityRef("transactions", id(50), (revision as Int).toLong())))))
            }
            db.execSQL("INSERT INTO transaction_history (ledgerId,id,kind,date,amount,debitAccountId,creditAccountId,debitNature,creditNature,memo,revision,financialRevision,deletedAt,operationId,changedAt,changeKind) VALUES (?,?,'EXPENSE','2026-11-12',30000,?,NULL,'GENERAL',NULL,'이전 메모',1,1,NULL,?,?,'UPDATE')", arrayOf(context.ledgerId, id(50), id(10), id(151), clock.instant().toString()))
            for ((op, raw) in listOf(152 to pendingRaw, 153 to rejectionRaw)) {
                db.execSQL("INSERT INTO submissions VALUES (?,?,?,?)", arrayOf(context.ledgerId, id(op), raw, requestHash(raw)))
                db.execSQL("INSERT INTO submission_references VALUES (?,?,?)", arrayOf(context.ledgerId, id(op), context.storageGeneration))
            }
            db.execSQL("INSERT INTO submission_rejections VALUES (?,?,?,?,?,?,1,2,NULL,?)", arrayOf(context.ledgerId, id(153), context.storageGeneration, rejectionRaw, requestHash(rejectionRaw), id(50), clock.instant().toString()))
            db.execSQL("INSERT INTO form_drafts VALUES (?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(context.ledgerId, context.storageGeneration, id(80), 1, captureDraftScope(scope), draftRaw, requestHash(draftRaw), "EDITING", null))
            db.version = 4
            columns.mapValues { (table, fields) -> db.rawQuery("SELECT $fields FROM $table ORDER BY 1,2", null).use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) } } }
        }
        var db = createLedgerDatabase(app, name)
        try {
            fun rows() = columns.mapValues { (table, fields) -> db.openHelper.writableDatabase.query("SELECT $fields FROM $table ORDER BY 1,2").use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) } } }
            assertEquals(expected, rows())
            val newTables = listOf("payment_rules", "payment_rule_versions", "payment_items", "payment_events", "payment_rejections", "payment_intents")
            for (table in newTables) db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            var ledger = RoomLedgerRepository(db, clock, dispatchers)
            var drafts = RoomDraftRepository(db, ledger, dispatchers)
            assertEquals(content, (drafts.draft(context, id(80)) as DraftSnapshot.Editable).draft.content)
            assertEquals(1, ledger.receipt(context, id(150))!!.entities.single().revision)
            assertEquals(2, ledger.receipt(context, id(151))!!.entities.single().revision)
            assertNotNull(ledger.rejection(context, id(153)))
            assertEquals(listOf(id(152)), ledger.pendingWrites(context).map { it.operationId })
            assertEquals(expected, rows())
            db.close()
            db = createLedgerDatabase(app, name)
            ledger = RoomLedgerRepository(db, clock, dispatchers)
            drafts = RoomDraftRepository(db, ledger, dispatchers)
            assertEquals(expected, rows())
            assertEquals(content, (drafts.draft(context, id(80)) as DraftSnapshot.Editable).draft.content)
            val payments = RoomPaymentRepository(db, ledger, clock, dispatchers)
            assertTrue(payments.month(context, "2026-11").items.isEmpty())
            val terms = PaymentTerms("합성 보험", PaymentPurpose.INSURANCE, 30_000, 31, id(10))
            payments.submit(context, id(170), PaymentCommand.RegisterRule(id(70), terms, "2026-11"))
            val item = payments.month(context, "2026-11").items.single()
            val command = PaymentCommand.Confirm(item.reference, PaymentTransaction(original.copy(id = id(71), date = LocalDate.of(2026, 11, 13))), true, "")
            val receipt = payments.submit(context, id(171), command)
            assertEquals(receipt, payments.submit(context, id(171), command))
            assertEquals(2, ledger.snapshot(context, LocalDate.of(2026, 11, 13)).transactions.size)
            assertEquals(60_000, ledger.snapshot(context, LocalDate.of(2026, 11, 13)).expense)
            assertEquals(createRaw, db.ledgerDao().operation(context.ledgerId, id(150))!!.requestJson)
            assertEquals(updateRaw, db.ledgerDao().operation(context.ledgerId, id(151))!!.requestJson)
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }
}
