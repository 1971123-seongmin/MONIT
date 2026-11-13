package com.monit.app.notifications

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import com.monit.feature.home.reminders.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReminderSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val store = ViewModelStore()
    private var number = 0

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun vm(repo: ReminderTestRepository) = ReminderSettingsViewModel(repo, repo.source, contexts).also {
        store.put("settings-${number++}", it)
        it.bind(repo.context)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** 조회는 선호를 변경하지 않고 실제 OS 세 조건과 미설정 규칙 기본값을 표시한다. */
    @Test fun readSeparatesUserPreferenceAndActualOsGates() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { os = ReminderAccess(false, false, false) }
        val model = vm(repo)
        assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
        assertFalse(model.state.value.ui.enabled)
        assertTrue(model.state.value.ui.permissionDenied)
        assertTrue(model.state.value.ui.channelBlocked)
        assertEquals("거부·철회", model.state.value.ui.permission)
        assertEquals("기본값 · 켜짐", model.state.value.ui.rules.single().preference)
        assertEquals(0, repo.writeCalls)
    }

    /** OS 거부 중 켠 사용자 선호를 유지하고 설정 복귀 때 실제 상태만 다시 조회한다. */
    @Test fun permissionDenialNeverRewritesPreferenceAndResumeReadsFreshState() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { os = ReminderAccess(false, true, true) }
        val model = vm(repo)
        model.setEnabled(true)
        advanceUntilIdle()
        assertTrue(model.state.value.ui.enabled)
        assertEquals("알림 불가", model.state.value.ui.status)
        repo.os = ReminderAccess(true, true, true)
        model.refresh()
        advanceUntilIdle()
        assertEquals("알림 켜짐", model.state.value.ui.status)
        assertEquals(1, repo.writeCalls)
        repo.os = ReminderAccess(true, true, false)
        model.refresh()
        advanceUntilIdle()
        assertEquals("알림 불가", model.state.value.ui.status)
        assertTrue(model.state.value.ui.channelBlocked)
        assertTrue(repo.config.enabled)
    }

    /** 전체 알림이 꺼져 있어도 일정별 선호만 저장하며 전체 선호를 바꾸지 않는다. */
    @Test fun ruleToggleChangesOnlyTheSelectedStoredPreference() = runTest(dispatcher) {
        val repo = ReminderTestRepository()
        val model = vm(repo)
        model.setRuleEnabled(reminderTestId(40), false)
        advanceUntilIdle()
        assertFalse(repo.config.enabled)
        assertFalse(model.state.value.ui.rules.single().enabled)
        assertEquals("개별 설정 · 꺼짐", model.state.value.ui.rules.single().preference)
        assertEquals(1, repo.writeCalls)
    }

    /**
     * 목적: 저장 중 연타로 서로 다른 설정 요청이 경합하지 않게 한다.
     * 조건: 설정 대역이 쓰기 전에 suspend gate에서 멈춘다.
     * 절차: 켜기 두 번→runCurrent→SAVING 확인→gate 해제 후 모든 작업 진행.
     * 기대: 요청1개, 확정 전 UI 선호를 성공으로 바꾸지 않고 완료 후 DB값을 다시 읽는다.
     */
    @Test fun repeatedToggleWhileSavingMakesOneRequest() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { gate = CompletableDeferred() }
        val model = vm(repo)
        model.setEnabled(true)
        model.setEnabled(true)
        runCurrent()
        assertEquals(ReminderSettingsPhase.SAVING, model.state.value.phase)
        assertEquals(1, repo.writeCalls)
        assertFalse(model.state.value.ui.enabled)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
        assertTrue(model.state.value.ui.enabled)
    }

    /** 저장 오류는 실제 저장값 재조회로 판정하며 요청값을 성공 상태로 표시하지 않는다. */
    @Test fun failedWriteReloadsConfirmedPreferenceWithoutFalseSuccess() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { failWrite = true }
        val model = vm(repo)
        model.setEnabled(true)
        advanceUntilIdle()
        assertFalse(model.state.value.ui.enabled)
        assertNotNull(model.state.value.ui.message)
        assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
    }

    /** 조회 실패를 허용/꺼짐 성공으로 대체하지 않고 명시 재조회로 복구한다. */
    @Test fun failedReadIsDistinctFromDisabledAndCanRetry() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { failRead = true }
        val model = vm(repo)
        assertEquals(ReminderSettingsPhase.FAILED, model.state.value.phase)
        model.setEnabled(true)
        advanceUntilIdle()
        assertEquals(0, repo.writeCalls)
        repo.failRead = false
        model.refresh()
        advanceUntilIdle()
        assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
    }

    /** 저장 세대가 다른 장부로 같은 VM을 재바인딩하면 기존 선호를 새 장부에 쓰지 않는다. */
    @Test fun wrongGenerationBlocksFurtherSettingWrites() = runTest(dispatcher) {
        val repo = ReminderTestRepository()
        val model = vm(repo)
        model.bind(LedgerContext(repo.context.ledgerId, reminderTestId(999)))
        model.setEnabled(true)
        advanceUntilIdle()
        assertEquals(ReminderSettingsPhase.FAILED, model.state.value.phase)
        assertEquals(0, repo.writeCalls)
    }

    /** 알림 클릭은 한 번 검증된 원래 조회 링크만 복구하며 설정·금융 쓰기를 하지 않는다. */
    @Test fun openTokenReadsOnceAndRejectsAnotherStorageGeneration() = runTest(dispatcher) {
        val repo = ReminderTestRepository()
        val model = ReminderOpenViewModel(repo)
        store.put("open", model)
        model.resolve(repo.context, repo.link!!.token)
        model.resolve(repo.context, repo.link!!.token)
        advanceUntilIdle()
        assertEquals(repo.link, model.state.value.link)
        assertEquals(1, repo.resolveCalls)
        assertEquals(0, repo.writeCalls)
        model.clear()
        model.resolve(LedgerContext(repo.context.ledgerId, reminderTestId(999)), repo.link!!.token)
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertNull(model.state.value.link)
        assertEquals(0, repo.writeCalls)
    }

    /**
     * 목적: 화면 종료 후 늦은 설정 쓰기가 다시 살아나지 않게 한다.
     * 조건: 쓰기 전 gate가 대기 중이며 ViewModelStore를 제거한다.
     * 절차: 저장 시작→VM 제거→gate 해제→새 VM 조회.
     * 기대: 원래 false 선호 유지, 자동 재제출 없이 새 화면에서 확인한다.
     */
    @Test fun clearedViewModelCancelsUncommittedToggleWithoutAutomaticReplay() = runTest(dispatcher) {
        val repo = ReminderTestRepository().apply { gate = CompletableDeferred() }
        val model = vm(repo)
        model.setEnabled(true)
        runCurrent()
        store.clear()
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        val recreated = vm(repo)
        assertFalse(repo.config.enabled)
        assertFalse(recreated.state.value.ui.enabled)
        assertEquals(1, repo.writeCalls)
    }
}
