package com.monit.core.data.backup

import com.monit.core.model.BackupProblem
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** v1의 없는 근거를 복원하지 않는다. 현재 모델로 무손실 대응 가능한 baseline만 명시 지원한다. */
internal object BackupV1Reader {
    fun convert(root: JSONObject, original: ByteArray): ByteArray {
        val ledger = root.getJSONObject("ledger")
        val oldCollections = listOf("accounts", "paymentMethods", "categories", "transactions", "transactionHistory", "rules", "ruleVersions", "obligations", "anchors", "importBatches", "importRows", "sourceMappings", "operations")
        val manifest = root.getJSONObject("manifest")
        require(manifest.text("ledgerId") == ledger.text("id"))
        val counts = manifest.getJSONObject("recordCounts")
        var total = 0L
        for (name in oldCollections) {
            val size = ledger.getJSONArray(name).length()
            require(counts.getInt(name) == size)
            total += size
        }
        if (total > 50_000 || original.size > 2 * 1024 * 1024) BackupJson.fail(BackupProblem.LIMIT)
        val unsupported = oldCollections - setOf("accounts", "anchors", "transactions")
        if (unsupported.any { ledger.getJSONArray(it).length() != 0 } || root.getJSONArray("originals").length() != 0 || manifest.getJSONArray("originals").length() != 0) BackupJson.fail(BackupProblem.UNSUPPORTED)
        val collections = JSONObject()
        for (name in BackupLayout.finance) collections.put(name, JSONArray())
        val id = ledger.text("id")
        val anchorIds = ledger.list("anchors").map { it.text("id") }.toSet()
        val accountOwners = ledger.list("accounts").associateBy { it.text("id") }
        for (account in ledger.list("accounts")) {
            if (account.text("revision") != "1" || account.isNull("currentAnchorId")) BackupJson.fail(BackupProblem.UNSUPPORTED)
            require(account.text("currentAnchorId") in anchorIds)
            collections.getJSONArray("accounts").put(JSONObject().put("ledgerId", id).put("id", account.text("id")).put("name", account.text("name")).put("nature", nature(account.text("role"))).put("status", account.text("status")).put("revision", account.text("revision")))
        }
        for (anchor in ledger.list("anchors")) {
            if (anchor.text("revision") != "1" || !anchor.getBoolean("isCurrent") || !anchor.isNull("importBatchId")) BackupJson.fail(BackupProblem.UNSUPPORTED)
            val owner = accountOwners.getValue(anchor.text("accountId"))
            require(owner.text("currentAnchorId") == anchor.text("id"))
            collections.getJSONArray("anchors").put(JSONObject().put("ledgerId", id).put("id", anchor.text("id")).put("accountId", anchor.text("accountId")).put("date", anchor.text("date")).put("amount", anchor.opt("amount") ?: JSONObject.NULL).put("confirmedAt", anchor.opt("confirmedAt") ?: JSONObject.NULL).put("unknownReason", if (anchor.isNull("amount")) anchor.get("reason") else JSONObject.NULL).put("revision", anchor.text("revision")))
        }
        val fences = JSONArray()
        for (value in ledger.list("transactions")) {
            if (value.text("revision") != "1" || value.text("financialRevision") != "1" || !value.isNull("deletedAt") || !value.isNull("paymentMethodId") || !value.isNull("categoryId") || !value.isNull("paymentEvidence") || value.getJSONArray("sourceMappingIds").length() != 0) BackupJson.fail(BackupProblem.UNSUPPORTED)
            val row = JSONObject().put("ledgerId", id).put("id", value.text("id")).put("kind", value.text("kind")).put("date", value.text("date")).put("amount", value.text("amount"))
                .put("debitAccountId", value.get("fromAccountId")).put("creditAccountId", value.get("toAccountId")).put("debitNature", value.optional("fromRole")?.let(::nature) ?: JSONObject.NULL).put("creditNature", value.optional("toRole")?.let(::nature) ?: JSONObject.NULL)
                .put("memo", value.text("memo")).put("revision", "1").put("financialRevision", "1").put("deletedAt", JSONObject.NULL)
            collections.getJSONArray("transactions").put(row)
            fences.put(value.text("originOperationId"))
        }
        val digest = BackupJson.digest(original)
        // v1에만 있는 생성/수정 시각·최초 확인 사유 등도 정확한 원문 bytes로 별도 보존한다.
        val originals = JSONArray().put(JSONObject().put("id", UUID.nameUUIDFromBytes(("MONIT_LOGICAL_V1:" + digest).toByteArray(Charsets.UTF_8)).toString()).put("sourceNamespace", "MONIT_LOGICAL_V1").put("fileName", "legacy-logical-v1.json").put("byteLength", original.size.toString()).put("sha256", digest).put("bytesBase64", Base64.getEncoder().encodeToString(original)))
        val preferences = JSONObject().put("coverage", "NOT_RECORDED_BY_V1").put("global", JSONObject().put("present", false).put("enabled", JSONObject.NULL).put("revision", JSONObject.NULL)).put("rules", JSONArray())
        val converted = JSONObject().put("formatVersion", 2).put("contractVersion", 2).put("createdAt", root.text("createdAt"))
            .put("sourceLedger", JSONObject().put("id", id).put("storageGeneration", ledger.text("storageGeneration")).put("revision", ledger.text("revision")).put("timeZone", ledger.text("timeZone")).put("currency", ledger.text("currency")))
            .put("collections", collections).put("submissionsEvidence", JSONArray()).put("operationFences", fences).put("reminderPreferences", preferences).put("originals", originals)
        converted.put("manifest", BackupSnapshotAdapter.manifest(converted, "NOT_RECORDED_BY_V1"))
        return BackupJson.utf8(converted.toString())
    }
    private fun nature(value: String): String = if (value == "SAVING") "SAVINGS" else value
}
