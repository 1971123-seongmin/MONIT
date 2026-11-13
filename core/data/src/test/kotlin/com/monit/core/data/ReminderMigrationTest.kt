package com.monit.core.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.io.File
import java.time.*
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
class ReminderMigrationTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    /**
     * 목적: 실제 보존 schema5 파일의 기존 모든 열·납입/금융/원문을 v6가 보존한다.
     * 조건: 실제 writer로 만든 규칙·버전·완료·근거·원래 intent와 v1/v2 결과가 있다.
     * 절차: checked-in v5 DDL 파일에 기존 16개 업무 테이블의 열을 복사해 user_version5로 열고 Migration·재열기한다.
     * 기대: 모든 기존 열/행 동일, 새 알림3테이블은 비어 있음, 기존 receipt·납입 조회 유효, 새 설정만 추가된다.
     */
    @Test fun actualSchemaFiveFilePreservesPaymentsAndOriginalRequests() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val sourceName = "reminder-source.db"
        val targetName = "reminder-v5.db"
        app.deleteDatabase(sourceName)
        app.deleteDatabase(targetName)
        val source = createLedgerDatabase(app, sourceName)
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/5.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        try {
            val ledger = RoomLedgerRepository(source, clock, contexts)
            ledger.initialize(context)
            ledger.createAccount(context, id(110), CreateAccount(id(10), id(11), "합성 계좌", AccountNature.GENERAL, LocalDate.of(2026, 11, 10), "미확인"))
            val payments = RoomPaymentRepository(source, ledger, clock, contexts)
            payments.submit(context, id(140), PaymentCommand.RegisterRule(id(40), PaymentTerms("합성 보험", PaymentPurpose.INSURANCE, 30_000, 13, id(10)), "2026-11"))
            val item = payments.month(context, "2026-11").items.single()
            val tx = CreateTransaction(id(50), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 13), 30_000, id(10), memo = "이전 원문")
            val receipt = payments.submit(context, id(150), PaymentCommand.Confirm(item.reference, PaymentTransaction(tx), true, ""))
            ledger.updateTransaction(context, id(151), UpdateTransaction(tx.copy(memo = "메모만 정정"), 1))
            val stale = PaymentCommand.ChangeItem(item.reference, PaymentAction.SKIP)
            assertFailsWith<PaymentRejectedException> { payments.submit(context, id(152), stale) }
            val expected = columns.mapValues { (table, fields) -> readColumns(source.openHelper.writableDatabase, table, fields) }
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(targetName), null).use { target ->
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    target.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (i in 0 until indices.length()) target.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) target.execSQL(setup.getString(index))
                for ((table, fields) in columns) {
                    for (row in expected.getValue(table)) target.execSQL("INSERT INTO $table (${fields.joinToString(",")}) VALUES (${fields.joinToString(",") { "?" }})", row.toTypedArray())
                }
                target.version = 5
            }
            var migrated = createLedgerDatabase(app, targetName)
            try {
                assertEquals(9, migrated.openHelper.writableDatabase.version)
                for ((table, fields) in columns) assertEquals(expected.getValue(table), readColumns(migrated.openHelper.writableDatabase, table, fields), table)
                for (table in listOf("reminder_settings", "reminder_rules", "reminder_days")) migrated.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
                assertEquals(receipt, RoomLedgerRepository(migrated, clock, contexts).receipt(context, id(150)))
                val repository = RoomReminderRepository(migrated, clock, contexts)
                assertFalse(repository.configuration(context).enabled)
                repository.setEnabled(context, 0, true)
                migrated.close()
                migrated = createLedgerDatabase(app, targetName)
                assertTrue(RoomReminderRepository(migrated, clock, contexts).configuration(context).enabled)
                for ((table, fields) in columns) assertEquals(expected.getValue(table), readColumns(migrated.openHelper.writableDatabase, table, fields), table)
            } finally {
                migrated.close()
            }
        } finally {
            source.close()
            app.deleteDatabase(sourceName)
            app.deleteDatabase(targetName)
        }
    }
}

private fun readColumns(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String, fields: List<String>): List<List<String?>> = db.query("SELECT ${fields.joinToString(",")} FROM $table ORDER BY rowid").use { cursor ->
    buildList { while (cursor.moveToNext()) add(fields.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
}
