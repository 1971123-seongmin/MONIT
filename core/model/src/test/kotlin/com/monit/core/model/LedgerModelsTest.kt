package com.monit.core.model

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerModelsTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"

    /** 거래 종류별 실제 입출금 계좌를 요구하고 같은 계좌 이체를 거부한다. */
    @Test
    fun transactionRequiresAccountsMatchingItsKind() {
        val date = LocalDate.of(2026, 11, 3)
        assertEquals(first, CreateTransaction(second, TransactionKind.INCOME, date, 1, creditAccountId = first).creditAccountId)
        assertFailsWith<IllegalArgumentException> { CreateTransaction(second, TransactionKind.INCOME, date, 1) }
        assertFailsWith<IllegalArgumentException> {
            CreateTransaction(second, TransactionKind.EXPENSE, date, 1, creditAccountId = first)
        }
        assertFailsWith<IllegalArgumentException> {
            CreateTransaction(second, TransactionKind.TRANSFER, date, 1, first, first)
        }
        assertFailsWith<IllegalArgumentException> {
            CreateTransaction(second, TransactionKind.EXPENSE, date, 0, debitAccountId = first)
        }
    }

    /** 문자열의 문자 수가 아닌 UTF-8 bytes를 제한하며 잘못된 Unicode를 대체하지 않는다. */
    @Test
    fun textValidationPreservesUnicodeAndEnforcesByteLimit() {
        assertEquals("가".repeat(5461), requireLedgerText("가".repeat(5461)))
        assertEquals("a".repeat(16384), requireLedgerText("a".repeat(16384)))
        assertFailsWith<IllegalArgumentException> { requireLedgerText("가".repeat(5462)) }
        assertFailsWith<IllegalArgumentException> { requireLedgerText("\uD800") }
        assertFailsWith<IllegalArgumentException> { requireLedgerText(" ") }
    }

    /** UUID를 정규화해서 다른 입력과 합치지 않고 소문자 표준 표현만 허용한다. */
    @Test
    fun identityValidationRejectsNoncanonicalIds() {
        assertEquals(first, requireLedgerId(first))
        assertFailsWith<IllegalArgumentException> { requireLedgerId("1-1-1-1-1") }
        assertFailsWith<IllegalArgumentException> { requireLedgerId("abcdefab-abcd-abcd-abcd-abcdefabcdef".uppercase()) }
    }

    /** 미확인 금액/확인시각과 확인한 0원을 구분하고 모순된 기준 상태를 거부한다. */
    @Test
    fun anchorDistinguishesUnknownFromConfirmedZero() {
        val day = LocalDate.of(2026, 10, 31)
        assertEquals(null, BalanceAnchor(first, day, null, null, "미확인").amount)
        assertEquals(0L, BalanceAnchor(first, day, 0, Instant.EPOCH, null).amount)
        assertFailsWith<IllegalArgumentException> { BalanceAnchor(first, day, null, Instant.EPOCH, "미확인") }
        assertFailsWith<IllegalArgumentException> { BalanceAnchor(first, day, 0, null, null) }
    }
}
