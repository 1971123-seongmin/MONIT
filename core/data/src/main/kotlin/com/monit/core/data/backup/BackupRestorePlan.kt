package com.monit.core.data.backup

import android.content.ContentValues
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.data.*
import com.monit.core.model.*
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 변환/파싱은 commit 전에 끝내고, 관계 순서의 삭제·삽입만 동일 트랜잭션 안에서 실행한다. */
internal class BackupRestorePlan(private val source: ValidatedBackup, private val result: RestoreResult, previousOperations: Set<String>, private val sourceFences: List<JSONObject> = source.root.getJSONObject("imports").list("import_source_fences")) {
    private val financial = BackupLayout.finance.associateWith { table -> source.rows(table).map { row -> values(table, row) } }
    private val evidence = source.root.list("submissionsEvidence").map { RestoredEvidenceEntity(result.context.ledgerId, it.text("operationId"), it.toString()) }
    private val fences = (previousOperations + source.root.getJSONArray("operationFences").values().map { it as String } + evidence.map { it.operationId }).map { OperationFenceEntity(requireLedgerId(it)) }
    private val originals = source.root.list("originals").map { BackupOriginalEntity(result.context.ledgerId, it.text("id"), it.text("sourceNamespace"), it.text("fileName"), it.exactLong("byteLength"), it.text("sha256"), Base64.getDecoder().decode(it.text("bytesBase64"))) }
    private val preferences = source.root.getJSONObject("reminderPreferences")
    private val global = preferences.getJSONObject("global").let { value -> if (!value.getBoolean("present")) null else ReminderSettingsEntity(result.context.ledgerId, result.context.storageGeneration, value.getBoolean("enabled"), value.exactLong("revision"), true, false, null, null, 0) }
    private val rules = preferences.list("rules").map { ReminderRuleEntity(result.context.ledgerId, result.context.storageGeneration, it.text("ruleId"), it.getBoolean("enabled"), it.exactLong("revision")) }
    private val status = BackupStatusEntity(lastBackupAt = null, lastBackupLedger = null, lastBackupGeneration = null, restoreResultJson = encodeRestoreResult(result), restoreAcknowledged = false, submissionCoverage = source.root.getJSONObject("manifest").text("submissionCoverage"), reminderCoverage = preferences.text("coverage"), sourceMappingsCoverage = source.root.getJSONObject("manifest").text("sourceMappings"), importCoverage = source.root.getJSONObject("imports").text("coverage"))

    suspend fun apply(database: LedgerDatabase, probe: BackupWorkProbe = BackupWorkProbe()) {
        val sql = database.openHelper.writableDatabase
        // operation_fences는 전체대체에서도 삭제하지 않는다. 이전 화면 밖의 UUID도 재사용할 수 없다.
        for (fence in fences) database.backupDao().fence(fence)
        for (table in deletionOrder) {
            currentCoroutineContext().ensureActive()
            sql.execSQL("DELETE FROM $table")
        }
        database.ledgerDao().insertLedger(LedgerEntity(result.context.ledgerId, result.context.storageGeneration, Math.addExact(source.revision, 1)))
        for (table in BackupLayout.finance) {
            for (row in financial.getValue(table)) {
                currentCoroutineContext().ensureActive()
                sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, row)
            }
            if (table == "accounts") probe.checkpoint(BackupCheckpoint.AFTER_PARTIAL_REPLACE)
        }
        for (value in evidence) database.backupDao().evidence(value)
        for (value in originals) database.backupDao().original(value)
        val imports = source.root.getJSONObject("imports")
        for (table in BackupLayout.importTables.filter { it != "import_source_fences" }) for (row in imports.list(table)) {
            val value = values(table, row)
            if (table == "import_batches") {
                value.put("evidenceOnly", true)
                value.put("acknowledged", true)
            }
            sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, value)
        }
        for (row in sourceFences) sql.insert("import_source_fences", android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE, values("import_source_fences", row))
        global?.let { database.reminderDao().insertSettings(it) }
        for (value in rules) database.reminderDao().insertRule(value)
        database.backupDao().status(status)
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
        currentCoroutineContext().ensureActive()
    }
    fun clear() {
        originals.forEach { it.bytes.fill(0) }
    }

    companion object {
        private val deletionOrder = listOf("import_child_commands", "import_rows", "import_source_mappings", "import_batches", "form_drafts", "reminder_days", "reminder_rules", "reminder_settings", "restored_evidence", "backup_originals", "anchor_rejections", "anchor_history", "payment_rejections", "payment_intents", "payment_events", "payment_items", "payment_rule_versions", "payment_rules", "submission_rejections", "transaction_history", "transactions", "anchors", "operations", "submissions", "submission_references", "accounts", "ledgers")
        private fun values(table: String, row: JSONObject) = ContentValues().apply {
            for (field in BackupLayout.properties(table).keys().asSequence()) when {
                row.isNull(field) -> putNull(field)
                BackupLayout.boolean(table, field) -> put(field, row.getBoolean(field))
                BackupLayout.integral(table, field) -> put(field, row.exactLong(field))
                BackupLayout.integer(table, field) -> put(field, row.getInt(field))
                else -> put(field, row.text(field))
            }
        }
    }
}

internal fun encodeRestoreResult(value: RestoreResult): String = JSONObject().put("id", value.id).put("ledgerId", value.context.ledgerId).put("storageGeneration", value.context.storageGeneration).put("sourceCreatedAt", value.sourceCreatedAt).put("committedAt", value.committedAt).put("recordTotal", value.recordTotal).put("legacyEvidenceMissing", value.legacyEvidenceMissing).put("notificationResetNeeded", value.notificationResetNeeded).toString()
internal suspend fun decodeRestoreResult(raw: String): RestoreResult {
    val json = BackupJson.parseText(raw)
    json.fields("id", "ledgerId", "storageGeneration", "sourceCreatedAt", "committedAt", "recordTotal", "legacyEvidenceMissing", "notificationResetNeeded")
    return RestoreResult(requireLedgerId(json.text("id")), LedgerContext(json.text("ledgerId"), json.text("storageGeneration")), json.instant("sourceCreatedAt").toString(), json.instant("committedAt").toString(), json.getInt("recordTotal").also { require(it in 0..50_000) }, json.getBoolean("legacyEvidenceMissing"), json.getBoolean("notificationResetNeeded"))
}
