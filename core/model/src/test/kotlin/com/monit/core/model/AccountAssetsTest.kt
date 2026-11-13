package com.monit.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class AccountAssetsTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val date = LocalDate.of(2026, 11, 10)
    private val anchor = BalanceAnchor(id(11), date, 1000, Instant.EPOCH, null)
    private fun transfer(n: Int, day: LocalDate, amount: Long, debit: Int = 10, credit: Int = 20, from: AccountNature = AccountNature.GENERAL, to: AccountNature = AccountNature.SAVINGS) = LedgerTransaction(id(n), TransactionKind.TRANSFER, day, amount, id(debit), id(credit), from, to, "", 1, 1)

    /** 기준일 당일은 마감액에 포함되고 다음 날부터 실제 날짜 기준으로 누적한다. */
    @Test fun closingBoundaryAndUnknownAreDistinctFromConfirmedZero() {
        val tx = listOf(transfer(30, date, 300), transfer(31, date.plusDays(1), 200))
        assertEquals(null, accountBalance(anchor, id(10), tx, date.minusDays(1)))
        assertEquals(1000, accountBalance(anchor, id(10), tx, date))
        assertEquals(800, accountBalance(anchor, id(10), tx, date.plusDays(1)))
        assertEquals(0, accountBalance(anchor.copy(amount = 0), id(10), tx, date))
        assertNull(accountBalance(anchor.copy(amount = null, confirmedAt = null, unknownReason = "미확인"), id(10), tx, date))
    }

    /** 월 전체 거래/당시 성격 기준이다. 재배치와 직접 수입·지출은 납입·인출에 포함하지 않는다. */
    @Test fun monthlyFlowsIncludeBothAssetNaturesAndExcludeRebalancingAndExternalFlows() {
        val values = listOf(
            transfer(30, date.minusDays(5), 300),
            transfer(31, date, 200, credit = 40, to = AccountNature.INVESTMENT),
            transfer(32, date, 80, 20, 10, AccountNature.SAVINGS, AccountNature.GENERAL),
            transfer(33, date, 500, 20, 40, AccountNature.SAVINGS, AccountNature.INVESTMENT),
            transfer(34, date, 900, 10, 50, AccountNature.GENERAL, AccountNature.GENERAL),
            transfer(35, date.minusMonths(1), 1000),
            LedgerTransaction(id(36), TransactionKind.INCOME, date, 400, null, id(20), null, AccountNature.SAVINGS, "외부 수입", 1, 1),
            LedgerTransaction(id(37), TransactionKind.EXPENSE, date, 100, id(10), null, AccountNature.GENERAL, null, "보험", 1, 1),
        )
        assertEquals(AssetFlows(500, 80, 420), assetFlows(values, YearMonth.from(date)))
        assertEquals(AssetFlows(300, 80, 220), assetFlows(values, YearMonth.from(date), id(20)))
        assertEquals(AssetFlows(200, 0, 200), assetFlows(values, YearMonth.from(date), id(40)))
    }

    /** L2 집계는 중간 Long overflow 없이 정확히 합산하지만 최종 한도를 벗어나면 거절한다. */
    @Test fun exactBalanceRejectsLongMinAndOverflow() {
        val values = listOf(transfer(30, date.plusDays(1), Long.MAX_VALUE))
        assertFailsWith<ArithmeticException> { accountBalance(anchor, id(20), values, date.plusDays(1)) }
        val negative = anchor.copy(amount = -1)
        assertFailsWith<ArithmeticException> { accountBalance(negative, id(10), values, date.plusDays(1)) }
    }

    /** 합성 L2 계산 입력으로 다음 날 상쇄가 과거 일별 overflow를 숨기지 못함을 검증한다. 거래 입력 한도 시험은 별도다. */
    @Test fun laterCancellationCannotHideHistoricalOverflow() {
        val values = listOf(transfer(30, date.plusDays(1), Long.MAX_VALUE), transfer(31, date.plusDays(2), Long.MAX_VALUE, 20, 10, AccountNature.SAVINGS, AccountNature.GENERAL))
        assertEquals(1000, accountBalance(anchor, id(20), values, date.plusDays(2)))
        assertFailsWith<ArithmeticException> { requireBalanceHistory(anchor, id(20), values, date.plusDays(2)) }
    }

    /** 확인한 음수·0과 최초 확인의 빈 사유를 허용하며 범위 밖 금액·잘못된 revision은 거절한다. 변경 사유는 저장 상태와 함께 검사한다. */
    @Test fun commandRequiresExplicitValidAmountReasonAndOwnRevision() {
        val command = ConfirmAnchor(id(10), id(11), 1, date, 0, "마감액 확인")
        assertEquals(-999_999_999_999L, command.copy(amount = -999_999_999_999L).amount)
        assertFailsWith<IllegalArgumentException> { command.copy(amount = 1_000_000_000_000L) }
        assertEquals("", command.copy(reason = "").reason)
        assertFailsWith<IllegalArgumentException> { command.copy(expectedRevision = 0) }
    }
}
