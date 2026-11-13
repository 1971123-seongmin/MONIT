package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 anchor·원문·hash는 그대로 두고 명시 확인의 이력과 충돌 결과만 추가한다. */
internal val LedgerMigration6To7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS anchor_history (ledgerId TEXT NOT NULL, anchorId TEXT NOT NULL, revision INTEGER NOT NULL, accountId TEXT NOT NULL, operationId TEXT NOT NULL, date TEXT NOT NULL, beforeAmount INTEGER, beforeConfirmedAt TEXT, beforeUnknownReason TEXT, beforeRevision INTEGER NOT NULL, amount INTEGER NOT NULL, reason TEXT NOT NULL, confirmedAt TEXT NOT NULL, PRIMARY KEY(ledgerId,anchorId,revision), FOREIGN KEY(ledgerId,anchorId) REFERENCES anchors(ledgerId,id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE TABLE IF NOT EXISTS anchor_rejections (ledgerId TEXT NOT NULL, operationId TEXT NOT NULL, storageGeneration TEXT NOT NULL, requestJson TEXT NOT NULL, requestSha256 TEXT NOT NULL, anchorId TEXT NOT NULL, expectedRevision INTEGER NOT NULL, actualRevision INTEGER NOT NULL, rejectedAt TEXT NOT NULL, PRIMARY KEY(ledgerId,operationId), FOREIGN KEY(ledgerId) REFERENCES ledgers(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
    }
}
