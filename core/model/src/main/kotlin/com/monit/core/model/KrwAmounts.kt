package com.monit.core.model

import java.math.BigInteger

private const val MAX_TRANSACTION_KRW = 999_999_999_999L
private val maximumTotal = BigInteger.valueOf(Long.MAX_VALUE)

/** L1 거래·예정액 범위를 검사한다. 부호 변환·반올림·상한 절삭을 하지 않는다. */
fun requireTransactionAmount(amount: Long): Long {
    require(amount in 1L..MAX_TRANSACTION_KRW) {
        "Transaction amount must be between 1 and $MAX_TRANSACTION_KRW KRW: $amount"
    }
    return amount
}

/**
 * L2의 부호 있는 원화 효과를 정확히 합산하고 최종 결과만 ±Long.MAX_VALUE로 제한한다.
 * 각 실제 거래의 L1 입력 검사는 호출 전에 별도로 수행해야 한다.
 *
 * @throws ArithmeticException 최종 합계가 L2 범위를 벗어날 때. Long.MIN_VALUE도 허용하지 않는다.
 */
fun sumSignedAmounts(amounts: Iterable<Long>): Long {
    val total = amounts.fold(BigInteger.ZERO) { sum, amount ->
        sum.add(BigInteger.valueOf(amount))
    }
    if (total < maximumTotal.negate() || total > maximumTotal) {
        throw ArithmeticException("Signed KRW total is outside the supported range: $total")
    }
    return total.longValueExact()
}
