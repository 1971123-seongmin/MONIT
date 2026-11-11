package com.monit.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 기존 여섯 테이블을 보존하며 과거 성공 원장을 활성 참조로 역생성하지 않는다. */
internal object LedgerMigration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `submission_references` (
                `ledgerId` TEXT NOT NULL, `operationId` TEXT NOT NULL, `storageGeneration` TEXT NOT NULL,
                PRIMARY KEY(`ledgerId`, `operationId`),
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
    }
}
