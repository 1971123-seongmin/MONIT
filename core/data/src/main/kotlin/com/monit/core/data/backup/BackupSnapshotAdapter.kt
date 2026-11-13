package com.monit.core.data.backup

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monit.core.data.LedgerDatabase
import com.monit.core.model.*
import java.time.Clock
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal object BackupLayout {
    val importTables = listOf("import_batches", "import_rows", "import_source_mappings", "import_child_commands", "import_source_fences")
    val finance = listOf("accounts", "anchors", "transactions", "transaction_history", "payment_rules", "payment_rule_versions", "payment_items", "payment_events", "anchor_history")
    val evidence = listOf("operations", "submissions", "submission_references", "submission_rejections", "payment_rejections", "payment_intents", "anchor_rejections")
    val excludes = listOf("UI_DRAFT_PAYLOADS", "PASSWORDS", "DERIVED_CACHES", "OS_PERMISSIONS", "OS_ALARMS", "NOTIFICATION_TOKENS", "TEMP_FILES")
    private val schema by lazy { JSONObject(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v3.schema.json")).use { it.reader(Charsets.UTF_8).readText() }) }
    fun properties(table: String): JSONObject {
        require(table in finance || table in evidence || table in importTables)
        return schema.getJSONObject("\$defs").getJSONObject(table).getJSONObject("properties")
    }
    fun definition(table: String): JSONObject = schema.getJSONObject("\$defs").getJSONObject(table)
    fun boolean(table: String, field: String): Boolean = properties(table).getJSONObject(field).toString().contains("\"boolean\"")
    fun integral(table: String, field: String): Boolean = properties(table).getJSONObject(field).toString().contains("maxLength\":20")
    fun integer(table: String, field: String): Boolean = properties(table).getJSONObject(field).optString("type") == "integer"
}

/** Room 파일 바이트가 아닌 고정 논리 컬렉션을 같은 읽기 트랜잭션에서 캡처한다. */
internal class BackupSnapshotAdapter(private val database: LedgerDatabase, private val clock: Clock) {
    suspend fun capture(context: LedgerContext): JSONObject = database.withTransaction {
        database.requireAvailable()
        captureInside(context)
    }

    /** 호출자는 현재 Room 읽기/유지보수 트랜잭션을 소유한다. 검증·암호화·파일 I/O는 밖에서 한다. */
    internal suspend fun captureInside(context: LedgerContext): JSONObject {
        val ledger = checkNotNull(database.ledgerDao().ledger(context.ledgerId))
        if (ledger.storageGeneration != context.storageGeneration) throw LedgerConflictException(LedgerConflict.STALE_GENERATION)
        val sql = database.openHelper.readableDatabase
        val budget = SnapshotBudget()
        val collections = JSONObject()
        for (table in BackupLayout.finance) {
            currentCoroutineContext().ensureActive()
            collections.put(table, JSONArray(rows(sql, table, context.ledgerId, budget)))
        }
        val all = BackupLayout.evidence.associateWith { rows(sql, it, context.ledgerId, budget).associateBy { row -> row.text("operationId") } }
        val operations = all.values.flatMap { it.keys }.distinct().sorted()
        val linkedDraftOperations = sql.query("SELECT operationId FROM form_drafts WHERE ledgerId=? AND operationId IS NOT NULL", arrayOf(context.ledgerId)).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val evidence = JSONArray()
        for (operation in operations) {
            currentCoroutineContext().ensureActive()
            val records = all.mapValues { (_, values) -> values[operation] }
            val raw = records.values.firstNotNullOfOrNull { it?.opt("requestJson") as? String }
            val generation = records.values.firstNotNullOfOrNull { it?.opt("storageGeneration") as? String } ?: raw?.let { BackupJson.parseText(it).text("expectedStorageGeneration") }
            val source = LedgerContext(context.ledgerId, checkNotNull(generation))
            val intent = records["payment_intents"]
            val excluded = operation in linkedDraftOperations || (intent != null && !intent.isNull("draftId"))
            val item = JSONObject().put("sourceContext", JSONObject().put("ledgerId", source.ledgerId).put("storageGeneration", source.storageGeneration)).put("operationId", operation).put("draftAvailability", if (excluded) "EXCLUDED_BY_POLICY" else "NOT_REFERENCED")
            for (table in BackupLayout.evidence) item.put(table, records[table] ?: JSONObject.NULL)
            evidence.put(item)
        }
        sql.query("SELECT evidenceJson FROM restored_evidence WHERE ledgerId=? ORDER BY operationId", arrayOf(context.ledgerId)).use { cursor ->
            while (cursor.moveToNext()) {
                val item = BackupJson.parseText(cursor.getString(0))
                budget.add(item, BackupLayout.evidence.count { !item.isNull(it) })
                evidence.put(item)
            }
        }
        val fenceIds = sql.query("SELECT operationId FROM operation_fences ORDER BY operationId").use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } } + evidence.values().map { (it as JSONObject).text("operationId") }
        val fences = JSONArray(fenceIds.sorted())
        sql.query("SELECT byteLength,length(bytes) FROM backup_originals WHERE ledgerId=?", arrayOf(context.ledgerId)).use { cursor ->
            var count = 0
            var total = 0L
            while (cursor.moveToNext()) {
                val declared = cursor.getLong(0)
                val actual = cursor.getLong(1)
                if (++count > 32 || declared != actual || actual !in 0..2 * 1024 * 1024L) BackupJson.fail(BackupProblem.LIMIT)
                total = Math.addExact(total, actual)
                if (total > 2 * 1024 * 1024L) BackupJson.fail(BackupProblem.LIMIT)
            }
        }
        val originals = JSONArray(
            readBackupOriginals(database, context.ledgerId).map { value ->
                try {
                    JSONObject().put("id", value.id).put("sourceNamespace", value.sourceNamespace).put("fileName", value.fileName).put("byteLength", value.byteLength.toString()).put("sha256", value.sha256).put("bytesBase64", Base64.getEncoder().encodeToString(value.bytes))
                } finally {
                    value.bytes.fill(0)
                }
            },
        )
        val status = database.backupDao().status()
        val currentSettings = database.reminderDao().settings(context.ledgerId)
        // v1 복원 후 OS 재조정용으로 자동 생성된 revision0 기본값은 과거 선호가 기록됐다는 증거가 아니다.
        val settings = currentSettings?.takeUnless { status?.reminderCoverage == "NOT_RECORDED_BY_V1" && it.revision == 0L }
        check(settings == null || settings.storageGeneration == context.storageGeneration)
        val global = JSONObject().put("present", settings != null).put("enabled", settings?.enabled ?: JSONObject.NULL).put("revision", settings?.revision?.toString() ?: JSONObject.NULL)
        val rules = JSONArray(
            database.reminderDao().rules(context.ledgerId).map { value ->
                check(value.storageGeneration == context.storageGeneration)
                JSONObject().put("ruleId", value.ruleId).put("enabled", value.enabled).put("revision", value.revision.toString())
            },
        )
        val preferenceCoverage = if (settings == null && rules.length() == 0) status?.reminderCoverage ?: "RECORDED" else "RECORDED"
        val preferences = JSONObject().put("coverage", preferenceCoverage).put("global", global).put("rules", rules)
        val imports = JSONObject().put("coverage", status?.importCoverage ?: "NOT_RECORDED_BY_V2")
        for (table in BackupLayout.importTables) imports.put(table, JSONArray(rows(sql, table, if (table == "import_source_fences") null else context.ledgerId, budget)))
        val root = JSONObject().put("formatVersion", 3).put("contractVersion", 3).put("createdAt", clock.instant().toString())
            .put("sourceLedger", JSONObject().put("id", context.ledgerId).put("storageGeneration", context.storageGeneration).put("revision", ledger.revision.toString()).put("timeZone", "Asia/Seoul").put("currency", "KRW"))
            .put("collections", collections).put("submissionsEvidence", evidence).put("operationFences", fences).put("reminderPreferences", preferences).put("originals", originals).put("imports", imports)
        root.put("manifest", manifest(root, status?.submissionCoverage ?: "RECORDED", imports.text("coverage")))
        if (root.getJSONObject("manifest").getInt("recordTotal") > 50_000 || BackupJson.utf8(root.toString()).size > BackupEnvelope.MAX_PAYLOAD) BackupJson.fail(BackupProblem.LIMIT)
        return root
    }

    companion object {
        fun manifest(root: JSONObject, submissionCoverage: String = "RECORDED", sourceMappings: String = "NONE_PRESENT"): JSONObject {
            val counts = JSONObject()
            val collections = root.getJSONObject("collections")
            for (name in BackupLayout.finance) counts.put(name, collections.getJSONArray(name).length())
            val evidence = root.list("submissionsEvidence")
            for (name in BackupLayout.evidence) counts.put(name, evidence.count { !it.isNull(name) })
            counts.put("operationFences", root.getJSONArray("operationFences").length())
            val preferences = root.getJSONObject("reminderPreferences")
            counts.put("reminderGlobal", if (preferences.getJSONObject("global").getBoolean("present")) 1 else 0).put("reminderRules", preferences.getJSONArray("rules").length())
            if (root.has("imports")) {
                for (table in BackupLayout.importTables) counts.put(table, root.getJSONObject("imports").getJSONArray(table).length())
                counts.put("originals", root.getJSONArray("originals").length())
            }
            val total = counts.keys().asSequence().sumOf { counts.getInt(it) }
            val originals = JSONArray(root.list("originals").map { JSONObject().put("id", it.text("id")).put("byteLength", it.text("byteLength")).put("sha256", it.text("sha256")) })
            return JSONObject().put("ledgerId", root.getJSONObject("sourceLedger").text("id")).put("recordCounts", counts).put("recordTotal", total).put("originals", originals).put("sourceMappings", sourceMappings).put("submissionCoverage", submissionCoverage).put("excludes", JSONArray(BackupLayout.excludes))
        }

        private suspend fun rows(sql: SupportSQLiteDatabase, table: String, ledger: String?, budget: SnapshotBudget): List<JSONObject> {
            val fields = BackupLayout.properties(table).keys().asSequence().toList()
            val selected = fields.joinToString(",") { if (table == "import_batches" && it == "requestJson") "'' AS requestJson" else it }
            val query = "SELECT $selected FROM $table" + if (ledger == null) " ORDER BY rowid" else " WHERE ledgerId=? ORDER BY rowid"
            return sql.query(query, if (ledger == null) emptyArray() else arrayOf(ledger)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (size == 50_000) BackupJson.fail(BackupProblem.LIMIT)
                        val row = JSONObject()
                        for ((index, field) in fields.withIndex()) row.put(
                            field,
                            when {
                                table == "import_batches" && field == "requestJson" -> com.monit.core.data.readLargeSqlText(sql, table, field, linkedMapOf("ledgerId" to checkNotNull(ledger), "batchId" to cursor.getString(cursor.getColumnIndexOrThrow("batchId"))), BackupEnvelope.MAX_PAYLOAD)

                                cursor.isNull(index) -> JSONObject.NULL

                                BackupLayout.boolean(table, field) -> {
                                    val number = cursor.getLong(index)
                                    check(number == 0L || number == 1L)
                                    number == 1L
                                }

                                BackupLayout.integral(table, field) -> cursor.getLong(index).toString()

                                BackupLayout.integer(table, field) -> cursor.getInt(index)

                                else -> cursor.getString(index)
                            },
                        )
                        budget.add(row)
                        add(row)
                    }
                }
            }
        }
    }
}

private class SnapshotBudget {
    private var records = 0L
    private var bytes = 0L
    fun add(row: JSONObject, count: Int = 1) {
        records += count
        bytes += BackupJson.utf8(row.toString()).size
        if (records > 50_000 || bytes > BackupEnvelope.MAX_PAYLOAD) BackupJson.fail(BackupProblem.LIMIT)
    }
}
