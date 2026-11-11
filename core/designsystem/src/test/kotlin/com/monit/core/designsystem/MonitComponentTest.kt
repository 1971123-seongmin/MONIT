package com.monit.core.designsystem

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.calendar.MonitDateCell
import com.monit.core.designsystem.preview.AccountOptionSample
import com.monit.core.designsystem.preview.AccountPickerSample
import com.monit.core.designsystem.preview.AmountFieldSample
import com.monit.core.designsystem.preview.TransactionRowSample
import com.monit.core.designsystem.theme.MonitColors
import com.monit.core.designsystem.theme.MonitTheme
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/** 실제 Compose semantics/레이아웃을 실행하는 호스트 시험이다. TalkBack·실기기 시험은 아니다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h2400dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitComponentTest {
    @get:Rule
    val compose = createComposeRule()

    /** 기기 테마 변경을 추종하고 두 테마에서 일반 글자와 컨트롤의 대비 목표를 지킨다. */
    @Test
    fun followsSystemThemeAndKeepsReadableSemanticPairs() {
        var dark by mutableStateOf(false)
        lateinit var colors: MonitColors
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(dark)) {
                MonitTheme {
                    val current = MonitTheme.colors
                    val scheme = MaterialTheme.colorScheme
                    val typography = MaterialTheme.typography
                    SideEffect {
                        colors = current
                        assertEquals(current.primary, scheme.primary)
                        assertEquals(current.surfaceContainer, scheme.surfaceContainer)
                        assertEquals(24f, typography.titleLarge.fontSize.value)
                        assertEquals(36f, typography.titleLarge.lineHeight.value)
                        assertEquals(14f, typography.bodyMedium.fontSize.value)
                        assertEquals(24f, typography.bodyMedium.lineHeight.value)
                        assertEquals(14f, typography.labelLarge.fontSize.value)
                        assertEquals(22f, typography.labelLarge.lineHeight.value)
                        assertEquals(16f, typography.bodyLarge.fontSize.value)
                        assertEquals(12f, typography.labelSmall.fontSize.value)
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.waitForIdle()
            assertEquals(if (night) Color(0xFF93C5FD) else Color(0xFF2563EB), colors.primary)
            val textPairs = listOf(
                colors.onPrimary to colors.primary, colors.onPrimary to colors.pressed,
                colors.onSurface to colors.surface, colors.onSurface to colors.surfaceContainer,
                colors.muted to colors.surface, colors.muted to colors.surfaceContainer,
                colors.error to colors.surfaceContainer,
            )
            for ((text, background) in textPairs) assertTrue(contrast(text, background) >= 4.5)
            assertTrue(contrast(colors.outline, colors.surfaceContainer) >= 3.0)
            assertTrue(contrast(colors.primary, colors.surface) >= 3.0)
        }
    }

    /**
     * 목적: 저장 중 중복 입력과 비활성 동작을 차단한다. 조건: 클릭 직후 호출자가 loading 상태로 전환.
     * 절차: 첫 클릭·loading 상태 클릭·명시적 disabled 클릭 후, 완료 상태로 복귀해 다시 클릭한다.
     * 기대: 처리 중 버튼은 disabled/저장 중 semantics를 가지며 loading 동안 콜백 횟수가 증가하지 않는다.
     */
    @Test
    fun loadingAndDisabledButtonsPreventAnotherAction() {
        var loading by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MonitTheme {
                Column {
                    MonitButton(
                        "저장",
                        Modifier.testTag("save"),
                        loading = loading,
                        loadingDescription = "저장 중"
                    ) { clicks++; loading = true }
                    MonitButton("잠김", enabled = false) { clicks++ }
                }
            }
        }
        compose.onNodeWithTag("save").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("save").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "저장 중"))
            .performClick()
        compose.onNodeWithText("잠김").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks); loading = false }
        compose.onNodeWithTag("save").performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    /** 빈칸/단독 부호/소수/범위 초과 문자열을 임의 변환하지 않으며 오류와 단위를 같은 필드에 연결한다. */
    @Test
    fun amountFieldPreservesEditingTextAndExposesErrorWithoutLiveTypingAnnouncements() {
        var value by mutableStateOf("123")
        var submitted: String? = null
        var error by mutableStateOf<String?>(null)
        var loading by mutableStateOf(false)
        compose.setContent {
            MonitTheme {
                AmountFieldSample(
                    value,
                    { value = it },
                    Modifier.testTag("amount"),
                    errorMessage = error,
                    loading = loading,
                    keyboardActions = KeyboardActions(onDone = { submitted = value })
                )
            }
        }
        val field = compose.onNode(hasSetTextAction())
        for (input in listOf("", "-", "1.2", "9999999999999999999999999999")) {
            field.performTextReplacement(input)
            compose.runOnIdle { assertEquals(input, value) }
            field.performImeAction()
            compose.runOnIdle { assertEquals(input, submitted) }
        }
        compose.runOnIdle { error = "정수 원 단위로 입력하세요." }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "정수 원 단위로 입력하세요."))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        field.assertContentDescriptionEquals("거래 금액 (원)")
        compose.runOnIdle { loading = true }
        compose.onNodeWithTag("amount").assertIsNotEnabled()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true
        )
            .assertContentDescriptionEquals("거래 금액 (원), 저장 중")
    }

    /** 같은 이름을 ID로 구별하고 라디오+행이 각각 하나의 선택 대상이며 미확인/확인 0원을 구별한다. */
    @Test
    fun accountSelectionReturnsIdentityAndDistinguishesUnknownFromZero() {
        val options = listOf(
            AccountOptionSample("first", "동명 계좌", "일반", "기준일 2026.11.02", null),
            AccountOptionSample("second", "동명 계좌", "저축", "기준일 2026.10.31", "0원"),
        )
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            MonitTheme { AccountPickerSample(options, selected, { selected = it }, {}) }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onAllNodes(radio).assertCountEquals(2)
        compose.onNode(radio and hasText("동명 계좌 · 저축")).performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("second", selected) }
        compose.onNode(radio and hasText("잔액 미확인", substring = true))
            .assertTextContains("현재 잔액 합계에서 제외", substring = true)
        compose.onNode(radio and hasText("0원")).assertIsSelected()
            .assertTextContains("기준일 2026.10.31")
    }

    /** 미등록/실패의 콜백은 호출자에게만 전달하며 저장 중에는 추가·재시도 모두 잠근다. */
    @Test
    fun emptyAndFailureActionsRemainCallerOwnedAndLockWhileSaving() {
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>("목록을 불러오지 못했어요.")
        var additions = 0
        var retries = 0
        compose.setContent {
            MonitTheme {
                AccountPickerSample(
                    emptyList(), null, {}, { additions++ }, loading = loading,
                    errorMessage = error, onRetry = { retries++ })
            }
        }
        compose.onNodeWithText("등록된 계좌가 없어요.").assertDoesNotExist()
        compose.onNodeWithText("다시 시도").performClick()
        compose.runOnIdle { error = null }
        compose.onNodeWithText("등록된 계좌가 없어요.").assertExists()
        compose.onNodeWithText("계좌 추가").performClick()
        compose.runOnIdle { loading = true }
        compose.onNodeWithText("계좌 추가").assertDoesNotExist()
        compose.onNodeWithText("등록된 계좌가 없어요.").assertDoesNotExist()
        compose.runOnIdle { error = "목록을 불러오지 못했어요." }
        compose.onNodeWithText("다시 시도").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, additions) }
    }

    /** 연도·요일·오늘·선택·정확한 요약을 전달하고 빈칸은 선택 대상에서 제외, 미래 조회는 허용한다. */
    @Test
    fun dateCellsExposeSingleTargetAndLeaveBlankCellsInert() {
        var futureClicks = 0
        compose.setContent {
            MonitTheme {
                Column {
                    MonitDateCell(
                        LocalDate.of(2026, 11, 3), {}, Modifier.testTag("today"), selected = true,
                        today = true, summary = "수입 3만", accessibilitySummary = "수입 30,000원"
                    )
                    MonitDateCell(null, {}, Modifier.testTag("blank"))
                    MonitDateCell(
                        LocalDate.of(2027, 1, 1),
                        { futureClicks++ },
                        Modifier.testTag("future")
                    )
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithTag("today").assertIsSelected().assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .assertContentDescriptionEquals("2026년 11월 3일 화요일, 오늘, 수입 30,000원")
        compose.onNodeWithTag("future").performClick()
        compose.runOnIdle { assertEquals(1, futureClicks) }
    }

    /** 320dp/200%에서 긴 버튼·계좌·정확한 금액을 줄바꿈하며 글자와 행을 잘라내지 않는다. */
    @Test
    fun narrowLargeTextExpandsInsteadOfEllipsizing() {
        val button = "확인하고 선택한 계좌를 사용합니다"
        val headline = "지출 · 긴 합성 거래 제목을 모두 읽을 수 있습니다"
        val account = "아주 긴 합성 계좌 이름을 다음 줄에서도 정확하게 확인합니다"
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(
                    DpSize(
                        320.dp,
                        1600.dp
                    )
                )
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MonitTheme {
                        Column(Modifier.fillMaxWidth()) {
                            MonitButton(button, Modifier.fillMaxWidth().testTag("longButton")) {}
                            TransactionRowSample(
                                "지출",
                                "긴 합성 거래 제목을 모두 읽을 수 있습니다",
                                "-999,999,999,999원",
                                account
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("longButton").assertHeightIsAtLeast(68.dp)
        for (text in listOf(button, headline, "-999,999,999,999원", account)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse(
                layout.hasVisualOverflow,
                "$text / size=${layout.size}, constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}"
            )
        }
    }

    /** 작은 글자 설정도 최소 48dp 터치 영역을 줄이지 않는다. */
    @Test
    fun smallerFontNeverShrinksDateTouchTarget() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(0.8f)) {
                MonitTheme {
                    MonitDateCell(
                        LocalDate.of(2026, 11, 3),
                        {},
                        Modifier.testTag("small")
                    )
                }
            }
        }
        compose.onNodeWithTag("small").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    /** 실제 키보드 포커스와 Enter 동작을 연결하고 콜백을 한 번 전달한다. */
    @Test
    fun keyboardFocusCanActivateButtonOnce() {
        var clicks = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            val current = LocalInputModeManager.current
            SideEffect { inputMode = current }
            MonitTheme { MonitButton("계속", Modifier.testTag("keyboard")) { clicks++ } }
        }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithTag("keyboard")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("keyboard").assertIsFocused().performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun contrast(a: Color, b: Color): Double =
        (max(a.luminance(), b.luminance()) + 0.05) / (min(a.luminance(), b.luminance()) + 0.05)
}
