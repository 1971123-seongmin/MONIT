package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 금융/제출 행은 수정하지 않고 금융 집계와 분리된 초안 표만 추가한다. */
internal object LedgerMigration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `form_drafts` (
                `ledgerId` TEXT NOT NULL, `storageGeneration` TEXT NOT NULL, `id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL, `scopeJson` TEXT NOT NULL,
                `payloadJson` TEXT, `payloadSha256` TEXT, `status` TEXT NOT NULL, `operationId` TEXT,
                PRIMARY KEY(`ledgerId`, `id`),
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_form_drafts_ledgerId_operationId` ON `form_drafts` (`ledgerId`, `operationId`)")
    }
}
