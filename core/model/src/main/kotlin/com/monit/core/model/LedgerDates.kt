package com.monit.core.model

// CI #30 측정 입력: 소스 변경 경로만 검증하며 금융 계산 동작은 바꾸지 않는다.

import java.time.LocalDate
import java.time.YearMonth

private val earliestLedgerDate = LocalDate.of(1900, 1, 1)

/**
 * L3 실제 거래일을 엄격히 생성한다. 호출자가 Asia/Seoul 기준 오늘을 전달한다.
 * 존재하지 않는 일자는 LocalDate.of의 DateTimeException으로 거부하며 자동 보정하지 않는다.
 * 유효 일자라도 1900-01-01 이전이나 오늘 이후이면 IllegalArgumentException으로 거부한다.
 */
fun transactionDateOf(
    year: Int,
    month: Int,
    dayOfMonth: Int,
    todayInSeoul: LocalDate,
): LocalDate {
    val date = LocalDate.of(year, month, dayOfMonth)
    require(date >= earliestLedgerDate && date <= todayInSeoul) {
        "Transaction date must be between $earliestLedgerDate and $todayInSeoul: $date"
    }
    return date
}

/**
 * 원래 규칙의 1..31일을 대상 월 말일로 보정한다. 이전 월 예정일을 규칙 일자로 덮어쓰지 않는다.
 * L3에 따라 예정 월은 1900-01..9999-12이며 범위 밖 규칙/월은 IllegalArgumentException으로 거부한다.
 */
fun recurringDueDate(month: YearMonth, ruleDayOfMonth: Int): LocalDate {
    require(month.year in 1900..9999) { "Recurring month must be within 1900..9999: $month" }
    require(ruleDayOfMonth in 1..31) { "Recurring day must be within 1..31: $ruleDayOfMonth" }
    return month.atDay(minOf(ruleDayOfMonth, month.lengthOfMonth()))
}

/**
 * 현재 선택일의 일자를 이동 월 길이로 clamp한다. 숨겨진 원래 일자 anchor를 유지하지 않는다.
 * 미래 월 조회도 허용하며 거래일 저장 검사는 별도다. 표현 범위를 넘는 이동은 예외를 전파한다.
 */
fun moveCalendarSelection(selectedDate: LocalDate, monthOffset: Long): LocalDate =
    selectedDate.plusMonths(monthOffset)
