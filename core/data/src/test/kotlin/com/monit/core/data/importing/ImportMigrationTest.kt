package com.monit.core.data.importing

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.data.backup.*
import com.monit.core.model.*
import java.io.File
import java.time.*
import java.util.Base64
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
class ImportMigrationTest {
    /** 목적: 실제 checked-in schema8 파일의 모든 기존 열·원문 BLOB을 Room9가 비파괴 승격한다.
     * 조건: 합성 계좌/기준액/거래 canonical 명령과 한글·NUL 원본, 이전 백업 시각을 가진 schema8 DB.
     * 절차: schema8 DDL과 기존 열만 복사→8→9 open→전 열 비교/재열기/backup capture한다.
     * 기대: 원문/해시·모든 기존 열 불변, 새 테이블0, 영수증 없음·import 미기록이며 시각만으로 백업 승인을 받지 않는다.
     */
    @Test fun actualVersionEightFilePreservesAllColumnsAndBinaryOriginal() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val context = ImportPreviewFixture.context
        val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
        val contexts = object : CoroutineContextProvider {
            override val main = Dispatchers.Main
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        val source = createLedgerDatabase(app, "import-migration-source.db")
        val schema = JSONObject(File(System.getProperty("monit.roomSchemas"), "com.monit.core.data.LedgerDatabase/8.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities").values().map { it as JSONObject }
        val columns = entities.associate { row -> row.text("tableName") to row.getJSONArray("fields").values().map { (it as JSONObject).text("columnName") } }
        fun rows(sql: SupportSQLiteDatabase, table: String, fields: List<String>): List<List<Any?>> = sql.query("SELECT ${fields.joinToString(",")} FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    fields.indices.map { index ->
                        when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                            else -> cursor.getString(index)
                        }
                    },
                )
            }
        }
        fun normalized(values: List<List<Any?>>) = values.map { row -> row.map { if (it is ByteArray) Base64.getEncoder().encodeToString(it) else it } }
        try {
            val ledger = RoomLedgerRepository(source, clock, contexts)
            ledger.initialize(context)
            ledger.createAccount(context, ImportPreviewFixture.id(110), CreateAccount(ImportPreviewFixture.id(10), ImportPreviewFixture.id(20), "합성 계좌", AccountNature.GENERAL, LocalDate.of(2026, 1, 1), "미확인"))
            ledger.confirmAnchor(context, ImportPreviewFixture.id(111), ConfirmAnchor(ImportPreviewFixture.id(10), ImportPreviewFixture.id(20), 1, LocalDate.of(2026, 1, 1), 1000, ""))
            ledger.createTransaction(context, ImportPreviewFixture.id(112), CreateTransaction(ImportPreviewFixture.id(30), TransactionKind.INCOME, LocalDate.of(2026, 11, 2), 10, creditAccountId = ImportPreviewFixture.id(10)))
            val raw = "합성\u0000원문\n".toByteArray()
            source.backupDao().original(BackupOriginalEntity(context.ledgerId, ImportPreviewFixture.id(900), "synthetic", "original.bin", raw.size.toLong(), BackupJson.digest(raw), raw))
            val status = checkNotNull(source.backupDao().status())
            source.backupDao().status(status.copy(lastBackupAt = clock.instant().toString(), lastBackupLedger = context.ledgerId, lastBackupGeneration = context.storageGeneration, lastBackupExportId = ImportPreviewFixture.id(901), lastBackupRevision = ledger.snapshot(context, LocalDate.of(2026, 11, 13)).revision))
            val expected = columns.mapValues { (table, fields) -> rows(source.openHelper.writableDatabase, table, fields) }
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("import-v8.db"), null).use { target ->
                for (entity in entities) {
                    val table = entity.text("tableName")
                    target.execSQL(entity.text("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.values().orEmpty().forEach { target.execSQL((it as JSONObject).text("createSql").replace("\${TABLE_NAME}", table)) }
                }
                schema.getJSONArray("setupQueries").values().forEach { target.execSQL(it as String) }
                for ((table, fields) in columns) for (row in expected.getValue(table)) target.execSQL("INSERT INTO $table (${fields.joinToString(",")}) VALUES (${fields.joinToString(",") { "?" }})", row.toTypedArray())
                target.version = 8
            }
            repeat(2) {
                val migrated = createLedgerDatabase(app, "import-v8.db")
                try {
                    assertEquals(9, migrated.openHelper.writableDatabase.version)
                    for ((table, fields) in columns) assertEquals(normalized(expected.getValue(table)), normalized(rows(migrated.openHelper.writableDatabase, table, fields)), table)
                    assertNull(migrated.backupDao().status()?.lastBackupExportId)
                    assertNull(migrated.backupDao().status()?.lastBackupRevision)
                    assertNull(migrated.backupDao().status()?.importCoverage)
                    for (table in BackupLayout.importTables) migrated.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                    val root = BackupSnapshotAdapter(migrated, clock).capture(context)
                    assertEquals("NOT_RECORDED_BY_V2", root.getJSONObject("imports").text("coverage"))
                    BackupValidator(clock).validate(root.toString().toByteArray())
                    val writer = RoomImportRepository(migrated, RoomLedgerRepository(migrated, clock, contexts), clock, contexts)
                    val review = writer.review(context, ImportPreviewFixture.bytes())
                    assertEquals(ImportFileProblem.BACKUP_REQUIRED, assertFailsWith<ImportFileException> { writer.bindBackup(context, review.token, ImportPreviewFixture.id(901)) }.problem)
                    migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
                } finally {
                    migrated.close()
                }
            }
        } finally {
            source.close()
            app.deleteDatabase("import-migration-source.db")
            app.deleteDatabase("import-v8.db")
        }
    }
}
