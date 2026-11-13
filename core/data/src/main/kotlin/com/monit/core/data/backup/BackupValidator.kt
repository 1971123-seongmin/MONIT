package com.monit.core.data.backup

import com.monit.core.data.*
import com.monit.core.model.*
import java.time.*
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class ValidatedBackup(val root: JSONObject, val context: LedgerContext, val revision: Long, val createdAt: Instant) {
    val collections get() = root.getJSONObject("collections")
    fun rows(table: String): List<JSONObject> = collections.list(table)
}

/** 인증 후만 실행하는 무쓰기 검증. SQL/파일/Keystore와 분리해 실패가 활성 자료를 바꾸지 못한다. */
internal class BackupValidator(private val clock: Clock, private val probe: BackupWorkProbe = BackupWorkProbe()) {
    private val today get() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    suspend fun validate(bytes: ByteArray): ValidatedBackup {
        var logicalStarted = false
        try {
            probe.start(BackupProbeEvent.PARSE)
            var root = try {
                BackupJson.parse(bytes)
            } finally {
                probe.end(BackupProbeEvent.PARSE)
            }
            probe.start(BackupProbeEvent.LOGICAL_VALIDATION)
            logicalStarted = true
            val version = root.get("formatVersion")
            if (version != 1 && version != 2 && version != 3) BackupJson.fail(BackupProblem.UNSUPPORTED)
            BackupSchema.bundled(version as Int).validate(root)
            if (version == 1) return validate(BackupV1Reader.convert(root, bytes))
            if (version == 2) {
                // 정규화로 manifest를 다시 만들기 전에 원래 선언과 실제 배열/원문을 대조한다.
                val legacySource = root.getJSONObject("sourceLedger")
                val legacyContext = LedgerContext(legacySource.text("id"), legacySource.text("storageGeneration"))
                validateManifest(ValidatedBackup(root, legacyContext, legacySource.exactLong("revision").also { require(it > 0) }, root.instant("createdAt")))
                root = BackupImportV3.upgradeV2(root)
            }
            val source = root.getJSONObject("sourceLedger")
            val context = LedgerContext(source.text("id"), source.text("storageGeneration"))
            val data = ValidatedBackup(root, context, source.exactLong("revision").also { require(it > 0) }, root.instant("createdAt"))
            validateManifest(data)
            validateFieldsAndKeys(data)
            val finance = BackupFinanceValidator(data, today)
            finance.validate()
            BackupEvidenceValidator(data, finance).validate()
            BackupImportV3.validate(data, finance, today)
            currentCoroutineContext().ensureActive()
            return data
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BackupException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        } catch (_: IllegalStateException) {
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        } catch (_: java.time.DateTimeException) {
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        } catch (_: org.json.JSONException) {
            BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
        } catch (_: ClassCastException) {
            BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
        } catch (_: NoSuchElementException) {
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        } catch (_: ArithmeticException) {
            BackupJson.fail(BackupProblem.INVALID_RELATION)
        } finally {
            if (logicalStarted) probe.end(BackupProbeEvent.LOGICAL_VALIDATION)
        }
    }

    private suspend fun validateFieldsAndKeys(data: ValidatedBackup) {
        val parents = HashMap<Pair<String, List<String>>, Set<List<String>>>()
        val rows = BackupLayout.finance.associateWith { data.rows(it) } + ("ledgers" to listOf(JSONObject().put("id", data.context.ledgerId)))
        fun key(row: JSONObject, fields: List<String>) = fields.map { row.text(it) }
        for (name in BackupLayout.finance) {
            val definition = BackupLayout.definition(name)
            val primary = definition.getJSONArray("x-primaryKey").values().map { it as String }
            require(data.rows(name).map { key(it, primary) }.distinct().size == data.rows(name).size)
            for (row in data.rows(name)) {
                currentCoroutineContext().ensureActive()
                require(row.text("ledgerId") == data.context.ledgerId)
                for (field in row.keys().asSequence()) {
                    if (row.isNull(field)) continue
                    val value = row.get(field)
                    if (value is String) {
                        if (field == "id" || field.endsWith("Id")) requireLedgerId(value)
                        if (BackupLayout.integral(name, field)) row.exactLong(field)
                        else if (field.endsWith("Json")) {
                            if (value.startsWith('[')) BackupJson.parseText("{\"array\":$value}") else BackupJson.parseText(value)
                        } else require(BackupJson.utf8(value).size <= 16 * 1024)
                    }
                }
                for (raw in definition.getJSONArray("x-foreignKeys").values()) {
                    val foreign = raw as JSONObject
                    val fields = foreign.getJSONArray("columns").values().map { it as String }
                    if (fields.any(row::isNull)) continue
                    val parent = foreign.getString("table")
                    val parentFields = foreign.getJSONArray("referencedColumns").values().map { it as String }
                    val index = parents.getOrPut(parent to parentFields) { checkNotNull(rows[parent]).map { key(it, parentFields) }.toSet() }
                    require(key(row, fields) in index)
                }
            }
        }
    }

    private fun validateManifest(data: ValidatedBackup) {
        val root = data.root
        val manifest = root.getJSONObject("manifest")
        require(manifest.text("ledgerId") == data.context.ledgerId)
        val expected = BackupSnapshotAdapter.manifest(root, manifest.text("submissionCoverage"), manifest.text("sourceMappings"))
        val counts = manifest.getJSONObject("recordCounts")
        for (key in counts.keys().asSequence()) require(counts.get(key) == expected.getJSONObject("recordCounts").get(key))
        require(manifest.getInt("recordTotal") == expected.getInt("recordTotal") && manifest.getInt("recordTotal") <= 50_000)
        val files = root.list("originals")
        val listed = manifest.list("originals").associateBy { it.text("id") }
        require(files.size == listed.size && files.map { it.text("id") }.distinct().size == files.size)
        var total = 0L
        for (file in files) {
            requireLedgerId(file.text("id"))
            val length = file.exactLong("byteLength")
            require(length in 0L..(2 * 1024 * 1024L))
            val bytes = Base64.getDecoder().decode(file.text("bytesBase64"))
            require(Base64.getEncoder().encodeToString(bytes) == file.text("bytesBase64") && bytes.size.toLong() == length && BackupJson.digest(bytes) == file.text("sha256"))
            bytes.fill(0)
            total = Math.addExact(total, length)
            require(total <= 2 * 1024 * 1024)
            val declared = checkNotNull(listed[file.text("id")])
            require(declared.text("byteLength") == file.text("byteLength") && declared.text("sha256") == file.text("sha256"))
            requireLedgerText(file.text("fileName"))
            requireLedgerText(file.text("sourceNamespace"))
        }
        val fences = root.getJSONArray("operationFences").values().map { requireLedgerId(it as String) }
        val fenceSet = fences.toSet()
        require(fenceSet.size == fences.size)
        require(root.list("submissionsEvidence").all { it.text("operationId") in fenceSet })
    }
}
