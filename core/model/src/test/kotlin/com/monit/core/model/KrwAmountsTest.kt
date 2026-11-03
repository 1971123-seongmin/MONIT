package com.monit.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KrwAmountsTest {
    /** 일반 금액과 L1 양 끝은 원 단위 정수 그대로 반환한다. */
    @Test
    fun acceptsTransactionAmountsWithinContract() {
        for (amount in listOf(1L, 25_000L, 999_999_999_999L)) {
            assertEquals(amount, requireTransactionAmount(amount))
        }
    }

    /** 0·음수·12자리 상한 초과를 보정하지 않고 입력 오류로 거부한다. */
    @Test
    fun rejectsInvalidTransactionAmounts() {
        for (amount in listOf(0L, -1L, Long.MIN_VALUE, 1_000_000_000_000L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException>("amount=$amount") {
                requireTransactionAmount(amount)
            }
        }
    }

    /** 빈 합계는 0이고 부호 있는 효과의 음수 결과를 허용한다. */
    @Test
    fun sumsEmptyAndSignedAmounts() {
        assertEquals(0L, sumSignedAmounts(emptyList()))
        assertEquals(-500L, sumSignedAmounts(listOf(1_000L, -1_500L)))
        assertEquals(0L, sumSignedAmounts(listOf(25_000L, -25_000L)))
    }

    /** L2 양 끝은 거래 입력 상한과 별개로 잘리지 않고 보존한다. */
    @Test
    fun acceptsBothTotalBounds() {
        assertEquals(Long.MAX_VALUE, sumSignedAmounts(listOf(Long.MAX_VALUE)))
        assertEquals(-Long.MAX_VALUE, sumSignedAmounts(listOf(-Long.MAX_VALUE)))
    }

    /** Long으로 감긴 값이 정상 범위처럼 보여도 정확한 최종 합계 초과는 산술 오류다. */
    @Test
    fun rejectsTotalsOutsideSymmetricLongRange() {
        val invalidTotals = listOf(
            listOf(Long.MAX_VALUE, 1L),
            listOf(Long.MAX_VALUE, 2L),
            listOf(Long.MAX_VALUE, Long.MAX_VALUE),
            listOf(-Long.MAX_VALUE, -Long.MAX_VALUE),
            listOf(-Long.MAX_VALUE, -1L),
            listOf(Long.MIN_VALUE),
        )
        for (amounts in invalidTotals) {
            assertFailsWith<ArithmeticException>("amounts=$amounts") {
                sumSignedAmounts(amounts)
            }
        }
    }

    /** 중간 합계 초과 후 상쇄되는 모든 서로 다른 순서는 동일한 최종 결과를 낸다. */
    @Test
    fun cancellationIsIndependentOfIntermediateRangeAndOrder() {
        val maximum = Long.MAX_VALUE
        val positiveOrders = listOf(
            listOf(maximum, maximum, -maximum),
            listOf(maximum, -maximum, maximum),
            listOf(-maximum, maximum, maximum),
        )
        for (amounts in positiveOrders) {
            assertEquals(maximum, sumSignedAmounts(amounts), "amounts=$amounts")
            val negativeAmounts = amounts.map { -it }
            assertEquals(-maximum, sumSignedAmounts(negativeAmounts), "amounts=$negativeAmounts")
        }
        // 부호 효과의 중간 값과 최종 합계의 허용 범위를 구별한다. L1 거래 입력을 뜻하지 않는다.
        assertEquals(-maximum, sumSignedAmounts(listOf(Long.MIN_VALUE, 1L)))
    }
}
