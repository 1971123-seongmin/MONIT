package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 금융·원문을 변경하지 않고 복원 근거·재실행 fence·원본·로컬 결과만 추가한다. */
internal val LedgerMigration7To8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS restored_evidence (ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, evidenceJson TEXT NOT NULL, PRIMARY KEY(ledgerId,operationId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS operation_fences (operationId TEXT NOT NULL, PRIMARY KEY(operationId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_originals (ledgerId TEXT NOT NULL, id TEXT NOT NULL, sourceNamespace TEXT NOT NULL, fileName TEXT NOT NULL, byteLength INTEGER NOT NULL, sha256 TEXT NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(ledgerId,id), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_status (id TEXT NOT NULL, lastBackupAt TEXT, lastBackupLedger TEXT, lastBackupGeneration TEXT, restoreResultJson TEXT, restoreAcknowledged INTEGER NOT NULL, submissionCoverage TEXT NOT NULL, reminderCoverage TEXT NOT NULL, sourceMappingsCoverage TEXT NOT NULL, PRIMARY KEY(id))")
    }
}
