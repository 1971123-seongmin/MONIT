package com.monit.feature.accounts.list

import com.monit.core.model.*
import java.time.*
import kotlin.test.*

class AccountsProjectionTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val date = LocalDate.of(2026, 11, 13)
    private fun account(n: Int, nature: AccountNature, amount: Long?, status: AccountStatus = AccountStatus.ACTIVE) = LedgerAccount(id(n), "합성 $n", nature, status, 1, BalanceAnchor(id(n + 1), date.minusDays(1), amount, amount?.let { Instant.EPOCH }, if (amount == null) "미확인" else null))

    /** 활성 확인 잔액만 소계에 포함하며 일반/저축투자 소계와 미확인/보관 제외를 각각 표시한다. */
    @Test fun subtotalIncludesConfirmedZeroAndNegativeAndExplainsExclusions() {
        val accounts = listOf(account(10, AccountNature.GENERAL, -500), account(20, AccountNature.SAVINGS, 1000), account(30, AccountNature.INVESTMENT, null), account(40, AccountNature.INVESTMENT, 2000, AccountStatus.ARCHIVED))
        val balances = accounts.associate { it.id to it.anchor.amount }
        val ui = LedgerSnapshot(1, accounts, emptyList(), balances, 0, 0).toAccountsUiState(date, YearMonth.from(date))
        assertTrue(ui.balanceSummary.contains("500원"))
        assertTrue(ui.assetSummary.contains("1,000원"))
        assertTrue(ui.exclusions.contains("미확인 1개/보관 1개 제외"))
        assertTrue(ui.exclusions.contains("일반 계좌 제외"))
        assertEquals(date.toString(), ui.asOf)
        assertTrue(ui.accounts.single { it.id == id(40) }.description.contains("보관된 계좌"))
    }

    /** 모든 자산 기준액이 미확인인 경우 0원 소계로 바꾸지 않는다. 확인한0은 별도로 포함한다. */
    @Test fun unknownSubtotalIsNotConfirmedZero() {
        val account = account(10, AccountNature.SAVINGS, null)
        val snapshot = LedgerSnapshot(1, listOf(account), emptyList(), mapOf(account.id to null), 0, 0)
        assertEquals("저축·투자 장부 잔액: 확인된 금액 없음", snapshot.toAccountsUiState(date, YearMonth.from(date)).assetSummary)
        val known = snapshot.copy(accounts = listOf(account.copy(anchor = account.anchor.copy(amount = 0, confirmedAt = Instant.EPOCH, unknownReason = null))), balances = mapOf(account.id to 0))
        assertEquals("확인된 저축·투자 장부 소계 0원", known.toAccountsUiState(date, YearMonth.from(date)).assetSummary)
    }

    /** 조회일의 월과 선택 월이 달라도 실제 거래의 선택 월 전체를 기준일 이전까지 포함한다. */
    @Test fun selectedMonthIgnoresAnchorAndOtherMonthSummary() {
        val account = account(10, AccountNature.SAVINGS, null)
        val tx = LedgerTransaction(id(30), TransactionKind.TRANSFER, date.minusMonths(1), 300, id(20), id(10), AccountNature.GENERAL, AccountNature.SAVINGS, "", 1, 1)
        val snapshot = LedgerSnapshot(1, listOf(account), listOf(tx), mapOf(account.id to null), 999, 888)
        val ui = snapshot.toAccountsUiState(date, YearMonth.from(tx.date))
        assertEquals("납입 300원 · 인출 0원 · 순납입 300원", ui.flows)
        assertEquals("납입 0원 · 인출 0원 · 순납입 0원", snapshot.toAccountsUiState(date, YearMonth.from(date)).flows)
    }
}
