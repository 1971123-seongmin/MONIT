package com.monit.core.model

import java.time.LocalDate
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LedgerIdValidationTest {
    private val canonical = "abcdef01-2345-6789-abcd-ef0123456789"

    /** 모든 위치의 ASCII 치환을 이전 UUID 왕복 판정과 비교해 대소문자·구분자 계약을 보존한다. */
    @Test
    fun everyAsciiPositionMatchesPreviousAcceptance() {
        for (index in canonical.indices) {
            for (code in 0..127) {
                val candidate = canonical.replaceRange(index, index + 1, code.toChar().toString())
                assertEquals(previouslyAccepted(candidate), accepted(candidate), "position=$index ascii=$code")
            }
        }
    }

    /** 고정 seed의 UUID와 길이·문자 변형을 비교하며 정상 입력은 동일한 String 인스턴스로 돌려준다. */
    @Test
    fun deterministicUuidCorpusMatchesPreviousAcceptanceWithoutNormalizing() {
        val random = Random(20261114L)
        repeat(1024) {
            val candidate = UUID(random.nextLong(), random.nextLong()).toString()
            assertSame(candidate, requireLedgerId(candidate))
            for (changed in listOf(candidate.uppercase(), candidate.drop(1), candidate + "\n", " " + candidate)) {
                assertEquals(previouslyAccepted(changed), accepted(changed))
            }
        }
    }

    /** nil·모든 비트가 1인 값·모든 version/variant nibble을 허용해 새로운 UUID 정책을 추가하지 않는다. */
    @Test
    fun allVersionAndVariantNibblesRemainAccepted() {
        for (version in "0123456789abcdef") {
            for (variant in "0123456789abcdef") {
                val value = "00000000-0000-${version}000-${variant}000-000000000000"
                assertEquals(true, previouslyAccepted(value))
                assertSame(value, requireLedgerId(value))
            }
        }
        for (value in listOf("00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff")) {
            assertSame(value, requireLedgerId(value))
        }
    }

    /** Unicode 숫자·전각 문자·단독 surrogate·축약형·공백은 정규화하지 않고 IllegalArgumentException으로 거부한다. */
    @Test
    fun nonAsciiAndNoncanonicalInputsRemainRejected() {
        val invalid = listOf(
            canonical.replace('0', '\u0660'),
            canonical.replace('0', '\uFF10'),
            canonical.replace('a', '\uFF41'),
            "\uD800" + canonical.drop(1),
            canonical.dropLast(1) + "\uDC00",
            "1-1-1-1-1",
            "",
            canonical.uppercase(),
            canonical + "\n",
            " " + canonical,
            canonical.dropLast(1),
        )
        for (value in invalid) {
            assertEquals(false, previouslyAccepted(value))
            assertFailsWith<IllegalArgumentException> { requireLedgerId(value) }
        }
    }

    /** ID와 금액/텍스트가 모두 잘못돼도 기존 생성자 순서대로 ID 오류가 먼저 관찰된다. */
    @Test
    fun constructorsStillValidateIdentityBeforeOtherFields() {
        val invalid = canonical.uppercase()
        val date = LocalDate.of(2026, 11, 14)
        val transaction = assertFailsWith<IllegalArgumentException> {
            CreateTransaction(invalid, TransactionKind.INCOME, date, 0)
        }
        val account = assertFailsWith<IllegalArgumentException> {
            CreateAccount(invalid, canonical, "", AccountNature.GENERAL, date, "")
        }
        assertEquals("Expected a lowercase UUID", transaction.message)
        assertEquals("Expected a lowercase UUID", account.message)
    }

    private fun previouslyAccepted(value: String): Boolean = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun accepted(value: String): Boolean = runCatching { requireLedgerId(value) }.isSuccess
}
