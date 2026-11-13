package com.monit.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 호출자는 읽기 트랜잭션을 소유한다. CursorWindow보다 큰 단일 BLOB/TEXT도 바이트 단위로 읽는다. */
internal suspend fun readLargeSqlValue(sql: SupportSQLiteDatabase, table: String, column: String, key: Map<String, String>, maximum: Int): ByteArray {
    require((table == "backup_originals" && column == "bytes" && key.keys == setOf("ledgerId", "id")) || (table == "import_batches" && column == "requestJson" && key.keys == setOf("ledgerId", "batchId")))
    val where = key.keys.joinToString(" AND ") { "$it=?" }
    val length = sql.query("SELECT length(CAST($column AS BLOB)) FROM $table WHERE $where", key.values.toTypedArray()).use { cursor ->
        check(cursor.moveToFirst() && !cursor.isNull(0))
        cursor.getLong(0).also { check(!cursor.moveToNext()) }
    }
    if (length !in 0..maximum.toLong()) throw BackupException(BackupProblem.LIMIT)
    val result = ByteArray(length.toInt())
    try {
        var offset = 0
        while (offset < result.size) {
            currentCoroutineContext().ensureActive()
            val size = minOf(128 * 1024, result.size - offset)
            val chunk = sql.query("SELECT substr(CAST($column AS BLOB),?,?) FROM $table WHERE $where", (listOf(offset + 1, size) + key.values).toTypedArray()).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getBlob(0).also { check(!cursor.moveToNext() && it.size == size) }
            }
            try {
                chunk.copyInto(result, offset)
            } finally {
                chunk.fill(0)
            }
            offset += size
        }
        currentCoroutineContext().ensureActive()
        return result
    } catch (error: Throwable) {
        result.fill(0)
        throw error
    }
}

internal suspend fun readLargeSqlText(sql: SupportSQLiteDatabase, table: String, column: String, key: Map<String, String>, maximum: Int): String {
    val bytes = readLargeSqlValue(sql, table, column, key, maximum)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } finally {
        bytes.fill(0)
    }
}
