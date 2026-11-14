package com.monit.core.data.backup

import com.monit.core.model.BackupProblem
import org.json.JSONObject

internal object BackupMergeBudget {
    /** 세 경로를 읽기 전용으로 대체해 같은 전체 JSON 길이와 건수를 검사한다. */
    fun requireWithinLimits(source: ValidatedBackup, previousOperations: Set<String>, importFences: List<JSONObject>) {
        val root = source.root
        val incoming = root.getJSONArray("operationFences")
        val operations = previousOperations.toHashSet()
        for (index in 0 until incoming.length()) operations.add(incoming.get(index) as String)
        val operationRows = org.json.JSONArray(operations.sorted())
        val importRows = org.json.JSONArray(importFences)
        val imports = root.getJSONObject("imports")
        val manifest = BackupSnapshotAdapter.manifest(root, root.getJSONObject("manifest").text("submissionCoverage"), imports.text("coverage"))
        val counts = manifest.getJSONObject("recordCounts")
        val unchanged = Math.subtractExact(Math.subtractExact(manifest.getLong("recordTotal"), counts.getLong("operationFences")), counts.getLong("import_source_fences"))
        val total = Math.addExact(Math.addExact(unchanged, operations.size.toLong()), importFences.size.toLong())
        counts.put("operationFences", operations.size).put("import_source_fences", importFences.size)
        manifest.put("recordTotal", total)
        if (total > 50_000) BackupJson.fail(BackupProblem.LIMIT)
        val bytes = BackupJsonSize.count(root) { owner, key ->
            when {
                owner === root && key == "operationFences" -> operationRows
                owner === root && key == "manifest" -> manifest
                owner === imports && key == "import_source_fences" -> importRows
                else -> owner.get(key)
            }
        }
        if (bytes > BackupEnvelope.MAX_PAYLOAD) BackupJson.fail(BackupProblem.LIMIT)
    }
}
