package com.monit.core.data

import com.monit.core.model.CorruptedSubmission
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerContext
import com.monit.core.model.PendingRecoveryItem
import com.monit.core.model.SubmissionRecoveryItem
import com.monit.core.model.SubmissionRecoveryProblem
import com.monit.core.model.SubmissionReference
import java.time.DateTimeException
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/** 예외 분류는 순수 저장값 검증에 한정한다. DAO 호출·취소·I/O는 이 경계 밖이다. */
internal fun submissionRecoveryItem(
    context: LedgerContext,
    ledgerRevision: Long,
    reference: SubmissionReferenceEntity?,
    pending: SubmissionEntity?,
    operation: OperationEntity?,
): SubmissionRecoveryItem {
    val key = if (reference == null) null else try {
        SubmissionReference(
            LedgerContext(reference.ledgerId, reference.storageGeneration),
            reference.operationId
        )
    } catch (_: IllegalArgumentException) {
        return SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)
    }

    fun blocked(reason: SubmissionRecoveryProblem) = SubmissionRecoveryItem.Blocked(key, reason)
    if (key != null && key.context != context) return blocked(SubmissionRecoveryProblem.GENERATION_MISMATCH)
    if (pending != null && operation != null) return blocked(SubmissionRecoveryProblem.CONFLICTING_RECORDS)
    if (operation != null) {
        checkNotNull(key)
        return validateCommitted(key, ledgerRevision, operation)
    }
    if (pending == null) return blocked(SubmissionRecoveryProblem.MISSING_RECORD)
    return when (val item = pending.toRecoveryItem(context)) {
        is PendingRecoveryItem.AccountRegistration -> {
            if (key == null) SubmissionRecoveryItem.LegacyPending(item)
            else if (item.operationId == key.operationId) SubmissionRecoveryItem.Pending(key, item)
            else blocked(SubmissionRecoveryProblem.CORRUPTED)
        }

        is PendingRecoveryItem.Unsupported -> blocked(SubmissionRecoveryProblem.UNSUPPORTED)
        is PendingRecoveryItem.Corrupted -> blocked(
            if (item.reason == CorruptedSubmission.CONTEXT_MISMATCH) SubmissionRecoveryProblem.GENERATION_MISMATCH
            else SubmissionRecoveryProblem.CORRUPTED,
        )
    }
}

private fun validateCommitted(
    reference: SubmissionReference,
    ledgerRevision: Long,
    operation: OperationEntity,
): SubmissionRecoveryItem {
    fun blocked(reason: SubmissionRecoveryProblem) =
        SubmissionRecoveryItem.Blocked(reference, reason)
    if (operation.ledgerId != reference.context.ledgerId || operation.operationId != reference.operationId ||
        requestHash(operation.requestJson) != operation.requestSha256
    ) return blocked(SubmissionRecoveryProblem.CORRUPTED)
    if (operation.storageGeneration != reference.context.storageGeneration) {
        return blocked(SubmissionRecoveryProblem.GENERATION_MISMATCH)
    }
    return try {
        val json = JSONObject(operation.requestJson)
        val version = json.get("version")
        if (version !is Int && version !is Long) return blocked(SubmissionRecoveryProblem.CORRUPTED)
        if ((version as Number).toLong() != 1L) return blocked(SubmissionRecoveryProblem.UNSUPPORTED)
        if (json.get("ledgerId") != reference.context.ledgerId ||
            json.get("expectedStorageGeneration") != reference.context.storageGeneration
        ) return blocked(SubmissionRecoveryProblem.GENERATION_MISMATCH)
        val kind = json.get("kind")
        if (kind !is String) return blocked(SubmissionRecoveryProblem.CORRUPTED)
        if (kind != "UPDATE_ACCOUNT" && kind != "CREATE_TRANSACTION") {
            return blocked(SubmissionRecoveryProblem.UNSUPPORTED)
        }
        if (operation.kind != kind) return blocked(SubmissionRecoveryProblem.CORRUPTED)
        val expectedRefs =
            when (val command = decodeWrite(reference.context, operation.requestJson)) {
                is WriteCommand.Account -> listOf(
                    EntityRef("accounts", command.value.id, 1),
                    EntityRef("anchors", command.value.anchorId, 1),
                )

                is WriteCommand.Transaction -> listOf(
                    EntityRef(
                        "transactions",
                        command.value.id,
                        1
                    )
                )
            }
        Instant.parse(operation.committedAt)
        val receipt = operation.receipt()
        if (receipt.ledgerRevision <= 1 || receipt.ledgerRevision > ledgerRevision ||
            receipt.entities != expectedRefs || encodeRefs(expectedRefs) != operation.resultJson
        ) return blocked(SubmissionRecoveryProblem.CORRUPTED)
        SubmissionRecoveryItem.Committed(reference, receipt)
    } catch (_: JSONException) {
        blocked(SubmissionRecoveryProblem.CORRUPTED)
    } catch (_: IllegalArgumentException) {
        blocked(SubmissionRecoveryProblem.CORRUPTED)
    } catch (_: IllegalStateException) {
        blocked(SubmissionRecoveryProblem.CORRUPTED)
    } catch (_: DateTimeException) {
        blocked(SubmissionRecoveryProblem.CORRUPTED)
    }
}
