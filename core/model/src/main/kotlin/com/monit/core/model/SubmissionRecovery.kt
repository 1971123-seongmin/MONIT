package com.monit.core.model

/** 최초 제출의 세대를 유지한다. 현재 장부 세대로 치환하지 않는다. */
data class SubmissionReference(val context: LedgerContext, val operationId: String) {
    init {
        requireLedgerId(operationId)
    }
}

data class SubmissionRecoverySnapshot(
    val context: LedgerContext,
    val items: List<SubmissionRecoveryItem>
)

/** 원문/hash를 노출하거나 조회만으로 금융 명령을 재실행하지 않는다. */
sealed interface SubmissionRecoveryItem {
    data class Committed(val reference: SubmissionReference, val receipt: WriteReceipt) :
        SubmissionRecoveryItem

    data class Pending(
        val reference: SubmissionReference,
        val submission: PendingRecoveryItem.TypedSubmission,
    ) : SubmissionRecoveryItem

    data class LegacyPending(val submission: PendingRecoveryItem.TypedSubmission) :
        SubmissionRecoveryItem

    /** 식별 자체가 손상되거나 확인되지 않으면 실행 가능한 참조를 제공하지 않는다. */
    data class Blocked(val reference: SubmissionReference?, val reason: SubmissionRecoveryProblem) :
        SubmissionRecoveryItem
}

enum class SubmissionRecoveryProblem {
    PENDING, MISSING_RECORD, CONFLICTING_RECORDS, GENERATION_MISMATCH, CORRUPTED, UNSUPPORTED,
}

sealed interface FinishSubmissionRecoveryResult {
    data object Finished : FinishSubmissionRecoveryResult

    /** 활성 참조가 없다는 뜻이며 과거 UI 확인 여부를 보장하지 않는다. */
    data object AlreadyInactive : FinishSubmissionRecoveryResult
    data class NotReady(val reason: SubmissionRecoveryProblem) : FinishSubmissionRecoveryResult
}
