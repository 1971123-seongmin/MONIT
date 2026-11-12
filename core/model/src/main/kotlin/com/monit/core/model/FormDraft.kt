package com.monit.core.model

enum class DraftKind { ACCOUNT, TRANSACTION }
enum class DraftOrigin { HOME, TRANSACTIONS, ACCOUNTS, TRANSACTION_ENTRY }
enum class DraftSlot { DEBIT, CREDIT }

/** 런타임 Nav entry가 아닌 지속 폼 간 호출 문맥이다. */
data class DraftScope(
    val kind: DraftKind,
    val origin: DraftOrigin,
    val parentDraftId: String? = null,
    val selectionRequestId: String? = null,
    val slot: DraftSlot? = null,
) {
    init {
        if (kind == DraftKind.TRANSACTION) require(origin == DraftOrigin.HOME || origin == DraftOrigin.TRANSACTIONS)
        else require(origin == DraftOrigin.ACCOUNTS || origin == DraftOrigin.TRANSACTION_ENTRY)
        if (origin == DraftOrigin.TRANSACTION_ENTRY) {
            requireLedgerId(requireNotNull(parentDraftId))
            requireLedgerId(requireNotNull(selectionRequestId))
            requireNotNull(slot)
        } else require(parentDraftId == null && selectionRequestId == null && slot == null)
    }
}

data class DraftReference(val id: String, val revision: Long) {
    init {
        requireLedgerId(id)
        require(revision > 0)
    }
}

/** 유효하지 않은 편집 중 금액/일자도 원문 그대로 보존한다. */
sealed interface DraftContent {
    data class Account(
        val name: String,
        val nature: AccountNature,
        val anchorDate: String,
        val unknownReason: String,
    ) : DraftContent

    data class Transaction(
        val kind: TransactionKind,
        val amount: String,
        val date: String,
        val debitAccountId: String?,
        val creditAccountId: String?,
        val memo: String,
        val selectionSlot: DraftSlot? = null,
        val selectionRequestId: String? = null,
        val candidateAccountId: String? = null,
    ) : DraftContent
}

data class FormDraft(
    val context: LedgerContext,
    val reference: DraftReference,
    val scope: DraftScope,
    val content: DraftContent,
)

/** 금융 제출이 사용한 초안의 정확한 revision과 호출 문맥. */
data class DraftSubmission(val reference: DraftReference, val scope: DraftScope)

sealed interface DraftSnapshot {
    data class Editable(val draft: FormDraft) : DraftSnapshot
    data class Submitted(val reference: DraftReference, val operationId: String) : DraftSnapshot
    data class Closed(val reference: DraftReference, val operationId: String?) : DraftSnapshot
    data class Blocked(val id: String?, val reason: DraftProblem) : DraftSnapshot
    data object Missing : DraftSnapshot
}

enum class DraftProblem { STALE_REVISION, WRONG_CONTEXT, CLOSED, CORRUPTED }
class DraftConflictException(val problem: DraftProblem) : IllegalStateException("Draft conflict: $problem")

/** 미제출 UI 초안과 기존 금융 writer의 연결 경계. 초안 저장 자체는 금융 쓰기가 아니다. */
interface DraftRepository {
    suspend fun drafts(context: LedgerContext): List<DraftSnapshot>
    suspend fun draft(context: LedgerContext, id: String): DraftSnapshot
    suspend fun saveDraft(context: LedgerContext, id: String, expectedRevision: Long, scope: DraftScope, content: DraftContent): FormDraft
    suspend fun discardDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope)
    suspend fun createAccountFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateAccount): WriteReceipt
    suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction): WriteReceipt
}
