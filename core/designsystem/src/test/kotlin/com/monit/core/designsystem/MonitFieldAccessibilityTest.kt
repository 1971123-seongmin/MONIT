package com.monit.core.designsystem

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/** 라벨·진행 안내 semantics와 Android 편집 노드의 오류·활성 정보를 구분해 확인한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MonitFieldAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * 목적: 편집 필드의 라벨 한 곳에 진행 상태를 포함하고 별도 상태 설명을 중복 설정하지 않는다.
     * 조건: 공통 입력에 호출자가 정한 라벨·진행 설명·오류를 전달한다.
     * 절차: loading → 일반 비활성 → 활성으로 전환해 semantics와 Android 노드를 조회한다.
     * 기대: 진행 라벨은 semantics 한 곳에만 있고, Android 노드의 오류·값·비활성 정보는 보존된다.
     */
    @Test
    fun fieldDescriptionIncludesProgressAndKeepsAndroidEditingMetadata() {
        var loading by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            MonitTheme {
                MonitTextField(
                    "30000", {}, Modifier.testTag("container"), label = "합성 금액", loading = loading,
                    enabled = enabled, errorMessage = "합성 오류", loadingDescription = "저장 중"
                )
            }
        }
        val field = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true
        )

        fun androidNode() = field.fetchSemanticsNode().id.let { id ->
            compose.runOnIdle {
                assertNotNull(
                    view.accessibilityNodeProvider.createAccessibilityNodeInfo(
                        id
                    )
                )
            }
        }

        field.assertContentDescriptionEquals("합성 금액, 저장 중")
        compose.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("합성 금액, 저장 중")
            ), useUnmergedTree = true
        ).assertCountEquals(1)
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("container", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        androidNode().let {
            assertEquals("합성 오류", it.error?.toString())
            assertEquals("30000", it.text?.toString())
            assertFalse(it.isEnabled)
        }
        compose.runOnIdle { loading = false; enabled = false }
        field.assertContentDescriptionEquals("합성 금액")
        assertFalse(androidNode().isEnabled)
        compose.runOnIdle { enabled = true }
        field.assertContentDescriptionEquals("합성 금액")
        androidNode().let {
            assertEquals("합성 오류", it.error?.toString())
            assertEquals(true, it.isEnabled)
        }
    }
}
