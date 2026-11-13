package com.monit.core.model

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PaymentRemindersTest {
    private val context = LedgerContext(id(1), id(2))
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val terms = PaymentTerms("합성 납입", PaymentPurpose.INSURANCE, 30_000, 31, id(10))
    private fun snapshot(date: LocalDate = LocalDate.of(2028, 2, 29), status: PaymentStatus = PaymentStatus.PENDING, active: Boolean = true, end: String? = null): PaymentMonthSnapshot {
        val month = date.toString().take(7)
        return PaymentMonthSnapshot(context, 1, month, listOf(PaymentRule(id(30), 1, "2026-01", end, terms)), listOf(MonthlyPayment(PaymentItemReference(id(40), id(30), month, 0, 1), 1, terms, date, status, active, null, null, null)))
    }

    /** 윤년 말일 회차의 세 단계는 서울 09시이며 30일 반복으로 계산하지 않는다. */
    @Test fun leapMonthEndUsesSeoulNineAndCalendarDayOffsets() {
        val rows = paymentReminders(snapshot())
        assertEquals(ReminderStage.entries, rows.map { it.stage })
        assertEquals(listOf("2028-02-28T00:00:00Z", "2028-02-29T00:00:00Z", "2028-03-01T00:00:00Z"), rows.map { it.scheduledAt.toString() })
        assertEquals(3, rows.map { it.key }.distinct().size)
    }

    /** 완료·건너뜀·비활성·종료 이후 보존 이력은 발송 후보가 아니다. */
    @Test fun terminalOrEndedSnapshotsNeverProduceReminderCandidates() {
        assertTrue(paymentReminders(snapshot(status = PaymentStatus.COMPLETED)).isEmpty())
        assertTrue(paymentReminders(snapshot(status = PaymentStatus.SKIPPED)).isEmpty())
        assertTrue(paymentReminders(snapshot(active = false)).isEmpty())
        assertTrue(paymentReminders(snapshot(status = PaymentStatus.RECONFIRM, end = "2028-01")).isEmpty())
        assertEquals(3, paymentReminders(snapshot(status = PaymentStatus.RECONFIRM, end = "2028-02")).size)
    }

    /** 최근 24시간의 최신 단계 하나만 고르고 경계보다 오래된 단계를 제외한다. */
    @Test fun delayedDeliveryKeepsOnlyLatestStageWithinStrictDayWindow() {
        val rows = paymentReminders(snapshot())
        assertEquals(ReminderStage.DUE, duePaymentReminders(rows, Instant.parse("2028-02-29T00:00:00Z"), emptySet()).single().stage)
        assertEquals(ReminderStage.AFTER, duePaymentReminders(rows, Instant.parse("2028-03-01T10:00:00Z"), emptySet()).single().stage)
        assertTrue(duePaymentReminders(rows, Instant.parse("2028-03-02T00:00:00Z"), emptySet()).isEmpty())
        assertTrue(duePaymentReminders(rows, Instant.parse("2028-02-27T23:59:59Z"), emptySet()).isEmpty())
    }

    /** 최신 단계가 이미 처리됐다고 오래된 단계로 되돌아가 재촉하지 않는다. */
    @Test fun consumedLatestStageNeverFallsBackToOlderStage() {
        val rows = paymentReminders(snapshot())
        val current = rows.single { it.stage == ReminderStage.DUE }
        assertTrue(duePaymentReminders(rows, current.scheduledAt.plusSeconds(1), setOf(current.key)).isEmpty())
    }

    /** 같은 회차의 일정 수정에도 단계 ID는 안정적이며 기기 시간대에 의존하지 않는다. */
    @Test fun logicalKeySurvivesReschedulingAndAccessRequiresEveryGate() {
        val before = paymentReminders(snapshot()).associateBy { it.stage }
        val after = paymentReminders(snapshot(LocalDate.of(2028, 2, 28))).associateBy { it.stage }
        assertEquals(before.keys, after.keys)
        before.forEach { (stage, value) -> assertEquals(value.key, after.getValue(stage).key) }
        assertTrue(ReminderAccess(true, true, true).allowed)
        assertFalse(ReminderAccess(false, true, true).allowed)
        assertFalse(ReminderAccess(true, false, true).allowed)
        assertFalse(ReminderAccess(true, true, false).allowed)
    }
}
