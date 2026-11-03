package com.monit.core.model

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerDatesTest {
    private val todayInSeoul = LocalDate.of(2026, 11, 3)

    /** 고정한 서울 기준 오늘과 1900년 하한을 포함하며 실제 시간을 읽지 않는다. */
    @Test
    fun acceptsTransactionDateBounds() {
        assertEquals(LocalDate.of(1900, 1, 1), transactionDateOf(1900, 1, 1, todayInSeoul))
        assertEquals(todayInSeoul, transactionDateOf(2026, 11, 3, todayInSeoul))
    }

    /** 유효한 ISO 날짜라도 L3 하한 이전과 미래 실제 거래일은 거부한다. */
    @Test
    fun rejectsTransactionDatesOutsideContract() {
        assertFailsWith<IllegalArgumentException> {
            transactionDateOf(1899, 12, 31, todayInSeoul)
        }
        assertFailsWith<IllegalArgumentException> {
            transactionDateOf(2026, 11, 4, todayInSeoul)
        }
    }

    /** 평년 2/29·없는 월/일을 말일이나 다음 달로 자동 보정하지 않는다. */
    @Test
    fun rejectsImpossibleTransactionDates() {
        val invalidDates = listOf(
            Triple(2026, 2, 29),
            Triple(2026, 4, 31),
            Triple(2026, 0, 1),
            Triple(2026, 13, 1),
            Triple(2026, 11, 0),
        )
        for ((year, month, day) in invalidDates) {
            assertFailsWith<DateTimeException>("date=$year-$month-$day") {
                transactionDateOf(year, month, day, todayInSeoul)
            }
        }
    }

    /** 31일 규칙은 대상 월 길이를 따르며 평년·윤년과 예정일 범위 양 끝을 처리한다. */
    @Test
    fun recurringRuleClampsToTargetMonthEnd() {
        val expectedDates = listOf(
            LocalDate.of(1900, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2028, 2, 29),
            LocalDate.of(2026, 4, 30),
            LocalDate.of(9999, 12, 31),
        )
        for (expected in expectedDates) {
            assertEquals(expected, recurringDueDate(YearMonth.from(expected), 31))
        }
        assertEquals(LocalDate.of(2026, 2, 1), recurringDueDate(YearMonth.of(2026, 2), 1))
    }

    /** 없는 규칙 일자와 L3 밖의 예정 월은 clamp 대상이 아니라 입력 오류다. */
    @Test
    fun rejectsInvalidRecurringRuleAndMonth() {
        for (day in listOf(0, 32)) {
            assertFailsWith<IllegalArgumentException>("day=$day") {
                recurringDueDate(YearMonth.of(2026, 2), day)
            }
        }
        for (month in listOf(YearMonth.of(1899, 12), YearMonth.of(10000, 1))) {
            assertFailsWith<IllegalArgumentException>("month=$month") {
                recurringDueDate(month, 31)
            }
        }
    }

    /** 달력은 현재 선택일을 사용해 31→28/29→28/29, 반복 규칙은 3월에 다시 31일이다. */
    @Test
    fun calendarSelectionAndRecurringRuleKeepDifferentDayContracts() {
        for ((year, februaryDay) in listOf(2026 to 28, 2028 to 29)) {
            val january = LocalDate.of(year, 1, 31)
            val february = moveCalendarSelection(january, 1)
            val march = moveCalendarSelection(february, 1)

            assertEquals(LocalDate.of(year, 2, februaryDay), february)
            assertEquals(LocalDate.of(year, 3, februaryDay), march)
            assertEquals(LocalDate.of(year, 3, 31), recurringDueDate(YearMonth.of(year, 3), 31))
        }
    }

    /** 12월↔1월 이동은 연도와 일자를 함께 보존하며 미래 조회도 차단하지 않는다. */
    @Test
    fun calendarMonthMoveCrossesYearInBothDirections() {
        val december = LocalDate.of(2026, 12, 31)
        val january = LocalDate.of(2027, 1, 31)
        assertEquals(january, moveCalendarSelection(december, 1))
        assertEquals(december, moveCalendarSelection(january, -1))
    }

    /** 날짜 표현 범위 밖 이동을 감싸거나 다른 월로 고치지 않고 호출자에게 거부한다. */
    @Test
    fun rejectsCalendarMoveOutsideRepresentableDates() {
        assertFailsWith<DateTimeException> { moveCalendarSelection(LocalDate.MAX, 1) }
        assertFailsWith<DateTimeException> { moveCalendarSelection(LocalDate.MIN, -1) }
    }
}
