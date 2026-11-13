package com.monit.core.data.importing

import com.monit.core.data.LedgerDatabase
import com.monit.core.data.readLargeSqlText
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem

/** root 원문은 다른 작은 열과 분리한다. 원래 문자열/해시를 재작성하지 않는다. */
internal suspend fun readImportBatches(database: LedgerDatabase, ledger: String, batchId: String? = null): List<ImportBatchEntity> {
    val sql = database.openHelper.readableDatabase
    val where = if (batchId == null) "ledgerId=?" else "ledgerId=? AND batchId=?"
    val metadata = sql.query("SELECT ledgerId,batchId,storageGeneration,requestSha256,originalId,expectedRevision,fingerprint,status,resultJson,createdAt,committedAt,acknowledged,evidenceOnly,length(CAST(requestJson AS BLOB)) FROM import_batches WHERE $where ORDER BY createdAt,batchId", if (batchId == null) arrayOf(ledger) else arrayOf(ledger, batchId)).use { cursor ->
        var total = 0L
        buildList {
            while (cursor.moveToNext()) {
                check(cursor.getInt(11) in 0..1 && cursor.getInt(12) in 0..1)
                val length = cursor.getLong(13)
                if (length !in 0..8 * 1024 * 1024L) throw BackupException(BackupProblem.LIMIT)
                total = Math.addExact(total, length)
                if (size >= 50_000 || total > 8 * 1024 * 1024L) throw BackupException(BackupProblem.LIMIT)
                add(ImportBatchEntity(cursor.getString(0), cursor.getString(1), cursor.getString(2), "", cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getString(6), cursor.getString(7), if (cursor.isNull(8)) null else cursor.getString(8), cursor.getString(9), if (cursor.isNull(10)) null else cursor.getString(10), cursor.getInt(11) != 0, cursor.getInt(12) != 0))
            }
        }
    }
    return metadata.map { value -> value.copy(requestJson = readLargeSqlText(sql, "import_batches", "requestJson", linkedMapOf("ledgerId" to ledger, "batchId" to value.batchId), 8 * 1024 * 1024)) }
}

internal suspend fun readImportBatch(database: LedgerDatabase, ledger: String, batchId: String): ImportBatchEntity? = readImportBatches(database, ledger, batchId).singleOrNull()
