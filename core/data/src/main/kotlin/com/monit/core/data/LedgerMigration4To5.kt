package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 v4 행·원문·삭제/초안/거절 이력은 변경하지 않는다. */
internal val LedgerMigration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_rules (ledgerId TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL, startMonth TEXT NOT NULL, endMonth TEXT, PRIMARY KEY(ledgerId,id), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_rule_versions (ledgerId TEXT NOT NULL, ruleId TEXT NOT NULL, revision INTEGER NOT NULL, effectiveMonth TEXT NOT NULL, termsJson TEXT NOT NULL, PRIMARY KEY(ledgerId,ruleId,revision), FOREIGN KEY(ledgerId,ruleId) REFERENCES payment_rules(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_items (ledgerId TEXT NOT NULL, id TEXT NOT NULL, ruleId TEXT NOT NULL, month TEXT NOT NULL, revision INTEGER NOT NULL, ruleVersion INTEGER NOT NULL, termsJson TEXT NOT NULL, dueDate TEXT NOT NULL, status TEXT NOT NULL, active INTEGER NOT NULL, transactionId TEXT, confirmedFinancialRevision INTEGER, confirmedJson TEXT, PRIMARY KEY(ledgerId,id), FOREIGN KEY(ledgerId,ruleId) REFERENCES payment_rules(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(ledgerId,transactionId) REFERENCES transactions(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payment_items_ledgerId_ruleId_month ON payment_items(ledgerId,ruleId,month)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payment_items_ledgerId_transactionId ON payment_items(ledgerId,transactionId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_events (ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, itemId TEXT NOT NULL, kind TEXT NOT NULL, transactionId TEXT, full INTEGER, reason TEXT NOT NULL, beforeJson TEXT, afterJson TEXT NOT NULL, changedAt TEXT NOT NULL, PRIMARY KEY(ledgerId,operationId,itemId), FOREIGN KEY(ledgerId,itemId) REFERENCES payment_items(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_events_ledgerId_transactionId ON payment_events(ledgerId,transactionId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_events_ledgerId_itemId ON payment_events(ledgerId,itemId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_rejections (ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, storageGeneration TEXT NOT NULL, requestJson TEXT NOT NULL, requestSha256 TEXT NOT NULL, collection TEXT NOT NULL, targetId TEXT NOT NULL, expectedRevision INTEGER NOT NULL, actualRevision INTEGER NOT NULL, rejectedAt TEXT NOT NULL, PRIMARY KEY(ledgerId,operationId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS payment_intents (ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, storageGeneration TEXT NOT NULL, requestJson TEXT NOT NULL, requestSha256 TEXT NOT NULL, status TEXT NOT NULL, draftId TEXT, draftRevision INTEGER, draftScopeJson TEXT, PRIMARY KEY(ledgerId,operationId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
    }
}
