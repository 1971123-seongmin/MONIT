package com.monit.core.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 사본 작업에만 넘기는 소유 plain과 최소 fence 값. 전체 검증 root는 보관하지 않는다. */
internal class BackupRecoveryPayload(val plain: ByteArray, val submissionOperations: List<String>, val operationFences: List<String>, val importFences: List<JSONObject>)

internal suspend fun prepareBackupRecoveryPayload(plain: ByteArray, validate: suspend (ByteArray) -> ValidatedBackup): BackupRecoveryPayload {
    var transferred = false
    try {
        val current = validate(plain).root
        val result = BackupRecoveryPayload(
            plain = plain,
            submissionOperations = current.list("submissionsEvidence").map { it.text("operationId") },
            operationFences = current.getJSONArray("operationFences").values().map { it as String },
            importFences = current.getJSONObject("imports").list("import_source_fences"),
        )
        transferred = true
        return result
    } finally {
        if (!transferred) plain.fill(0)
    }
}

/** strict/논리 검증과 최소 metadata 추출 후 plain을 만들며 실패·취소 때 소유 배열을 소거한다. */
internal suspend fun prepareBackupRecoveryPayload(snapshot: BackupCapturedSnapshot, validate: suspend (BackupCapturedSnapshot) -> ValidatedBackup): BackupRecoveryPayload {
    var transferred = false
    try {
        val current = validate(snapshot).root
        val submissions = current.list("submissionsEvidence").map { it.text("operationId") }
        val operations = current.getJSONArray("operationFences").values().map { it as String }
        val imports = current.getJSONObject("imports").list("import_source_fences")
        currentCoroutineContext().ensureActive()
        val plain = snapshot.encode()
        currentCoroutineContext().ensureActive()
        val result = BackupRecoveryPayload(plain, submissions, operations, imports)
        transferred = true
        return result
    } finally {
        if (!transferred) snapshot.clear()
    }
}
