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
class AnchorMigrationTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }

    /** 목적: 실제 schema6의 모든 열과 원래 context/raw/hash/초안·알림 선호를 비파괴 승격한다.
     * 조건: 현 writer로 만든 금융/거절/초안/알림 자료를 checked-in6 DDL 파일로 복사한다.
     * 절차: user_version6 파일을 최신 schema8로 열어 기준액 명시확정·재열기한다.
     * 기대: 기존 모든 열/행 동일, 새테이블 빈 상태→이력1, 이전 계좌receipt 및 알림설정 보존.
     */
    @Test fun actualVersionSixPreservesAllExistingColumnsAndAllowsExplicitAnchorConfirmation() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val source = createLedgerDatabase(app, "anchor-source.db")
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/6.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { n ->
            val e = entities.getJSONObject(n)
            val fields = e.getJSONArray("fields")
            e.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        fun rows(database: LedgerDatabase): Map<String, List<List<String?>>> = columns.mapValues { (table, fields) -> database.openHelper.writableDatabase.query("SELECT ${fields.joinToString(",")} FROM $table ORDER BY rowid").use { cursor -> buildList { while (cursor.moveToNext()) add(fields.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }) } } }
        try {
            val ledger = RoomLedgerRepository(source, clock, contexts)
            ledger.initialize(context)
            val account = CreateAccount(id(10), id(11), "합성 계좌", AccountNature.GENERAL, LocalDate.of(2026, 11, 10), "미확인")
            val receipt = ledger.createAccount(context, id(110), account)
            ledger.createTransaction(context, id(130), CreateTransaction(id(30), TransactionKind.INCOME, account.anchorDate.plusDays(1), 300, creditAccountId = account.id))
            val reminders = RoomReminderRepository(source, clock, contexts)
            reminders.setEnabled(context, 0, true)
            RoomDraftRepository(source, ledger, contexts).saveDraft(context, id(300), 0, DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS), DraftContent.Transaction(TransactionKind.EXPENSE, "00.입력중", "2026-", id(10), null, " 원문\n"))
            val expected = rows(source)
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("anchor-v6.db"), null).use { target ->
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i)
                    val table = e.getString("tableName")
                    target.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = e.optJSONArray("indices")
                    if (indices != null) for (n in 0 until indices.length()) target.execSQL(indices.getJSONObject(n).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) target.execSQL(setup.getString(i))
                for ((table, fields) in columns) for (row in expected.getValue(table)) target.execSQL("INSERT INTO $table (${fields.joinToString(",")}) VALUES (${fields.joinToString(",") { "?" }})", row.toTypedArray())
                target.version = 6
            }
            var migrated = createLedgerDatabase(app, "anchor-v6.db")
            try {
                assertEquals(9, migrated.openHelper.writableDatabase.version)
                assertEquals(expected, rows(migrated))
                var upgraded = RoomLedgerRepository(migrated, clock, contexts)
                assertEquals(receipt, upgraded.receipt(context, id(110)))
                assertTrue(upgraded.anchorHistory(context, id(10)).isEmpty())
                upgraded.confirmAnchor(context, id(200), ConfirmAnchor(id(10), id(11), 1, account.anchorDate, 0, "합성 마감 확인"))
                migrated.close()
                migrated = createLedgerDatabase(app, "anchor-v6.db")
                upgraded = RoomLedgerRepository(migrated, clock, contexts)
                assertEquals(300, upgraded.snapshot(context, LocalDate.of(2026, 11, 13)).balances[id(10)])
                assertEquals(1, upgraded.anchorHistory(context, id(10)).size)
                assertEquals(receipt, upgraded.receipt(context, id(110)))
                assertTrue(RoomReminderRepository(migrated, clock, contexts).configuration(context).enabled)
                assertIs<DraftSnapshot.Editable>(RoomDraftRepository(migrated, upgraded, contexts).draft(context, id(300)))
            } finally {
                migrated.close()
            }
        } finally {
            source.close()
            app.deleteDatabase("anchor-source.db")
            app.deleteDatabase("anchor-v6.db")
        }
    }
}
