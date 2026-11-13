package com.monit.core.model

sealed interface LedgerEntryResult {
    data class Ready(val context: LedgerContext) : LedgerEntryResult
    data class RecoveryRequired(val reason: LedgerEntryProblem) : LedgerEntryResult
}

enum class LedgerEntryProblem { MISSING_METADATA, INVALID_METADATA, MULTIPLE_LEDGERS }

data class PendingRecoverySnapshot(val context: LedgerContext, val items: List<PendingRecoveryItem>)

/** 저장 원문·hash를 노출하지 않는 읽기 전용 투영. 손상된 항목도 생략하지 않는다. */
sealed interface PendingRecoveryItem {
    /** canonical 캡처의 유효한 요청이다. 현재 실행 가능성은 명시 재시도의 writer가 재검사한다. */
    sealed interface TypedSubmission : PendingRecoveryItem {
        val operationId: String
    }

    data class AccountRegistration(override val operationId: String, val value: CreateAccount) : TypedSubmission

    data class TransactionCreation(override val operationId: String, val value: CreateTransaction) : TypedSubmission

    data class PaymentSubmission(override val operationId: String, val command: PaymentCommand) : TypedSubmission

    data class TransactionUpdate(override val operationId: String, val value: UpdateTransaction) : TypedSubmission
    data class TransactionDeletion(override val operationId: String, val value: DeleteTransaction) : TypedSubmission

    data class Unsupported(val operationId: String, val reason: UnsupportedSubmission) : PendingRecoveryItem

    /** ID 자체가 잘못된 경우 유효한 재시도 키를 제공하지 않는다. 원본 행은 보존된다. */
    data class Corrupted(val operationId: String?, val reason: CorruptedSubmission) : PendingRecoveryItem
}

enum class UnsupportedSubmission { VERSION, KIND }
enum class CorruptedSubmission { INVALID_OPERATION_ID, HASH_MISMATCH, CONTEXT_MISMATCH, INVALID_PAYLOAD }
