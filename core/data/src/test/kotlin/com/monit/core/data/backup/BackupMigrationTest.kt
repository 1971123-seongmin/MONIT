package com.monit.core.data.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
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
class BackupMigrationTest {
    /** 목적: checked-in schema7 파일의 모든 열·행·원문·참조·초안·알림을 손실 없이 schema8로 연다.
     * 조건: 합성 계좌/기준액/거래/초안/알림이 들어 있는 실제 파일 DB다.
     * 절차: schema7 DDL 파일에 기존 열을 복사→7→8 open→열 전체 대조→닫고 재열기한다.
     * 기대: 기존 데이터와 generation 동일, 추가 네 테이블은 비어 있고 FK 위반이 없다.
     */
    @Test fun actualVersionSevenPreservesEveryExistingColumnAndAddsEmptyBackupTables() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val contexts = object : CoroutineContextProvider {
            override val main = Dispatchers.Main
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
        fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
        val context = LedgerContext(id(900), id(901))
        val source = createLedgerDatabase(app, "backup-migration-source.db")
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/7.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { n ->
            val e = entities.getJSONObject(n)
            e.text("tableName") to e.getJSONArray("fields").values().map { (it as JSONObject).text("columnName") }
        }
        fun rows(db: LedgerDatabase) = columns.mapValues { (table, fields) -> db.openHelper.writableDatabase.query("SELECT ${fields.joinToString(",")} FROM $table ORDER BY rowid").use { cursor -> buildList { while (cursor.moveToNext()) add(fields.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }) } } }
        try {
            val ledger = RoomLedgerRepository(source, clock, contexts)
            ledger.initialize(context)
            ledger.createAccount(context, id(110), CreateAccount(id(10), id(11), "합성 계좌", AccountNature.GENERAL, LocalDate.of(2026, 11, 10), "미확인"))
            ledger.confirmAnchor(context, id(111), ConfirmAnchor(id(10), id(11), 1, LocalDate.of(2026, 11, 10), 1000, ""))
            ledger.createTransaction(context, id(130), CreateTransaction(id(30), TransactionKind.INCOME, LocalDate.of(2026, 11, 12), 300, creditAccountId = id(10)))
            RoomReminderRepository(source, clock, contexts).setEnabled(context, 0, true)
            RoomDraftRepository(source, ledger, contexts).saveDraft(context, id(300), 0, DraftScope(DraftKind.TRANSACTION, DraftOrigin.TRANSACTIONS), DraftContent.Transaction(TransactionKind.EXPENSE, "00.입력중", "2026-", id(10), null, "합성 원문"))
            val expected = rows(source)
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("backup-v7.db"), null).use { target ->
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.text("tableName")
                    target.execSQL(entity.text("createSql").replace("\${TABLE_NAME}", table))
                    for (index in entity.optJSONArray("indices")?.values().orEmpty()) target.execSQL((index as JSONObject).text("createSql").replace("\${TABLE_NAME}", table))
                }
                for (query in schema.getJSONArray("setupQueries").values()) target.execSQL(query as String)
                for ((table, fields) in columns) for (row in expected.getValue(table)) target.execSQL("INSERT INTO $table (${fields.joinToString(",")}) VALUES (${fields.joinToString(",") { "?" }})", row.toTypedArray())
                target.version = 7
            }
            repeat(2) {
                val migrated = createLedgerDatabase(app, "backup-v7.db")
                try {
                    val sql = migrated.openHelper.writableDatabase
                    assertEquals(9, sql.version)
                    assertEquals(expected, rows(migrated))
                    for (table in listOf("restored_evidence", "operation_fences", "backup_originals", "backup_status")) sql.query("SELECT count(*) FROM $table").use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    assertEquals(context, (RoomLedgerRepository(migrated, clock, contexts).openLedger() as LedgerEntryResult.Ready).context)
                } finally {
                    migrated.close()
                }
            }
        } finally {
            source.close()
            app.deleteDatabase("backup-migration-source.db")
            app.deleteDatabase("backup-v7.db")
        }
    }
}
