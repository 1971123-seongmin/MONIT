package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 알림 선호·재조정·하루 요약 식별자만 추가한다. 기존 금융·제출·초안은 변경하지 않는다. */
internal val LedgerMigration5To6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_rules (ledgerId TEXT NOT NULL, storageGeneration TEXT NOT NULL, ruleId TEXT NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(ledgerId,ruleId), FOREIGN KEY(ledgerId,ruleId) REFERENCES payment_rules(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_settings (ledgerId TEXT NOT NULL, storageGeneration TEXT NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL, dirty INTEGER NOT NULL, failed INTEGER NOT NULL, syncToken TEXT, nextAt TEXT, appliedLedgerRevision INTEGER NOT NULL, PRIMARY KEY(ledgerId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_days (ledgerId TEXT NOT NULL, storageGeneration TEXT NOT NULL, day TEXT NOT NULL, token TEXT NOT NULL, status TEXT NOT NULL, membersJson TEXT NOT NULL, createdAt TEXT NOT NULL, postedAt TEXT, PRIMARY KEY(ledgerId,storageGeneration,day), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reminder_days_ledgerId_token ON reminder_days(ledgerId,token)")
    }
}
