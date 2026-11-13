package com.monit.core.model

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** 저장된 기준일의 마감액이다. 현재 은행 잔액으로 역산하거나 기준일을 바꾸지 않는다. */
data class ConfirmAnchor(
    val accountId: String,
    val anchorId: String,
    val expectedRevision: Long,
    val date: LocalDate,
    val amount: Long,
    val reason: String,
) {
    init {
        requireLedgerId(accountId)
        requireLedgerId(anchorId)
        require(expectedRevision in 1 until Long.MAX_VALUE)
        require(date >= LocalDate.of(1900, 1, 1))
        require(amount in -999_999_999_999L..999_999_999_999L)
        requireLedgerText(reason, allowBlank = true)
    }
}

enum class AnchorChangeKind { INITIAL_CONFIRMATION, CORRECTION }

data class AnchorChange(
    val operationId: String,
    val accountId: String,
    val before: BalanceAnchor,
    val amount: Long,
    val revision: Long,
    val reason: String,
    val confirmedAt: Instant,
) {
    val kind: AnchorChangeKind get() = if (before.amount == null) AnchorChangeKind.INITIAL_CONFIRMATION else AnchorChangeKind.CORRECTION
}

data class AnchorRejection(val context: LedgerContext, val operationId: String, val anchorId: String, val expectedRevision: Long, val actualRevision: Long, val rejectedAt: Instant)
class AnchorRejectedException(val rejection: AnchorRejection) : IllegalStateException("Anchor revision changed")
class AnchorBoundaryException : IllegalArgumentException("기준일 경계를 넘는 일반 수정은 지원하지 않습니다. 기준 잔액 재확인이 필요합니다.")

interface AnchorRepository {
    suspend fun confirmAnchor(context: LedgerContext, operationId: String, value: ConfirmAnchor, draft: DraftSubmission? = null): WriteReceipt
    suspend fun anchorHistory(context: LedgerContext, accountId: String): List<AnchorChange>
    suspend fun anchorRejection(context: LedgerContext, operationId: String): AnchorRejection?
}

/** 한 계좌의 장부잔액. 마감일 당일은 기준액에 이미 포함된다. */
fun accountBalance(anchor: BalanceAnchor, accountId: String, transactions: List<LedgerTransaction>, asOf: LocalDate): Long? {
    val amount = anchor.amount ?: return null
    if (asOf < anchor.date) return null
    return sumSignedAmounts(
        listOf(amount) + transactions.filter { it.date > anchor.date && it.date <= asOf }.mapNotNull {
            when (accountId) {
                it.debitAccountId -> -it.amount
                it.creditAccountId -> it.amount
                else -> null
            }
        },
    )
}

/** 날짜별 마감액의 전체 경로를 검사한다. 오늘의 상쇄 결과로 과거 overflow를 숨기지 않는다. */
fun requireBalanceHistory(anchor: BalanceAnchor, accountId: String, transactions: List<LedgerTransaction>, asOf: LocalDate) {
    var running = BigInteger.valueOf(anchor.amount ?: return)
    val limit = BigInteger.valueOf(Long.MAX_VALUE)
    val daily = transactions.filter { it.date > anchor.date && it.date <= asOf && (it.debitAccountId == accountId || it.creditAccountId == accountId) }.groupBy { it.date }.toSortedMap()
    for (values in daily.values) {
        for (transaction in values) running += BigInteger.valueOf(if (transaction.debitAccountId == accountId) -transaction.amount else transaction.amount)
        if (running < -limit || running > limit) throw ArithmeticException("Account balance exceeds the supported range")
    }
}

data class AssetFlows(val deposits: Long, val withdrawals: Long, val net: Long)

/** 당시 성격으로 분류한다. 현재 성격/기준일/납입 체크 여부로 과거 월 통계를 자르지 않는다. */
fun assetFlows(transactions: List<LedgerTransaction>, month: YearMonth, accountId: String? = null): AssetFlows {
    val values = transactions.filter { it.kind == TransactionKind.TRANSFER && YearMonth.from(it.date) == month }
    fun AccountNature?.asset() = this == AccountNature.SAVINGS || this == AccountNature.INVESTMENT
    val deposits = sumSignedAmounts(values.filter { it.debitNature == AccountNature.GENERAL && it.creditNature.asset() && (accountId == null || it.creditAccountId == accountId) }.map { it.amount })
    val withdrawals = sumSignedAmounts(values.filter { it.debitNature.asset() && it.creditNature == AccountNature.GENERAL && (accountId == null || it.debitAccountId == accountId) }.map { it.amount })
    return AssetFlows(deposits, withdrawals, sumSignedAmounts(listOf(deposits, -withdrawals)))
}

fun anchorCommandFromDraft(scope: DraftScope, value: DraftContent.Anchor): ConfirmAnchor {
    require(scope.kind == DraftKind.ANCHOR)
    require(value.amount.matches(Regex("-?[0-9]{1,12}")))
    return ConfirmAnchor(checkNotNull(scope.accountId), checkNotNull(scope.anchorId), checkNotNull(scope.anchorRevision), checkNotNull(scope.anchorDate), value.amount.toLong(), value.reason)
}
