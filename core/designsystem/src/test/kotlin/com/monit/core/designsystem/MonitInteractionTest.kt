package com.monit.core.designsystem

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.monit.core.designsystem.component.calendar.MonitDateCell
import com.monit.core.designsystem.component.calendar.MonitDatePicker
import com.monit.core.designsystem.component.chip.MonitFilterChip
import com.monit.core.designsystem.component.MonitSegmentedControl
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.modifier.clickableWithCooldown
import com.monit.core.designsystem.modifier.clickableWithoutRipple
import com.monit.core.designsystem.modifier.ClickCooldown
import com.monit.core.designsystem.preview.AccountCardSample
import com.monit.core.designsystem.preview.AccountOptionSample
import com.monit.core.designsystem.preview.AccountSheetSample
import com.monit.core.designsystem.preview.StatusPanelSample
import com.monit.core.designsystem.theme.MonitTheme
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/** 실제 Compose 입력/semantics 시험. TalkBack·실기기 접근성 통과를 뜻하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [28], qualifiers = "w360dp-h2400dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitInteractionTest {
    @get:Rule val compose = createComposeRule()

    /**
     * 목적: 첫 클릭과 경계 시간 수락, 억제 클릭이 대기 시간을 연장하지 않음을 확인한다.
     * 조건: 독립 gate 두 개에 합성 monotonic 시각을 제공한다.
     * 절차: 0 → 599 → 600ms와 다른 인스턴스의 첫 0ms를 검사한다.
     * 기대: 첫/경계/다른 인스턴스는 수락, 간격 내부는 거부한다.
     */
    @Test fun cooldownUsesAcceptedClickAndIndependentInstances() {
        val first = ClickCooldown()
        assertTrue(first.accept(0, 600))
        assertFalse(first.accept(599, 600))
        assertTrue(first.accept(600, 600))
        assertTrue(ClickCooldown().accept(0, 600))
        assertTrue(first.accept(600, 0))
    }

    /**
     * 목적: 재구성이 cooldown을 초기화하지 않고 최신 콜백을 호출한다.
     * 조건: 두 modifier 인스턴스와 바뀔 콜백 값, 비활성 상태를 사용한다.
     * 절차: A 두 번/B 한 번 → 콜백 교체 → A 재클릭 → 가상 시스템 시각 전진 → A 클릭한다.
     * 기대: A 중복 억제/B 독립, 경과 후 최신 콜백 호출, disabled 클릭은 전달되지 않는다.
     */
    @Test fun cooldownKeepsGateAcrossRecompositionAndUsesLatestCallback() {
        var version by mutableStateOf(1)
        var enabled by mutableStateOf(true)
        val received = mutableListOf<String>()
        compose.setContent {
            val callbackVersion = version
            Column {
                Box(Modifier.size(48.dp).testTag("a").clickableWithCooldown(60_000, enabled,
                    onClickLabel = "실행", role = Role.Button) { received += "a$callbackVersion" })
                Box(Modifier.size(48.dp).testTag("b").clickableWithCooldown(60_000) { received += "b" })
            }
        }
        compose.onNodeWithTag("a").performClick().performClick()
        compose.onNodeWithTag("b").performClick()
        compose.runOnIdle { version = 2 }
        compose.onNodeWithTag("a").performClick()
        compose.runOnIdle { assertEquals(listOf("a1", "b"), received) }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        compose.onNodeWithTag("a").performClick()
        compose.runOnIdle { enabled = false }
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        compose.onNodeWithTag("a").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("a1", "b", "a2"), received) }
    }

    /** ripple만 제거해도 역할·클릭 label·키보드 focus/Enter·disabled 의미를 보존한다. */
    @Test fun noRippleRetainsKeyboardFocusAndAccessibilityAction() {
        var enabled by mutableStateOf(true)
        var calls = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            val current = LocalInputModeManager.current
            SideEffect { inputMode = current }
            Text("실행", Modifier.size(48.dp).testTag("target")
                .clickableWithoutRipple(enabled, "결과 확인", Role.Button) { calls++ })
        }
        compose.onNodeWithTag("target").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("클릭 안내 보존") { it.config[SemanticsActions.OnClick].label == "결과 확인" })
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("target").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("target").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(1, calls); enabled = false }
        compose.onNodeWithTag("target").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    /**
     * 목적: 선택과 명시적 적용을 구분하고 미확인 잔액을 보존한다.
     * 조건: 미확인 계좌 한 건, 초기 선택 없음인 시트 본문이다.
     * 절차: 비활성 적용 확인 → 카드 선택 → 적용 콜백 없음 확인 → 적용 클릭 순서다.
     * 기대: 선택 ID와 미확인 표시가 유지되고 적용 클릭에서만 해당 ID를 전달한다.
     */
    @Test fun accountSheetRequiresExplicitApplyAndPreservesUnknownBalance() {
        var selected by mutableStateOf<String?>(null)
        val applied = mutableListOf<String>()
        val account = AccountOptionSample("synthetic", "생활비", "일반", "", null)
        compose.setContent {
            MonitTheme { AccountSheetSample(listOf(account), selected, { selected = it },
                { applied += it }, {}, {}) }
        }
        compose.onNodeWithText("이 계좌 사용").assertIsNotEnabled()
        compose.onNodeWithText("생활비 · 일반").performClick()
        compose.onNodeWithText("잔액 미확인").assertIsSelected()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("이 계좌 사용").performClick()
        compose.runOnIdle { assertEquals(listOf("synthetic"), applied) }
    }

    /**
     * 목적: 조회 중·실패와 정상 미등록 상태를 구분한다.
     * 조건: 계좌 목록 없이 로딩 중인 시트다.
     * 절차: 미등록 안내 없음·닫기 잠금을 확인한 뒤 로딩을 조회 오류로 전환한다.
     * 기대: 두 상태 모두 미등록으로 단정하지 않으며 오류 문구를 표시한다.
     */
    @Test fun accountSheetDoesNotPresentEmptyWhileLoadingOrFailed() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        compose.setContent {
            MonitTheme { AccountSheetSample(emptyList(), null, {}, {}, {}, {}, loading = loading, errorMessage = error) }
        }
        compose.onNodeWithText("등록된 계좌가 없어요.").assertDoesNotExist()
        compose.onNodeWithText("닫기").assertIsNotEnabled()
        compose.runOnIdle { loading = false; error = "조회 실패" }
        compose.onNodeWithText("등록된 계좌가 없어요.").assertDoesNotExist()
        compose.onNodeWithText("조회 실패").assertExists()
    }

    /**
     * 목적: 표시 선택과 소유자의 선택 콜백이 일치한다.
     * 조건: 달력/목록 중 달력이 선택된 제어 컴포넌트다.
     * 절차: 달력 선택 의미 확인 후 목록을 클릭하고 재구성을 기다린다.
     * 기대: 목록의 selected 의미와 소유자의 선택 인덱스가 함께 변경된다.
     */
    @Test fun segmentedControlPublishesSelectionAndCallback() {
        var selected by mutableStateOf(0)
        compose.setContent { MonitTheme { MonitSegmentedControl(listOf("달력", "목록"), selected, { selected = it }) } }
        compose.onNodeWithText("달력").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("목록").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    /** 날짜 정책을 소유자가 주입하면 미래 입력은 잠그고 월 이동 요청은 별도로 전달한다. */
    @Test fun datePickerRespectsCallerPolicyAndMonthNavigation() {
        val today = LocalDate.of(2026, 11, 3)
        val dates = mutableListOf<LocalDate>()
        val months = mutableListOf<YearMonth>()
        compose.setContent { MonitTheme {
            MonitDatePicker(YearMonth.of(2026, 11), today, today, { months += it }, { dates += it },
                selectableDate = { !it.isAfter(today) })
        } }
        compose.onNodeWithContentDescription("2026년 11월 4일 수요일").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("2026년 11월 2일 월요일").performClick()
        compose.onNodeWithContentDescription("다음 달").performClick()
        compose.runOnIdle { assertEquals(listOf(today.minusDays(1)), dates); assertEquals(listOf(YearMonth.of(2026, 12)), months) }
    }

    /** 불명 결과 확인 중의 버튼은 잠그며 저장 성공 표시를 만들지 않는다. */
    @Test fun checkingPanelLocksActionWithoutClaimingSuccess() {
        var calls = 0
        compose.setContent { MonitTheme {
            StatusPanelSample("저장 결과 확인 중", "완료 여부를 확인하고 있어요.", "결과 확인 중…", { calls++ }, busy = true)
        } }
        compose.onNodeWithText("결과 확인 중…").assertIsNotEnabled().performClick()
        compose.onNodeWithText("기록을 저장했어요").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    /**
     * 목적: 제목 탐색과 독립 선택 칩의 역할·동작을 노출한다.
     * 조건: 제목과 미선택 필터 하나를 실제 Compose에 올린다.
     * 절차: Heading·Checkbox 역할을 확인하고 필터를 클릭한다.
     * 기대: 제목 탐색 의미가 있으며 필터 선택이 소유자 상태와 함께 바뀐다.
     */
    @Test fun topBarHeadingAndFilterChipRolesAreExposed() {
        var selected by mutableStateOf(false)
        compose.setContent { MonitTheme { Column {
            MonitTopBar("거래 화면")
            MonitFilterChip("생활비 필터", selected) { selected = !selected }
        } } }
        compose.onNodeWithText("거래 화면").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("생활비 필터")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick().assertIsOn()
    }

    /** 320dp·200%에서 미확인과 구분되는 긴 확정 잔액을 생략·잘림 없이 표시한다. */
    @Test fun longConfirmedAccountBalanceWrapsAtLargeFont() {
        val amount = "−999,999,999,999원"
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 780.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MonitTheme { AccountCardSample("합성 계좌", amount, "확인한 장부 잔액", false, {}) }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(amount), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText("잔액 미확인").assertDoesNotExist()
    }

    /** 선택된 비활성 날짜의 선택 의미와 클릭 차단을 확인한다. 대비는 같은 상태의 렌더로 대조한다. */
    @Test fun disabledSelectedDateRetainsSelectionAndBlocksClickInBothThemes() {
        var clicks = 0
        compose.setContent { Column {
            listOf(false, true).forEachIndexed { index, dark ->
                MonitTheme(dark) { MonitDateCell(LocalDate.of(2026, 11, 3), { clicks++ },
                    Modifier.testTag("date$index"), selected = true, enabled = false) }
            }
        } }
        repeat(2) { index ->
            compose.onNodeWithTag("date$index").assertIsSelected().assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(0, clicks) }
    }
}
