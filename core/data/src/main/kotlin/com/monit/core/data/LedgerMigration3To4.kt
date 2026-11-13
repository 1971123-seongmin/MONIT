package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 금융값·원문·초안·revision을 보존하고 과거 이력은 역생성하지 않는다. */
internal val LedgerMigration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAt TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS submission_rejections (
                ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, storageGeneration TEXT NOT NULL,
                requestJson TEXT NOT NULL, requestSha256 TEXT NOT NULL, transactionId TEXT NOT NULL,
                expectedRevision INTEGER NOT NULL, actualRevision INTEGER NOT NULL, actualDeletedAt TEXT, rejectedAt TEXT NOT NULL,
                PRIMARY KEY(ledgerId, operationId),
                FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_history (
                ledgerId TEXT NOT NULL, id TEXT NOT NULL, kind TEXT NOT NULL,
                date TEXT NOT NULL, amount INTEGER NOT NULL, debitAccountId TEXT,
                creditAccountId TEXT, debitNature TEXT, creditNature TEXT, memo TEXT NOT NULL,
                revision INTEGER NOT NULL, financialRevision INTEGER NOT NULL, deletedAt TEXT,
                operationId TEXT NOT NULL, changedAt TEXT NOT NULL, changeKind TEXT NOT NULL,
                PRIMARY KEY(ledgerId, id, revision),
                FOREIGN KEY(ledgerId, id) REFERENCES transactions(ledgerId, id) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
    }
}
