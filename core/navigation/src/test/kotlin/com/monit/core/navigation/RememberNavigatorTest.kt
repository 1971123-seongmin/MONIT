package com.monit.core.navigation

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RememberNavigatorTest {
    @get:Rule
    val compose = createComposeRule()
    private val fixtures = mutableListOf<NavigationFixture>()

    @After
    fun tearDown() {
        fixtures.forEach { it.close() }
    }

    /**
     * 목적: 표시 재구성은 실행기를 복제하지 않고 host 이탈은 실제로 연결을 닫는다.
     * 조건: 실제 controller와 rememberNavigator를 제공하는 Compose composition이다.
     * 절차: tick 재구성 → host 제거 → 재생성을 차례로 적용한다.
     * 기대: 재구성 전후 동일 객체, 제거된 객체 요청은 무시, 재생성된 새 객체만 실행한다.
     */
    @Test
    fun recompositionRetainsInstanceAndRemovalClosesIt() {
        val fixture = NavigationFixture().also { fixtures += it }
        val visible = mutableStateOf(true)
        val tick = mutableIntStateOf(0)
        lateinit var observed: Navigator
        compose.setContent {
            val currentTick = tick.intValue
            if (visible.value) {
                val current = rememberNavigator(fixture.controller)
                SideEffect {
                    check(currentTick >= 0)
                    observed = current
                }
            }
        }
        compose.waitForIdle()
        val first = observed
        compose.runOnIdle { tick.intValue++ }
        compose.waitForIdle()
        assertSame(first, observed)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(NavigationResult.IGNORED, first.navigate(fixture.current, Form("old")))
            visible.value = true
        }
        compose.waitForIdle()
        assertNotSame(first, observed)
        compose.runOnIdle {
            assertEquals(NavigationResult.EXECUTED, observed.navigate(fixture.current, Form("new")))
        }
    }

    /** 같은 composition에서 controller 키가 바뀌면 옛 실행기를 닫고 새 연결만 사용한다. */
    @Test
    fun controllerReplacementClosesOldConnection() {
        val first = NavigationFixture().also { fixtures += it }
        val second = NavigationFixture().also { fixtures += it }
        val selected = mutableStateOf(first.controller)
        lateinit var observed: Navigator
        compose.setContent {
            val current = rememberNavigator(selected.value)
            SideEffect { observed = current }
        }
        compose.waitForIdle()
        val previous = observed
        compose.runOnIdle { selected.value = second.controller }
        compose.waitForIdle()
        assertNotSame(previous, observed)
        compose.runOnIdle {
            assertEquals(NavigationResult.IGNORED, previous.navigate(first.current, Form("old")))
            assertEquals(NavigationResult.EXECUTED, observed.navigate(second.current, Form("new")))
        }
    }
}
