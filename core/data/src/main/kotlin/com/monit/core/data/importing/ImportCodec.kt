package com.monit.core.data.importing

import com.monit.core.data.*
import com.monit.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** 정렬된 구조 JSON. 출처 키/검토/요청을 구분자 문자열로 합치지 않는다. */
internal fun canonicalImport(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalImport(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalImport(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Boolean, is Int, is Long -> value.toString()
    else -> error("Unsupported import value")
}

internal fun importObject(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { json -> pairs.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) } }
internal fun importHash(value: Any?): String = requestHash(canonicalImport(value))
internal fun importFailure(problem: ImportFileProblem): Nothing = throw ImportFileException(problem)

internal data class ImportIdentity(val key: String, val hash: String)
internal fun importIdentity(input: ImportPreviewInput, row: ImportSourceRow, position: Int): ImportIdentity {
    val key = if (row.sourceId != null) importObject("kind" to "SOURCE_ID", "namespace" to row.sourceNamespace, "entityKind" to row.entityKind, "sourceId" to row.sourceId)
    else importObject("kind" to "FILE_ROW", "namespace" to row.sourceNamespace, "entityKind" to row.entityKind, "fileSha256" to input.inputSha256, "locator" to row.locator, "position" to position)
    return ImportIdentity(canonicalImport(key), importHash(key))
}

internal fun mappingHash(input: ImportPreviewInput, row: ImportSourceRow, options: ImportReviewOptions): String {
    val field = input.fields.singleOrNull { it.namespace == row.sourceNamespace && it.entityKind == row.entityKind }
    val raw = runCatching { JSONObject(row.rawJson) }.getOrNull()
    val relationIds = field?.let { listOf(it.debitRelation, it.creditRelation).flatMap { name -> (raw?.opt(name) as? JSONArray)?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }.orEmpty() } }.orEmpty()
    val accounts = input.accounts.filter { it.walletNamespace == field?.walletNamespace && it.sourceId in relationIds }.sortedWith(compareBy({ it.walletNamespace }, { it.sourceId }, { it.accountId }))
    return importHash(
        importObject(
            "version" to input.mappingVersion,
            "transformer" to input.transformerVersion,
            "field" to field?.let { importObject("title" to it.title, "date" to it.date, "amount" to it.amount, "kind" to it.category, "debit" to it.debitRelation, "credit" to it.creditRelation, "walletNamespace" to it.walletNamespace, "currency" to it.currency, "currencyConfirmed" to it.currencyConfirmed) },
            "accounts" to JSONArray(accounts.map { importObject("namespace" to it.walletNamespace, "sourceId" to it.sourceId, "accountId" to it.accountId, "confirmed" to it.confirmed) }),
            "group" to JSONArray(options.groups.singleOrNull { row.locator in it.locators }?.locators?.sorted().orEmpty()),
        ),
    )
}

internal fun transactionPayload(value: TransactionEntity): String = canonicalImport(importObject("id" to value.id, "kind" to value.kind, "date" to value.date, "amount" to value.amount.toString(), "debit" to value.debitAccountId, "credit" to value.creditAccountId, "debitNature" to value.debitNature, "creditNature" to value.creditNature, "memo" to value.memo))

internal data class ImportPlannedChild(val operationId: String, val command: WriteCommand, val locators: List<String>)
internal data class ImportPlan(val review: ImportReview, val bytes: ByteArray, val identities: Map<String, ImportIdentity>, val mappingHashes: Map<String, String>, val children: List<ImportPlannedChild>, val originalId: String, val backupId: String?, val sourceChecked: Boolean = false) {
    fun request(): String = canonicalImport(
        importObject(
            "version" to "monit-import-1", "batchId" to review.batchId,
            "ledgerId" to review.report.context.ledgerId, "storageGeneration" to review.report.context.storageGeneration,
            "expectedRevision" to review.report.ledgerRevision.toString(), "originalId" to originalId,
            "sourceSha256" to review.report.input.inputSha256, "snapshotSha256" to review.report.input.snapshotSha256,
            "mappingSha256" to review.report.input.mappingSha256, "fingerprint" to review.report.fingerprint,
            "backupId" to backupId, "historyCoverage" to review.historyCoverage.name, "options" to optionsJson(review.options),
            "rows" to JSONArray(review.report.rows.mapIndexed { index, row -> importObject("position" to index, "locator" to row.source.locator, "identity" to checkNotNull(identities[row.source.locator]).hash, "keyJson" to checkNotNull(identities[row.source.locator]).key, "rawSha256" to row.source.rawSha256, "mappingHash" to mappingHashes[row.source.locator], "decision" to row.decision.name, "reasons" to JSONArray(row.reasons.map { it.name }.sorted())) }),
            "children" to JSONArray(children.map { importObject("operationId" to it.operationId, "requestJson" to captureWrite(review.report.context, it.command), "locators" to JSONArray(it.locators.sorted())) }),
        ),
    )
}

internal fun optionsJson(value: ImportReviewOptions): JSONObject = importObject(
    "targetConfirmed" to value.targetConfirmed,
    "accounts" to JSONArray(value.accounts.sortedWith(compareBy({ it.walletNamespace }, { it.sourceId }, { it.accountId })).map { importObject("walletNamespace" to it.walletNamespace, "sourceId" to it.sourceId, "accountId" to it.accountId, "confirmed" to it.confirmed) }),
    "anchors" to JSONArray(value.anchors.sortedBy { it.accountId }.map { importObject("accountId" to it.accountId, "anchorId" to it.anchorId, "revision" to it.expectedRevision.toString(), "date" to it.date, "action" to it.action.name, "amount" to it.amount?.toString(), "reason" to it.reason, "confirmed" to it.confirmed) }),
    "groups" to JSONArray(value.groups.map { importObject("locators" to JSONArray(it.locators.sorted()), "confirmed" to it.confirmed) }.sortedBy(::canonicalImport)),
    "confirmedRows" to JSONArray(value.confirmedRows.sorted()),
    "distinctRows" to JSONArray(value.distinctRows.sorted()),
    "excludedRows" to JSONArray(value.excludedRows.sorted()),
)

internal fun importResultJson(value: ImportResult): String = canonicalImport(importObject("batchId" to value.batchId, "ledgerId" to value.context.ledgerId, "storageGeneration" to value.context.storageGeneration, "committedAt" to value.committedAt, "revision" to value.revision.toString(), "sourceRows" to value.sourceRows, "appliedRows" to value.appliedRows, "existingRows" to value.existingRows, "heldRows" to value.heldRows, "excludedRows" to value.excludedRows, "createdTransactions" to value.createdTransactions, "confirmedAnchors" to value.confirmedAnchors, "partial" to value.partial, "originalId" to value.originalId))
internal fun importResult(raw: String): ImportResult = JSONObject(raw).let { ImportResult(it.getString("batchId"), LedgerContext(it.getString("ledgerId"), it.getString("storageGeneration")), it.getString("committedAt"), it.getString("revision").toLong(), it.getInt("sourceRows"), it.getInt("appliedRows"), it.getInt("existingRows"), it.getInt("heldRows"), it.getInt("excludedRows"), it.getInt("createdTransactions"), it.getInt("confirmedAnchors"), it.getBoolean("partial"), it.getString("originalId")) }.also { check(importResultJson(it) == raw) }
