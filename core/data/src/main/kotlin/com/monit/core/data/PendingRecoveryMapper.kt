package com.monit.core.data

import com.monit.core.model.CorruptedSubmission
import com.monit.core.model.LedgerContext
import com.monit.core.model.PendingRecoveryItem
import com.monit.core.model.UnsupportedSubmission
import com.monit.core.model.requireLedgerId
import java.time.DateTimeException
import org.json.JSONException
import org.json.JSONObject

/** 예외를 분류하는 영역은 순수 원문 검증뿐이다. DB 오류·취소를 목록으로 바꾸지 않는다. */
internal fun SubmissionEntity.toRecoveryItem(context: LedgerContext): PendingRecoveryItem {
    try {
        requireLedgerId(operationId)
    } catch (_: IllegalArgumentException) {
        return PendingRecoveryItem.Corrupted(null, CorruptedSubmission.INVALID_OPERATION_ID)
    }
    fun corrupted(reason: CorruptedSubmission) = PendingRecoveryItem.Corrupted(operationId, reason)
    if (requestHash(requestJson) != requestSha256) return corrupted(CorruptedSubmission.HASH_MISMATCH)
    return try {
        val json = JSONObject(requestJson)
        // getInt는 문자열·소수를 강제 변환하므로 저장 형식의 정수 타입도 확인한다.
        val version = json.get("version")
        if (version !is Int && version !is Long) return corrupted(CorruptedSubmission.INVALID_PAYLOAD)
        if ((version as Number).toLong() != 1L) {
            return PendingRecoveryItem.Unsupported(operationId, UnsupportedSubmission.VERSION)
        }
        if (ledgerId != context.ledgerId || json.get("ledgerId") != context.ledgerId ||
            json.get("expectedStorageGeneration") != context.storageGeneration
        ) return corrupted(CorruptedSubmission.CONTEXT_MISMATCH)
        val kind = json.get("kind")
        if (kind !is String) return corrupted(CorruptedSubmission.INVALID_PAYLOAD)
        if (kind != "UPDATE_ACCOUNT") {
            return PendingRecoveryItem.Unsupported(operationId, UnsupportedSubmission.KIND)
        }
        val command = decodeWrite(context, requestJson) as WriteCommand.Account
        PendingRecoveryItem.AccountRegistration(operationId, command.value)
    } catch (_: JSONException) {
        corrupted(CorruptedSubmission.INVALID_PAYLOAD)
    } catch (_: IllegalArgumentException) {
        corrupted(CorruptedSubmission.INVALID_PAYLOAD)
    } catch (_: IllegalStateException) {
        corrupted(CorruptedSubmission.INVALID_PAYLOAD)
    } catch (_: DateTimeException) {
        corrupted(CorruptedSubmission.INVALID_PAYLOAD)
    }
}
