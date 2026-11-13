package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 원장·canonical 원문은 바꾸지 않고 원자 이관의 근거만 추가한다. */
internal val LedgerMigration8To9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS import_batches (ledgerId TEXT NOT NULL, batchId TEXT NOT NULL, storageGeneration TEXT NOT NULL, requestJson TEXT NOT NULL, requestSha256 TEXT NOT NULL, originalId TEXT NOT NULL, expectedRevision INTEGER NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL, resultJson TEXT, createdAt TEXT NOT NULL, committedAt TEXT, acknowledged INTEGER NOT NULL, evidenceOnly INTEGER NOT NULL, PRIMARY KEY(ledgerId,batchId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_rows (ledgerId TEXT NOT NULL, batchId TEXT NOT NULL, locator TEXT NOT NULL, position INTEGER NOT NULL, identity TEXT NOT NULL, keyJson TEXT NOT NULL, rawSha256 TEXT NOT NULL, decision TEXT NOT NULL, reasonsJson TEXT NOT NULL, groupId TEXT, transactionId TEXT, PRIMARY KEY(ledgerId,batchId,locator), FOREIGN KEY(ledgerId,batchId) REFERENCES import_batches(ledgerId,batchId) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_source_mappings (ledgerId TEXT NOT NULL, identity TEXT NOT NULL, keyJson TEXT NOT NULL, rawSha256 TEXT NOT NULL, fileSha256 TEXT NOT NULL, snapshotSha256 TEXT NOT NULL, fileMappingSha256 TEXT NOT NULL, mappingSha256 TEXT NOT NULL, locator TEXT NOT NULL, position INTEGER NOT NULL, originalId TEXT NOT NULL, batchId TEXT NOT NULL, storageGeneration TEXT NOT NULL, groupId TEXT NOT NULL, transactionId TEXT NOT NULL, appliedRevision INTEGER NOT NULL, appliedFinancialRevision INTEGER NOT NULL, payloadJson TEXT NOT NULL, payloadSha256 TEXT NOT NULL, PRIMARY KEY(ledgerId,identity), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_child_commands (ledgerId TEXT NOT NULL, batchId TEXT NOT NULL, operationId TEXT NOT NULL, requestSha256 TEXT NOT NULL, kind TEXT NOT NULL, entityId TEXT NOT NULL, PRIMARY KEY(ledgerId,operationId), FOREIGN KEY(ledgerId,batchId) REFERENCES import_batches(ledgerId,batchId) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_import_child_commands_ledgerId_batchId ON import_child_commands(ledgerId,batchId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_source_fences (identity TEXT NOT NULL, keyJson TEXT NOT NULL, ledgerId TEXT NOT NULL, storageGeneration TEXT NOT NULL, rawSha256 TEXT NOT NULL, fileSha256 TEXT NOT NULL, snapshotSha256 TEXT NOT NULL, fileMappingSha256 TEXT NOT NULL, locator TEXT NOT NULL, position INTEGER NOT NULL, batchId TEXT NOT NULL, transactionId TEXT NOT NULL, payloadSha256 TEXT NOT NULL, PRIMARY KEY(identity))")
        db.execSQL("ALTER TABLE backup_status ADD COLUMN lastBackupExportId TEXT")
        db.execSQL("ALTER TABLE backup_status ADD COLUMN lastBackupRevision INTEGER")
        db.execSQL("ALTER TABLE backup_status ADD COLUMN importCoverage TEXT")
    }
}
