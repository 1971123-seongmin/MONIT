package com.monit.core.model

import java.time.LocalDate
import java.time.YearMonth

enum class PaymentPurpose { GENERAL, INSURANCE, SAVINGS, INVESTMENT }
enum class PaymentStatus { PENDING, COMPLETED, SKIPPED, RECONFIRM }
enum class PaymentAction { UNLINK, SKIP, UNSKIP }

fun paymentMonth(value: String): YearMonth = YearMonth.parse(value).also {
    require(it.year in 1900..9999 && it.toString() == value)
}

data class PaymentTerms(
    val name: String,
    val purpose: PaymentPurpose,
    val amount: Long,
    val day: Int,
    val debitAccountId: String,
    val creditAccountId: String? = null,
) {
    init {
        requireLedgerText(name)
        requireTransactionAmount(amount)
        require(day in 1..31)
        requireLedgerId(debitAccountId)
        creditAccountId?.let(::requireLedgerId)
        require(
            when (purpose) {
                PaymentPurpose.GENERAL, PaymentPurpose.INSURANCE -> creditAccountId == null
                PaymentPurpose.SAVINGS, PaymentPurpose.INVESTMENT -> creditAccountId != null && creditAccountId != debitAccountId
            },
        )
    }
}

data class PaymentRule(
    val id: String,
    val revision: Long,
    val startMonth: String,
    val endMonth: String?,
    val terms: PaymentTerms,
)

/** expectedRevision=0은 아직 저장되지 않은 월항목의 부재 조건이다. */
data class PaymentItemReference(
    val id: String,
    val ruleId: String,
    val month: String,
    val expectedRevision: Long,
    val expectedRuleRevision: Long,
) {
    init {
        requireLedgerId(id)
        requireLedgerId(ruleId)
        paymentMonth(month)
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE)
        require(expectedRuleRevision > 0 && expectedRuleRevision < Long.MAX_VALUE)
    }
}

/** 신규면 두 revision이 null이다. 기존 거래의 금융 revision을 고정하고 객체 CAS는 명시한 경우만 검사한다. */
data class PaymentTransaction(
    val value: CreateTransaction,
    val revision: Long? = null,
    val financialRevision: Long? = null,
) {
    init {
        require(financialRevision != null || revision == null)
        revision?.let { require(it > 0 && it < Long.MAX_VALUE) }
        financialRevision?.let { require(it > 0 && it < Long.MAX_VALUE) }
    }
}

data class MonthlyPayment(
    val reference: PaymentItemReference,
    val ruleVersion: Long,
    val terms: PaymentTerms,
    val dueDate: LocalDate,
    val status: PaymentStatus,
    val active: Boolean,
    val transactionId: String?,
    val confirmedFinancialRevision: Long?,
    val confirmedTransaction: CreateTransaction?,
)

data class PaymentMonthSnapshot(
    val context: LedgerContext,
    val ledgerRevision: Long,
    val month: String,
    val rules: List<PaymentRule>,
    val items: List<MonthlyPayment>,
    val reservations: Map<String, String> = emptyMap(),
    val partialTransactions: Set<String> = emptySet(),
)

sealed interface PaymentCommand {
    data class RegisterRule(val id: String, val terms: PaymentTerms, val startMonth: String, val endMonth: String? = null) : PaymentCommand {
        init {
            requireLedgerId(id)
            val start = paymentMonth(startMonth)
            endMonth?.let { require(paymentMonth(it) >= start) }
        }
    }
    data class ChangeRule(val id: String, val expectedRevision: Long, val effectiveMonth: String, val terms: PaymentTerms) : PaymentCommand {
        init {
            requireLedgerId(id)
            require(expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
            paymentMonth(effectiveMonth)
        }
    }
    data class EndRule(val id: String, val expectedRevision: Long, val endMonth: String) : PaymentCommand {
        init {
            requireLedgerId(id)
            require(expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
            paymentMonth(endMonth)
        }
    }
    data class Confirm(val item: PaymentItemReference, val transaction: PaymentTransaction, val full: Boolean, val reason: String) : PaymentCommand {
        init {
            requireLedgerText(reason, allowBlank = true)
        }
    }
    data class ChangeItem(val item: PaymentItemReference, val action: PaymentAction) : PaymentCommand
}

fun paymentResultEntities(command: PaymentCommand): List<EntityRef> {
    val target = when (command) {
        is PaymentCommand.RegisterRule -> EntityRef("payment_rules", command.id, 1)
        is PaymentCommand.ChangeRule -> EntityRef("payment_rules", command.id, Math.addExact(command.expectedRevision, 1))
        is PaymentCommand.EndRule -> EntityRef("payment_rules", command.id, Math.addExact(command.expectedRevision, 1))
        is PaymentCommand.Confirm -> EntityRef("payment_items", command.item.id, Math.addExact(command.item.expectedRevision, 1))
        is PaymentCommand.ChangeItem -> EntityRef("payment_items", command.item.id, Math.addExact(command.item.expectedRevision, 1))
    }
    return listOf(target) + if (command is PaymentCommand.Confirm && command.transaction.financialRevision == null) listOf(EntityRef("transactions", command.transaction.value.id, 1)) else emptyList()
}

/** 금융 성공 영수증과 구분하는, writer 안에서 검증된 명백한 대상 revision 충돌이다. */
data class PaymentRejection(
    val context: LedgerContext,
    val operationId: String,
    val collection: String,
    val id: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val rejectedAt: String,
)
class PaymentRejectedException(val rejection: PaymentRejection) : IllegalStateException("Payment revision changed")

data class PreparedPayment(val context: LedgerContext, val operationId: String, val command: PaymentCommand, val draft: DraftSubmission?)
sealed interface PaymentAttempt {
    data class Committed(val receipt: WriteReceipt) : PaymentAttempt
    data class Rejected(val rejection: PaymentRejection) : PaymentAttempt
    data class Prepared(val request: PreparedPayment) : PaymentAttempt
    data object Released : PaymentAttempt
    data object Missing : PaymentAttempt
}

interface PaymentRepository {
    /** 조회는 규칙·월항목·금융 기록을 생성하지 않는다. */
    suspend fun month(context: LedgerContext, month: String): PaymentMonthSnapshot
    suspend fun submit(context: LedgerContext, operationId: String, command: PaymentCommand, draft: DraftSubmission? = null): WriteReceipt
    suspend fun rejection(context: LedgerContext, operationId: String): PaymentRejection?
    suspend fun prepared(context: LedgerContext): List<PreparedPayment>

    /** receipt・거절・원래 요청을 같은 DB 스냅샷에서 확인한다. */
    suspend fun attempt(context: LedgerContext, operationId: String): PaymentAttempt
    suspend fun retry(context: LedgerContext, operationId: String): WriteReceipt

    /** 결과가 없는 요청만 명시 종료한다. 원문은 유지하며 늦은 이전 실행의 commit을 막는다. */
    suspend fun release(context: LedgerContext, operationId: String, command: PaymentCommand? = null): PaymentAttempt
}
