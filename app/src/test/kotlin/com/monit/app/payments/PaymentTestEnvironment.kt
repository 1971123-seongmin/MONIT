package com.monit.app.payments

import com.monit.core.model.*
import java.io.IOException
import java.time.LocalDate

internal fun paymentTestId(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

/** VM 제어용 프로토콜 대역. 금융·규칙 비즈니스 계산은 실제 Room 시험이 검증한다. */
internal class PaymentTestEnvironment {
    val context = LedgerContext(paymentTestId(900), paymentTestId(901))
    private val terms = PaymentTerms("합성 보험", PaymentPurpose.INSURANCE, 30_000, 30, paymentTestId(10))
    val baseItem = MonthlyPayment(PaymentItemReference(paymentTestId(50), paymentTestId(40), "2026-11", 0, 1), 1, terms, LocalDate.of(2026, 11, 30), PaymentStatus.PENDING, true, null, null, null)
    val accounts = listOf(10 to AccountNature.GENERAL, 20 to AccountNature.SAVINGS, 30 to AccountNature.INVESTMENT).map { (n, nature) -> LedgerAccount(paymentTestId(n), "합성 계좌 $n", nature, AccountStatus.ACTIVE, 1, BalanceAnchor(paymentTestId(n + 1), LocalDate.of(2026, 11, 12), null, null, "미확인")) }
    var ledgerValue = LedgerSnapshot(1, accounts, emptyList(), emptyMap(), 0, 0)
    var monthly = PaymentMonthSnapshot(context, 1, "2026-11", listOf(PaymentRule(paymentTestId(40), 1, "2026-01", null, terms)), listOf(baseItem))
    var failRead = false
    var failOutcome = false
    var failSubmit = false
    var loseReply = false
    var reject = false
    var beforeSubmit: suspend () -> Unit = {}
    var onTransactionUpdate: suspend (String, UpdateTransaction) -> WriteReceipt = { _, _ -> error("Unexpected transaction update") }
    val calls = mutableListOf<PreparedPayment>()
    val finished = mutableListOf<String>()
    val outcomes = linkedMapOf<String, PaymentAttempt>()
    val rows = linkedMapOf<String, DraftSnapshot>()
    var draftFailure = false
    var draftSaves = 0
    val reads = mutableListOf<String>()

    val ledger = object : LedgerRepository {
        override suspend fun openLedger(): LedgerEntryResult = error("VM must use the supplied context")
        override suspend fun initialize(context: LedgerContext): Unit = error("VM must not create metadata")
        override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
            if (failRead) throw IOException()
            require(context == this@PaymentTestEnvironment.context)
            return ledgerValue
        }
        override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult {
            finished += operationId
            return FinishSubmissionRecoveryResult.Finished
        }
        override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = (outcomes[operationId] as? PaymentAttempt.Committed)?.receipt
        override suspend fun paymentRejection(context: LedgerContext, operationId: String): PaymentRejection? = (outcomes[operationId] as? PaymentAttempt.Rejected)?.rejection
        override suspend fun rejection(context: LedgerContext, operationId: String): TransactionRejection? = null
        override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt = error("Rule entry never writes an account")
        override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt = error("Payment confirmation uses its atomic writer")
        override suspend fun updateTransaction(context: LedgerContext, operationId: String, value: UpdateTransaction): WriteReceipt = onTransactionUpdate(operationId, value)
        override suspend fun deleteTransaction(context: LedgerContext, operationId: String, value: DeleteTransaction): WriteReceipt = error("Transaction delete belongs to its feature")
        override suspend fun pendingRecovery(context: LedgerContext) = PendingRecoverySnapshot(context, emptyList())
        override suspend fun submissionRecovery(context: LedgerContext) = SubmissionRecoverySnapshot(context, emptyList())
        override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = error("Raw requests stay private")
        override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = error("Payment retry uses its original intent")
    }
    val payments = object : PaymentRepository {
        override suspend fun month(context: LedgerContext, month: String): PaymentMonthSnapshot {
            if (failRead) throw IOException()
            require(context == this@PaymentTestEnvironment.context)
            reads += month
            return monthly.copy(month = month, items = if (month == monthly.month) monthly.items else emptyList())
        }
        override suspend fun prepared(context: LedgerContext): List<PreparedPayment> = outcomes.values.filterIsInstance<PaymentAttempt.Prepared>().map { it.request }
        override suspend fun rejection(context: LedgerContext, operationId: String): PaymentRejection? = (outcomes[operationId] as? PaymentAttempt.Rejected)?.rejection
        override suspend fun attempt(context: LedgerContext, operationId: String): PaymentAttempt {
            if (failOutcome) throw IOException()
            return outcomes[operationId] ?: PaymentAttempt.Missing
        }
        override suspend fun submit(context: LedgerContext, operationId: String, command: PaymentCommand, draft: DraftSubmission?): WriteReceipt {
            val original = PreparedPayment(context, operationId, command, draft)
            calls += original
            when (val prior = outcomes[operationId]) {
                is PaymentAttempt.Committed -> return prior.receipt
                is PaymentAttempt.Rejected -> throw PaymentRejectedException(prior.rejection)
                PaymentAttempt.Released -> error("Request released")
                is PaymentAttempt.Prepared -> require(prior.request == original)
                else -> outcomes[operationId] = PaymentAttempt.Prepared(original)
            }
            beforeSubmit()
            check(outcomes[operationId] != PaymentAttempt.Released)
            if (failSubmit) throw IOException()
            if (reject) {
                val target = paymentResultEntities(command).first()
                val result = PaymentRejection(context, operationId, target.collection, target.id, target.revision - 1, target.revision, "2026-11-13T03:00:00Z")
                outcomes[operationId] = PaymentAttempt.Rejected(result)
                draft?.let { row ->
                    val old = (rows[row.reference.id] as DraftSnapshot.Editable).draft
                    rows[row.reference.id] = DraftSnapshot.Rejected(old.copy(reference = old.reference.copy(revision = old.reference.revision + 1)), operationId)
                }
                throw PaymentRejectedException(result)
            }
            val receipt = WriteReceipt(operationId, 2, paymentResultEntities(command))
            outcomes[operationId] = PaymentAttempt.Committed(receipt)
            draft?.let { rows[it.reference.id] = DraftSnapshot.Closed(it.reference.copy(revision = it.reference.revision + 1), operationId) }
            if (loseReply) throw IOException()
            return receipt
        }
        override suspend fun retry(context: LedgerContext, operationId: String): WriteReceipt = when (val result = attempt(context, operationId)) {
            is PaymentAttempt.Prepared -> submit(context, operationId, result.request.command, result.request.draft)
            is PaymentAttempt.Committed -> result.receipt
            is PaymentAttempt.Rejected -> throw PaymentRejectedException(result.rejection)
            else -> error("No original request")
        }
        override suspend fun release(context: LedgerContext, operationId: String, command: PaymentCommand?): PaymentAttempt = when (val result = attempt(context, operationId)) {
            is PaymentAttempt.Prepared, PaymentAttempt.Missing, PaymentAttempt.Released -> PaymentAttempt.Released.also { outcomes[operationId] = it }
            else -> result
        }
    }
    val drafts = object : DraftRepository {
        override suspend fun drafts(context: LedgerContext): List<DraftSnapshot> = rows.values.filter { it is DraftSnapshot.Editable || it is DraftSnapshot.Submitted }
        override suspend fun draft(context: LedgerContext, id: String): DraftSnapshot = rows[id] ?: DraftSnapshot.Missing
        override suspend fun saveDraft(context: LedgerContext, id: String, expectedRevision: Long, scope: DraftScope, content: DraftContent): FormDraft {
            if (draftFailure) throw IOException()
            val previous = rows[id]
            val row = (previous as? DraftSnapshot.Editable)?.draft
            require(previous == null || row != null)
            require(row == null || (row.context == context && row.scope == scope))
            if (row?.content == content) return row
            require((row?.reference?.revision ?: 0) == expectedRevision)
            draftSaves++
            return FormDraft(context, DraftReference(id, expectedRevision + 1), scope, content).also { rows[id] = DraftSnapshot.Editable(it) }
        }
        override suspend fun discardDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope) {
            val old = (rows[reference.id] as DraftSnapshot.Editable).draft
            require(old.context == context && old.scope == scope && old.reference == reference)
            rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), null)
        }
        override suspend fun createAccountFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateAccount): WriteReceipt = error("Payment uses its own writer")
        override suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction): WriteReceipt = error("Payment uses its own writer")
        override suspend fun updateTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: UpdateTransaction): WriteReceipt = ledger.updateTransaction(context, operationId, value).also { rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), operationId) }
    }
}
