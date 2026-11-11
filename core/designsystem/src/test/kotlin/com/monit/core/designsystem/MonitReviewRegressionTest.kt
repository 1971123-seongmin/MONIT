package com.monit.core.designsystem

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.MonitListItem
import com.monit.core.designsystem.theme.MonitTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 공개 모달의 실제 너비와 클릭 행의 패딩·비활성 계약을 검사한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
class MonitReviewRegressionTest {
    @get:Rule
    val compose = createComposeRule()

    /** 짧은 콘텐츠도 304dp 본문과 좌우 16dp 여백을 사용하는 공개 모달로 표시한다. */
    @Test
    fun shortDialogKeepsTheSpecifiedContentWidth() {
        compose.setContent {
            MonitTheme {
                MonitDialog({}, Modifier.testTag("dialog"), title = { Text("제목") }) { Text("본문") }
            }
        }
        compose.onNodeWithTag("dialog").assertWidthIsEqualTo(336.dp)
    }

    /**
     * 목적: 클릭 처리를 내부로 이동해도 행의 패딩이 터치 영역에서 빠지지 않는다.
     * 조건: 전체 폭의 공통 행에 짧은 라벨과 소유자 콜백을 전달한다.
     * 절차: 좌측 패딩을 누른 뒤 비활성으로 전환해 같은 위치를 다시 누른다.
     * 기대: 클릭 대상은 하나이며 활성 상태의 첫 터치만 콜백으로 전달된다.
     */
    @Test
    fun listItemPaddingRemainsClickableAndDisabledActionStaysLocked() {
        var enabled by mutableStateOf(true)
        var calls = 0
        compose.setContent {
            MonitTheme {
                MonitListItem(
                    headline = { Text("항목") }, modifier = Modifier.testTag("item"),
                    enabled = enabled, onClick = { calls++ })
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        val item = compose.onNodeWithTag("item")
        item.performTouchInput { click(Offset(4f, center.y)) }
        compose.runOnIdle { assertEquals(1, calls); enabled = false }
        item.performTouchInput { click(Offset(4f, center.y)) }
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
