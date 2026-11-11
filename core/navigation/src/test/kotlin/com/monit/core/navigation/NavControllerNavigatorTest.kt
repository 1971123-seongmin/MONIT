package com.monit.core.navigation

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.navigation.toRoute
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 실제 Navigation 2 stack/entry/SavedStateHandle 계약. 기기 Back·전원 손실 시험이 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NavControllerNavigatorTest {
    private lateinit var fixture: NavigationFixture

    @Before
    fun setUp() {
        fixture = NavigationFixture()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    /** 첫 navigate 뒤 source가 바뀌므로 동일 콜백의 연타가 두 번째 폼을 쌓지 않는다. */
    @Test
    fun repeatedNavigateFromOldSourceIsIgnored() {
        val source = fixture.current
        assertEquals(NavigationResult.EXECUTED, fixture.navigator.navigate(source, Form("one")))
        val form = fixture.current
        assertEquals(Form("one"), form.toRoute<Form>())
        assertEquals(NavigationResult.IGNORED, fixture.navigator.navigate(source, Form("two")))
        assertSame(form, fixture.current)
        assertEquals(NavigationResult.EXECUTED, fixture.navigator.back(form))
        assertSame(source, fixture.current)
    }

    /** pop 직후 같은 source를 다시 사용해도 호출 루트까지 연속 pop하지 않는다. */
    @Test
    fun repeatedBackPopsOnlyOneEntry() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val form = fixture.current
        assertEquals(NavigationResult.EXECUTED, fixture.navigator.back(form))
        val root = fixture.current
        assertEquals(NavigationResult.IGNORED, fixture.navigator.back(form))
        assertSame(root, fixture.current)
    }

    /** root의 일반 Back 실행기는 OS 종료를 대신하거나 마지막 목적지를 제거하지 않는다. */
    @Test
    fun backAtRootIsUnavailable() {
        val root = fixture.current
        assertEquals(NavigationResult.UNAVAILABLE, fixture.navigator.back(root))
        assertSame(root, fixture.current)
    }

    /** STARTED에서는 이동하지 않고 owner가 재개되면 같은 유효 UI 의도를 실행할 수 있다. */
    @Test
    fun inactiveSourceIsIgnoredWithoutQueueing() {
        val root = fixture.current
        fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(NavigationResult.IGNORED, fixture.navigator.navigate(root, Form("one")))
        fixture.owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertSame(root, fixture.current)
        assertEquals(NavigationResult.EXECUTED, fixture.navigator.navigate(root, Form("one")))
    }

    /** 호출 대상이 맞지 않으면 Back의 부수 효과가 없다. */
    @Test
    fun backRequiresMatchingCaller() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val source = fixture.current
        assertEquals(NavigationResult.UNAVAILABLE, fixture.navigator.back(source) { false })
        assertSame(source, fixture.current)
    }

    /** 검증한 caller에만 결과를 기록하고 source 한 개를 제거한다. 재호출은 무시한다. */
    @Test
    fun resultReturnsToExactCallerOnce() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val caller = fixture.current
        fixture.navigator.navigate(caller, Registration)
        val source = fixture.current
        val result = Bundle().apply { putString("value", "new") }
        assertEquals(
            NavigationResult.EXECUTED,
            fixture.navigator.returnWithResult(source, { it === caller }, "result", result),
        )
        assertSame(caller, fixture.current)
        assertEquals("new", caller.savedStateHandle.get<Bundle>("result")?.getString("value"))
        assertEquals(
            NavigationResult.IGNORED,
            fixture.navigator.returnWithResult(source, { it === caller }, "result", result),
        )
        assertSame(caller, fixture.current)
    }

    /** 같은 타입의 이전 entry가 여러 개여도 바로 아래 caller가 틀리면 더 아래로 반환하지 않는다. */
    @Test
    fun mismatchDoesNotSearchEarlierCallerOrWriteResult() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val first = fixture.current
        fixture.navigator.navigate(first, Form("two"))
        val second = fixture.current
        fixture.navigator.navigate(second, Registration)
        val source = fixture.current
        assertEquals(
            NavigationResult.UNAVAILABLE,
            fixture.navigator.returnWithResult(source, { it === first }, "result", Bundle()),
        )
        assertSame(source, fixture.current)
        assertFalse(first.savedStateHandle.contains("result"))
        assertFalse(second.savedStateHandle.contains("result"))
    }

    /** pop 거부 뒤 원래 없었던 결과 키는 다시 없어야 한다. */
    @Test
    fun refusedPopRestoresAbsentKey() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val caller = fixture.current
        fixture.navigator.navigate(caller, Registration)
        val source = fixture.current
        fixture.probe.refusePop = true
        assertEquals(
            NavigationResult.UNAVAILABLE,
            fixture.navigator.returnWithResult(source, { it === caller }, "result", Bundle()),
        )
        assertFalse(caller.savedStateHandle.contains("result"))
        assertSame(source, fixture.current)
    }

    /** pop 거부 뒤 기존 값은 타입을 임의 변환하지 않고 원래 그대로 보존한다. */
    @Test
    fun refusedPopRestoresExistingValue() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val caller = fixture.current
        caller.savedStateHandle["result"] = "previous"
        fixture.navigator.navigate(caller, Registration)
        fixture.probe.refusePop = true
        assertEquals(
            NavigationResult.UNAVAILABLE,
            fixture.navigator.returnWithResult(
                fixture.current,
                { it === caller },
                "result",
                Bundle(),
            ),
        )
        assertEquals("previous", caller.savedStateHandle.get<String>("result"))
    }

    /** null이 들어 있던 키와 존재하지 않던 키를 구분한다. */
    @Test
    fun refusedPopRestoresExistingNullKey() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val caller = fixture.current
        caller.savedStateHandle.set<String?>("result", null)
        fixture.navigator.navigate(caller, Registration)
        fixture.probe.refusePop = true
        fixture.navigator.returnWithResult(fixture.current, { it === caller }, "result", Bundle())
        assertTrue(caller.savedStateHandle.contains("result"))
        assertEquals(null, caller.savedStateHandle.get<Any?>("result"))
    }

    /** 예외도 결과 키를 복구하되 graph/실행 오류를 정상 값으로 삼키지 않는다. */
    @Test
    fun thrownPopRestoresValueAndPropagates() {
        fixture.navigator.navigate(fixture.current, Form("one"))
        val caller = fixture.current
        caller.savedStateHandle["result"] = "previous"
        fixture.navigator.navigate(caller, Registration)
        val source = fixture.current
        fixture.probe.throwOnPop = true
        assertFailsWith<IllegalStateException> {
            fixture.navigator.returnWithResult(source, { it === caller }, "result", Bundle())
        }
        assertEquals("previous", caller.savedStateHandle.get<String>("result"))
        assertSame(source, fixture.current)
    }

    /** 닫힌 실행기는 새 유효 source에도 동작하지 않고 새 실행기만 사용할 수 있다. */
    @Test
    fun closedHostDoesNotReplayIntoNewNavigator() {
        val root = fixture.current
        fixture.navigator.close()
        assertEquals(NavigationResult.IGNORED, fixture.navigator.navigate(root, Form("one")))
        val replacement = NavControllerNavigator(fixture.controller)
        try {
            assertEquals(NavigationResult.EXECUTED, replacement.navigate(root, Form("two")))
            assertEquals(Form("two"), fixture.current.toRoute<Form>())
        } finally {
            replacement.close()
        }
    }

    /**
     * 목적: UI 요청을 임의 background thread에서 실행하지 않는다.
     * 조건: 실제 Default dispatcher에서 같은 유효 entry로 호출한다.
     * 절차: main-thread 검사 예외를 기다린 뒤 main의 최종 목적지를 확인한다.
     * 기대: 예외 전파, 이동/예약 없음.
     */
    @Test
    fun backgroundCallIsRejectedBeforeNavigation() = runTest {
        val root = fixture.current
        withContext(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                fixture.navigator.navigate(root, Form("one"))
            }
        }
        assertSame(root, fixture.current)
    }

    /** 저장된 route 인자·작은 UI 상태를 새 controller에서 복원하고 옛 entry로는 실행하지 않는다. */
    @Test
    fun restoredStackKeepsArgumentsAndRejectsOldEntry() {
        fixture.navigator.navigate(fixture.current, Form("original"))
        val previous = fixture.current
        previous.savedStateHandle["month"] = "2026-08"
        val restored = NavigationFixture(requireNotNull(fixture.controller.saveState()))
        try {
            assertNotSame(previous, restored.current)
            assertEquals(Form("original"), restored.current.toRoute<Form>())
            assertEquals("2026-08", restored.current.savedStateHandle.get<String>("month"))
            assertEquals(NavigationResult.IGNORED, restored.navigator.back(previous))
        } finally {
            restored.close()
        }
    }
}
