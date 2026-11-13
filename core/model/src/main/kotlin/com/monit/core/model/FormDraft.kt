package com.monit.core.model

enum class DraftKind { ACCOUNT, TRANSACTION, PAYMENT_RULE, PAYMENT_CONFIRM, ANCHOR }
enum class DraftOrigin { HOME, TRANSACTIONS, ACCOUNTS, TRANSACTION_ENTRY, PAYMENTS }
enum class DraftSlot { DEBIT, CREDIT }

/** 런타임 Nav entry가 아닌 지속 폼 간 호출 문맥이다. */
data class DraftScope(
    val kind: DraftKind,
    val origin: DraftOrigin,
    val parentDraftId: String? = null,
    val selectionRequestId: String? = null,
    val slot: DraftSlot? = null,
    val transactionId: String? = null,
    val transactionRevision: Long? = null,
    val paymentId: String? = null,
    val paymentRevision: Long? = null,
    val paymentRuleId: String? = null,
    val paymentRuleRevision: Long? = null,
    val paymentMonth: String? = null,
    val accountId: String? = null,
    val anchorId: String? = null,
    val anchorRevision: Long? = null,
    val anchorDate: java.time.LocalDate? = null,
) {
    init {
        if (transactionId != null) {
            require(kind == DraftKind.TRANSACTION)
            requireLedgerId(transactionId)
            require(transactionRevision != null && transactionRevision > 0 && transactionRevision < Long.MAX_VALUE)
        } else require(transactionRevision == null)
        when (kind) {
            DraftKind.ANCHOR -> {
                require(origin == DraftOrigin.ACCOUNTS)
                requireLedgerId(requireNotNull(accountId))
                requireLedgerId(requireNotNull(anchorId))
                require(anchorRevision != null && anchorRevision in 1 until Long.MAX_VALUE)
                requireNotNull(anchorDate)
            }

            DraftKind.TRANSACTION -> require(origin == DraftOrigin.HOME || origin == DraftOrigin.TRANSACTIONS || origin == DraftOrigin.PAYMENTS)

            DraftKind.ACCOUNT -> require(origin == DraftOrigin.ACCOUNTS || origin == DraftOrigin.TRANSACTION_ENTRY)

            DraftKind.PAYMENT_RULE, DraftKind.PAYMENT_CONFIRM -> {
                require(origin == DraftOrigin.PAYMENTS)
                requireLedgerId(requireNotNull(paymentId))
                require(paymentRevision != null && paymentRevision >= 0 && paymentRevision < Long.MAX_VALUE)
                if (kind == DraftKind.PAYMENT_CONFIRM) {
                    requireLedgerId(requireNotNull(paymentRuleId))
                    require(paymentRuleRevision != null && paymentRuleRevision > 0 && paymentRuleRevision < Long.MAX_VALUE)
                    com.monit.core.model.paymentMonth(requireNotNull(paymentMonth))
                } else require(paymentRuleId == null && paymentRuleRevision == null && paymentMonth == null)
            }
        }
        if (kind != DraftKind.ANCHOR) require(accountId == null && anchorId == null && anchorRevision == null && anchorDate == null)
        if (kind != DraftKind.PAYMENT_RULE && kind != DraftKind.PAYMENT_CONFIRM) require(paymentId == null && paymentRevision == null && paymentRuleId == null && paymentRuleRevision == null && paymentMonth == null)
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
    data class Anchor(val amount: String, val reason: String) : DraftContent
    data class Payment(val fields: Map<String, String>) : DraftContent
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
    data class Rejected(val draft: FormDraft, val operationId: String) : DraftSnapshot
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
    suspend fun updateTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: UpdateTransaction): WriteReceipt
    suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction): WriteReceipt
}
