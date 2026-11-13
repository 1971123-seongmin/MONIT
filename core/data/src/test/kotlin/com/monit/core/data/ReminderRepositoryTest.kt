package com.monit.core.data

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReminderRepositoryTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = MutableReminderClock(Instant.parse("2026-11-13T00:01:00Z"))
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val allowed = ReminderAccess(true, true, true)
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private lateinit var reminders: RoomReminderRepository
    private val dao get() = db.reminderDao()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        reopen()
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("reminders.db")
    }
    private fun reopen() {
        db = createLedgerDatabase(app, "reminders.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
        payments = RoomPaymentRepository(db, ledger, clock, contexts)
        reminders = RoomReminderRepository(db, clock, contexts)
    }
    private suspend fun seed(day: Int = 13, start: String = "2026-01", end: String? = null, number: Int = 40) {
        if (db.ledgerDao().entryLedgers().isEmpty()) {
            ledger.initialize(context)
            ledger.createAccount(context, id(110), CreateAccount(id(10), id(11), "합성 출금", AccountNature.GENERAL, LocalDate.of(2026, 11, 10), "미확인"))
        }
        payments.submit(context, id(number + 100), PaymentCommand.RegisterRule(id(number), PaymentTerms("합성 일정 $number", PaymentPurpose.INSURANCE, 30_000, day, id(10)), start, end))
    }
    private suspend fun enabled() = reminders.configuration(context).let { reminders.setEnabled(context, it.revision, true) }
    private suspend fun item(month: String = "2026-11", rule: Int = 40) = payments.month(context, month).items.single { it.reference.ruleId == id(rule) }

    /** 최초 명시 켜기 전에는 현재 유효 회차가 있어도 통지/예약을 만들지 않는다. */
    @Test fun optInAndEveryOsGateAreRequiredWithoutChangingFinance() = runTest {
        seed()
        val before = ledger.snapshot(context, LocalDate.of(2026, 11, 13))
        val off = assertNotNull(reminders.prepare(allowed))
        assertFalse(off.configuration.enabled)
        assertNull(off.delivery)
        assertNull(off.nextAt)
        reminders.applied(off, false)
        enabled()
        for (access in listOf(ReminderAccess(false, true, true), ReminderAccess(true, false, true), ReminderAccess(true, true, false))) {
            val blocked = assertNotNull(reminders.prepare(access))
            assertNull(blocked.delivery)
            assertNull(blocked.nextAt)
            assertTrue(blocked.cancelNotification)
        }
        val on = assertNotNull(reminders.prepare(allowed))
        assertEquals(ReminderStage.DUE, on.delivery!!.members.single().stage)
        assertEquals(before, ledger.snapshot(context, LocalDate.of(2026, 11, 13)))
    }

    /**
     * 목적: 응답 유실/재열기/동시 준비가 일별 중복 요약이나 단계 되돌림을 만들지 않는다.
     * 조건: 같은 날 유효한 두 회차와 실제 파일 DB가 있다.
     * 절차: 병렬 prepare→발송 기록→재열기→같은 날 재준비한다.
     * 기대: 일별 token 하나·두 멤버, 이미 처리한 날 새 발송 없음, 다음날 최신 AFTER 하나씩이다.
     */
    @Test fun dailyClaimSurvivesConcurrencyAndReopenWithoutAdditionalPush() = runTest {
        seed()
        seed(number = 41)
        enabled()
        val a = async(Dispatchers.Default) { reminders.prepare(allowed) }
        val b = async(Dispatchers.Default) { reminders.prepare(allowed) }
        val first = assertNotNull(a.await())
        val second = assertNotNull(b.await())
        assertEquals(first.delivery!!.token, second.delivery!!.token)
        assertEquals(2, first.delivery!!.members.size)
        assertFalse(reminders.current(first))
        assertTrue(reminders.applied(second, true))
        db.close()
        reopen()
        assertNull(reminders.prepare(allowed)!!.delivery)
        assertEquals(1, dao.days(context.ledgerId, context.storageGeneration).size)
        clock.value = Instant.parse("2026-11-14T00:01:00Z")
        val next = reminders.prepare(allowed)!!
        assertTrue(next.delivery!!.members.all { it.stage == ReminderStage.AFTER })
        assertNotEquals(first.delivery!!.token, next.delivery!!.token)
    }

    /** 같은 날 뒤늦은 토글·추가 회차가 이미 처리한 요약을 새 푸시로 늘리지 않는다. */
    @Test fun togglesAndNewRulesAfterDailyPostDoNotReplaySummary() = runTest {
        seed()
        enabled()
        val first = reminders.prepare(allowed)!!
        reminders.applied(first, true)
        val on = reminders.configuration(context)
        val off = reminders.setEnabled(context, on.revision, false)
        reminders.setEnabled(context, off.revision, true)
        seed(number = 41)
        assertNull(reminders.prepare(allowed)!!.delivery)
    }

    /** 규칙별 제외는 해당 일정만 거르고 전체 off/on에도 선호와 금융 revision을 보존한다. */
    @Test fun individualPreferencesSurviveGlobalToggleAndAreIdempotent() = runTest {
        seed()
        seed(number = 41)
        val before = ledger.snapshot(context, LocalDate.of(2026, 11, 13))
        val initial = reminders.configuration(context)
        assertTrue(initial.rules.all { it.enabled && it.isDefault && it.revision == 0L })
        val selected = reminders.setRuleEnabled(context, id(40), 0, false)
        val duplicate = reminders.setRuleEnabled(context, id(40), 0, false)
        assertEquals(selected, duplicate)
        enabled()
        val plan = reminders.prepare(allowed)!!
        assertEquals(listOf(item(rule = 41).reference.id), plan.delivery!!.members.map { it.itemId })
        val off = reminders.setEnabled(context, plan.configuration.revision, false)
        val on = reminders.setEnabled(context, off.revision, true)
        assertFalse(on.rules.single { it.ruleId == id(40) }.enabled)
        assertEquals(before, ledger.snapshot(context, LocalDate.of(2026, 11, 13)))
        assertFailsWith<LedgerConflictException> { reminders.setRuleEnabled(context, id(40), 0, true) }
        assertFailsWith<IllegalStateException> { reminders.setRuleEnabled(context, id(999), 0, false) }
    }

    /** 완료/건너뜀/해제/정정의 금융 commit은 dirty와 원자이며 수신 때 최신 상태를 다시 읽는다. */
    @Test fun committedPaymentAndTransactionChangesInvalidatePlanAndRecomputeTargets() = runTest {
        seed()
        enabled()
        val scheduled = reminders.prepare(allowed)!!
        reminders.applied(scheduled, true)
        assertFalse(reminders.configuration(context).dirty)
        val current = item()
        payments.submit(context, id(150), PaymentCommand.Confirm(current.reference, PaymentTransaction(CreateTransaction(id(50), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 13), 30_000, id(10))), true, ""))
        assertTrue(reminders.configuration(context).dirty)
        assertFalse(reminders.current(scheduled))
        val completed = reminders.prepare(allowed)!!
        assertNull(completed.delivery)
        assertTrue(completed.cancelNotification)
        reminders.applied(completed, false)
        ledger.updateTransaction(context, id(151), UpdateTransaction(CreateTransaction(id(50), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 13), 25_000, id(10)), 1))
        assertTrue(reminders.configuration(context).dirty)
        assertEquals(PaymentStatus.RECONFIRM, item().status)
        // 이전 요약을 이미 처리한 날에는 재확인으로 바뀌어도 추가 발송하지 않는다.
        assertNull(reminders.prepare(allowed)!!.delivery)
    }

    /** 최근 24시간을 넘긴 누락은 발송하지 않으며 미래 규칙은 시작 월의 실제 달력으로 예약한다. */
    @Test fun expiredNotificationsDoNotReplayAndFutureStartUsesCalendarMonth() = runTest {
        seed(day = 10, end = "2026-11")
        enabled()
        assertNull(reminders.prepare(allowed)!!.delivery)
        assertNull(reminders.prepare(allowed)!!.nextAt)
        seed(day = 31, start = "2028-02", number = 41)
        val future = reminders.prepare(allowed)!!
        assertEquals(Instant.parse("2028-02-28T00:00:00Z"), future.nextAt)
    }

    /** 월 경계의 다음날 단계와 다음 월 1일 전날 단계를 같은 하루 요약으로 합친다. */
    @Test fun monthBoundaryIncludesPreviousAndNextMonthCandidates() = runTest {
        seed(day = 31)
        seed(day = 1, number = 41)
        enabled()
        clock.value = Instant.parse("2026-12-01T00:01:00Z")
        val values = reminders.prepare(allowed)!!.delivery!!.members
        assertTrue(values.any { it.month == "2026-11" && it.stage == ReminderStage.AFTER })
        assertTrue(values.any { it.month == "2026-12" && it.stage == ReminderStage.DUE })
    }

    /** OS 실패는 금융을 바꾸지 않고 원래 일별 token/dirty를 유지해 명시 재조정을 허용한다. */
    @Test fun failedOsApplicationRetainsOriginalClaimAndFinance() = runTest {
        seed()
        enabled()
        val before = ledger.snapshot(context, LocalDate.of(2026, 11, 13))
        val plan = reminders.prepare(allowed)!!
        reminders.failed(plan)
        val failure = reminders.configuration(context)
        assertTrue(failure.failed)
        assertTrue(failure.dirty)
        val retry = reminders.prepare(allowed)!!
        assertEquals(plan.delivery!!.token, retry.delivery!!.token)
        reminders.applied(retry, true)
        assertFalse(reminders.configuration(context).failed)
        assertEquals(before, ledger.snapshot(context, LocalDate.of(2026, 11, 13)))
    }

    /** 오래된 예약·잘못된 context와 복원 세대는 차단하고 OS 상태를 복원값으로 간주하지 않는다. */
    @Test fun alarmTokenTimeAndStorageGenerationAreRevalidated() = runTest {
        seed(day = 14)
        enabled()
        val plan = reminders.prepare(allowed)!!
        assertFalse(reminders.acceptsAlarm(context, plan.token))
        clock.value = assertNotNull(plan.nextAt)
        assertTrue(reminders.acceptsAlarm(context, plan.token))
        assertFalse(reminders.acceptsAlarm(context, id(777)))
        val next = LedgerContext(context.ledgerId, id(902))
        db.openHelper.writableDatabase.execSQL("UPDATE ledgers SET storageGeneration=?", arrayOf(next.storageGeneration))
        assertFalse(reminders.acceptsAlarm(context, plan.token))
        assertFailsWith<IllegalStateException> { reminders.configuration(next) }
        reminders.afterRestore(next)
        assertTrue(reminders.configuration(next).enabled)
        assertTrue(reminders.configuration(next).dirty)
        assertNull(reminders.configuration(next).nextAt)
    }

    /**
     * 목적: 규칙별 설정 경합이 중복 revision이나 다른 일정의 갱신 손실을 만들지 않는다.
     * 조건: 같은 원래 revision0을 가진 두 동시 끄기 요청이다.
     * 절차: 실제 Default dispatcher 두 코루틴으로 설정 후 stale 반대값을 제출한다.
     * 기대: 선호 한 행/revision1, 같은 값 멱등, 오래된 반대값 거부·금융 revision 불변이다.
     */
    @Test fun concurrentRuleToggleIsValueIdempotentAndRejectsStaleOppositeValue() = runTest {
        seed()
        val revision = ledger.snapshot(context, LocalDate.of(2026, 11, 13)).revision
        val first = async(Dispatchers.Default) { reminders.setRuleEnabled(context, id(40), 0, false) }
        val second = async(Dispatchers.Default) { reminders.setRuleEnabled(context, id(40), 0, false) }
        first.await()
        second.await()
        assertEquals(1, dao.rules(context.ledgerId).size)
        assertEquals(1, dao.rules(context.ledgerId).single().revision)
        assertFailsWith<LedgerConflictException> { reminders.setRuleEnabled(context, id(40), 0, true) }
        assertFalse(reminders.configuration(context).rules.single().enabled)
        assertEquals(revision, ledger.snapshot(context, LocalDate.of(2026, 11, 13)).revision)
    }

    /** 설정 CAS 뒤 실패하면 먼저 쓴 규칙별 값과 dirty도 함께 rollback한다. */
    @Test fun preferenceSqlFailureRollsBackSelectedRuleWithoutChangingFinance() = runTest {
        seed()
        enabled()
        val before = reminders.configuration(context)
        val finance = ledger.snapshot(context, LocalDate.of(2026, 11, 13))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reminder_settings_fail BEFORE UPDATE ON reminder_settings BEGIN SELECT RAISE(ABORT, 'synthetic settings failure'); END")
        assertFailsWith<Exception> { reminders.setRuleEnabled(context, id(40), 0, false) }
        assertTrue(dao.rules(context.ledgerId).isEmpty())
        assertEquals(before, reminders.configuration(context))
        assertEquals(finance, ledger.snapshot(context, LocalDate.of(2026, 11, 13)))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reminder_settings_fail")
        reminders.setRuleEnabled(context, id(40), 0, false)
        assertFalse(reminders.configuration(context).rules.single().enabled)
    }

    /** 준비 단계 오류도 실제 유효 장부가 있을 때 실패/dirty로 남기며 금융값을 바꾸지 않는다. */
    @Test fun failureBeforePlanCanBeMarkedWithoutInventingAnOsSuccess() = runTest {
        seed()
        enabled()
        val before = ledger.snapshot(context, LocalDate.of(2026, 11, 13))
        val clean = reminders.prepare(allowed)!!
        reminders.applied(clean, true)
        reminders.failed(null)
        assertTrue(reminders.configuration(context).failed)
        assertTrue(reminders.configuration(context).dirty)
        assertEquals(before, ledger.snapshot(context, LocalDate.of(2026, 11, 13)))
        assertNull(reminders.prepare(allowed)!!.delivery)
    }

    /** 아직 장부가 없는 앱 시작은 금융 메타를 자동 생성하지 않는다. */
    @Test fun emptyStartupDoesNotCreateLedgerOrSettings() = runTest {
        assertNull(reminders.prepare(allowed))
        assertTrue(db.ledgerDao().entryLedgers().isEmpty())
        assertFalse(db.ledgerDao().hasBusinessRecords())
    }
}

private class MutableReminderClock(var value: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
    override fun instant(): Instant = value
}
