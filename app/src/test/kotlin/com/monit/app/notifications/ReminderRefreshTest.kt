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

/** 권한 callback·생명주기 복귀·DB 관찰이 겹치는 조회 수명 시험. 실제 OS 왕복은 기기 QA와 구분한다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ReminderRefreshTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val store = ViewModelStore()
    private val base = ReminderTestRepository()

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(read: suspend () -> ReminderConfiguration): ReminderSettingsViewModel {
        val repository = object : ReminderRepository by base {
            override suspend fun configuration(context: LedgerContext): ReminderConfiguration = read()
        }
        return ReminderSettingsViewModel(repository, base.source, contexts).also { store.put("settings", it) }
    }

    /**
     * 목적: 권한 응답/복귀/변경 관찰의 연속 요청이 진행 중 Room 조회를 서로 취소하지 않는다.
     * 조건: 첫 조회와 최신 재조회가 별도 gate에서 대기하고 그 사이 OS 접근 대역 상태가 바뀐다.
     * 절차: bind/첫 조회→refresh 두 번과 변경 신호→첫 해제→최신 조회 진입→최신 해제.
     * 기대: 조회 직렬화·취소0, 최신 조회 전 LOADING, 완료 후 최신 권한/채널과 기존 선호·설정 쓰기0이다.
     */
    @Test fun overlappingReturnsFinishCurrentReadThenReadLatestWithoutCancellation() = runTest(dispatcher) {
        val first = CompletableDeferred<Unit>()
        val latest = CompletableDeferred<Unit>()
        var reads = 0
        var cancelled = 0
        base.config = base.config.copy(enabled = true)
        val model = model {
            val captured = base.config
            val gate = if (++reads == 1) first else latest
            try {
                gate.await()
            } catch (error: CancellationException) {
                cancelled++
                throw error
            }
            captured
        }
        try {
            model.bind(base.context)
            runCurrent()
            base.os = ReminderAccess(true, true, false)
            model.refresh()
            model.refresh()
            base.signals.value = !base.signals.value
            runCurrent()
            assertEquals(0, cancelled)
            assertEquals(1, reads)
            first.complete(Unit)
            runCurrent()
            assertEquals(2, reads)
            assertEquals(ReminderSettingsPhase.LOADING, model.state.value.phase)
            latest.complete(Unit)
            advanceUntilIdle()
            assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
            assertTrue(model.state.value.ui.enabled)
            assertFalse(model.state.value.ui.permissionDenied)
            assertTrue(model.state.value.ui.channelBlocked)
            assertEquals("알림 불가", model.state.value.ui.status)
            assertEquals(0, cancelled)
            assertEquals(0, base.writeCalls)
        } finally {
            first.complete(Unit)
            latest.complete(Unit)
        }
    }

    /**
     * 목적: 부모가 활성인 조회 자체의 취소도 LOADING을 남기지 않는다.
     * 조건: 설정 대역이 최초 조회에서 CancellationException을 던진다.
     * 절차: bind/독립 취소→실패 확인→대역 해제/OS 앱 차단 변경→명시 refresh.
     * 기대: FAILED 뒤 최신 projection의 READY로 복구하며 선호 쓰기0이다.
     */
    @Test fun independentlyCancelledReadIsVisibleFailureAndCanRetryWithoutWritingPreference() = runTest(dispatcher) {
        var cancelled = true
        val model = model {
            if (cancelled) throw CancellationException("synthetic query cancellation")
            base.config
        }
        model.bind(base.context)
        advanceUntilIdle()
        assertEquals(ReminderSettingsPhase.FAILED, model.state.value.phase)
        assertFalse(model.state.value.ui.allowed)
        cancelled = false
        base.os = ReminderAccess(true, false, true)
        model.refresh()
        advanceUntilIdle()
        assertEquals(ReminderSettingsPhase.READY, model.state.value.phase)
        assertTrue(model.state.value.ui.appBlocked)
        assertFalse(model.state.value.ui.enabled)
        assertEquals(0, base.writeCalls)
    }

    /**
     * 목적: 장부/세대가 바뀐 뒤 이전 조회가 새 상태를 덮지 않는다.
     * 조건: 오래된 조회가 취소에 협력하지 않는 gate에 대기한다.
     * 절차: bind/조회→다른 세대 bind→이전 해제→쓰기/재조회 시도.
     * 기대: FAILED 유지·이전 권한 성공 미표시·설정 쓰기0이다.
     */
    @Test fun invalidatedGenerationRejectsLateReadAndFurtherWrites() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val model = model {
            withContext(NonCancellable) {
                release.await()
                base.config
            }
        }
        try {
            model.bind(base.context)
            runCurrent()
            model.bind(LedgerContext(base.context.ledgerId, reminderTestId(999)))
            val blocked = model.state.value
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(ReminderSettingsPhase.FAILED, model.state.value.phase)
            assertEquals(blocked, model.state.value)
            model.refresh()
            model.setEnabled(true)
            advanceUntilIdle()
            assertEquals(blocked, model.state.value)
            assertEquals(0, base.writeCalls)
        } finally {
            release.complete(Unit)
        }
    }

    /**
     * 목적: 부모/VM 종료 취소를 새 오류/READY로 바꾸거나 늦은 값을 표시하지 않는다.
     * 조건: 조회의 NonCancellable 정리를 gate로 지연한다.
     * 절차: 조회→store.clear→종료 시점 상태 보관→정리 해제.
     * 기대: 제거된 VM 상태 그대로·선호 쓰기0이며 정리 완료를 기다린다.
     */
    @Test fun clearingViewModelDiscardsLateReadWithoutPublishingAReplacementState() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val model = model {
            withContext(NonCancellable) {
                release.await()
                base.config
            }
        }
        try {
            model.bind(base.context)
            runCurrent()
            store.clear()
            val cleared = model.state.value
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(cleared, model.state.value)
            assertEquals(0, base.writeCalls)
        } finally {
            release.complete(Unit)
        }
    }
}
