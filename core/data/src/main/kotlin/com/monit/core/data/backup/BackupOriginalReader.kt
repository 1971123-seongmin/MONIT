package com.monit.core.data.backup

import com.monit.core.data.LedgerDatabase
import com.monit.core.data.readLargeSqlValue
import com.monit.core.model.BackupProblem

/** 원본 크기를 metadata에서 먼저 검사하고 큰 BLOB를 CursorWindow 한 행에 올리지 않는다. */
internal suspend fun readBackupOriginals(database: LedgerDatabase, ledger: String): List<BackupOriginalEntity> {
    val sql = database.openHelper.readableDatabase
    val metadata = sql.query("SELECT id,sourceNamespace,fileName,byteLength,sha256,length(bytes) FROM backup_originals WHERE ledgerId=? ORDER BY id", arrayOf(ledger)).use { cursor ->
        var total = 0L
        buildList {
            while (cursor.moveToNext()) {
                val length = cursor.getLong(3)
                if (size >= 32 || length !in 0..2 * 1024 * 1024L || length != cursor.getLong(5)) BackupJson.fail(BackupProblem.LIMIT)
                total = Math.addExact(total, length)
                if (total > 2 * 1024 * 1024L) BackupJson.fail(BackupProblem.LIMIT)
                add(BackupOriginalEntity(ledger, cursor.getString(0), cursor.getString(1), cursor.getString(2), length, cursor.getString(4), byteArrayOf()))
            }
        }
    }
    return metadata.map { value ->
        val bytes = readLargeSqlValue(sql, "backup_originals", "bytes", linkedMapOf("ledgerId" to ledger, "id" to value.id), 2 * 1024 * 1024)
        if (BackupJson.digest(bytes) != value.sha256) {
            bytes.fill(0)
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        }
        value.copy(bytes = bytes)
    }
}
