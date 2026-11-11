package com.monit.core.designsystem

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.monit.core.designsystem.component.MonitBottomNavigation
import com.monit.core.designsystem.component.MonitBottomSheetContent
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonSize
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.button.MonitButtonColors
import com.monit.core.designsystem.component.button.MonitButtonLayout
import com.monit.core.designsystem.component.chip.MonitFilterChip
import com.monit.core.designsystem.component.chip.MonitChipSize
import com.monit.core.designsystem.component.chip.MonitChipType
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitDialogContent
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/** 화면이나 금융 모델 없이 호출자가 공통 API의 변형·콘텐츠·행동을 구성하는 시험. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h2400dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitCustomizationTest {
    @get:Rule
    val compose = createComposeRule()

    /** 스타일·밀도·앞뒤 장식이 다른 버튼도 하나의 클릭 대상과 최소 터치 높이, loading 잠금을 유지한다. */
    @Test
    fun buttonPresetsAndIconSlotsRetainOneActionAndLock() {
        var loading by mutableStateOf(false)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MonitTheme {
                Column {
                    for (type in MonitButtonType.entries) for (size in MonitButtonSize.entries) {
                        val tag = "$type-$size"
                        MonitButton(
                            "계속",
                            Modifier.testTag(tag),
                            type = type,
                            size = size,
                            loading = loading,
                            leadingIcon = { Text("+") },
                            trailingIcon = { Text("→") }) { clicked += tag }
                    }
                }
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        compose.onNodeWithText("+").assertDoesNotExist()
        for (type in MonitButtonType.entries) {
            val regular = compose.onNodeWithTag("$type-Regular")
            val compact = compose.onNodeWithTag("$type-Compact")
            regular.assertHeightIsAtLeast(48.dp)
            compact.assertHeightIsAtLeast(48.dp)
            assertTrue(compact.fetchSemanticsNode().boundsInRoot.width < regular.fetchSemanticsNode().boundsInRoot.width)
            compact.performClick()
        }
        compose.runOnIdle {
            assertEquals(
                listOf("Primary-Compact", "Secondary-Compact"),
                clicked
            ); loading = true
        }
        compose.onNodeWithTag("Secondary-Compact").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(2, clicked.size) }
    }

    /** 기본값 공급자·앱 테마 없이도 명시적으로 전달한 버튼 색상/배치와 콜백으로 동작한다. */
    @Test
    fun buttonAcceptsResolvedValuesWithoutMonitTheme() {
        val colors = MonitButtonColors(
            Color.Black, Color.White, Color.DarkGray, Color.White,
            Color.Gray, Color.White, Color.Blue
        )
        val layout = MonitButtonLayout(
            PaddingValues(12.dp), TextStyle(fontSize = 14.sp),
            48.dp, 8.dp, 16.dp, 4.dp, 2.dp, 2.dp
        )
        var calls = 0
        compose.setContent {
            MonitButton(
                "독립 버튼", colors = colors, layout = layout,
                loadingText = "처리", loadingDescription = "진행"
            ) { calls++ }
        }
        compose.onNodeWithText("독립 버튼").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    /**
     * 목적: 칩의 type/size 조합이 Checkbox 선택 의미와 소유자 상태를 보존한다.
     * 조건: 독립 상태를 가진 네 조합에 같은 활성 여부를 전달한다.
     * 절차: 두 스타일/밀도를 모두 선택한 뒤 전체 비활성으로 바꿔 다시 클릭한다.
     * 기대: 체크 상태·최소 터치가 유지되고 비활성 중 콜백이 발생하지 않는다.
     */
    @Test
    fun chipPresetsKeepCheckedStateAndDisabledActions() {
        var enabled by mutableStateOf(true)
        val selected = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
        var calls = 0
        compose.setContent {
            MonitTheme {
                Column {
                    for (type in MonitChipType.entries) for (size in MonitChipSize.entries) {
                        val tag = "$type-$size"
                        MonitFilterChip(
                            tag,
                            selected[tag] == true,
                            enabled = enabled,
                            type = type,
                            size = size
                        ) {
                            calls++
                            selected[tag] = selected[tag] != true
                        }
                    }
                }
            }
        }
        for (type in MonitChipType.entries) for (size in MonitChipSize.entries) {
            compose.onNodeWithText("$type-$size").assertIsOff().assertHeightIsAtLeast(48.dp)
                .performClick().assertIsOn()
        }
        compose.runOnIdle { assertEquals(4, calls); enabled = false }
        compose.onNodeWithText("Outlined-Compact").assertIsNotEnabled().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(4, calls) }
    }

    /** 320dp/200%에서도 앞뒤 아이콘이 긴 버튼 라벨의 공간을 침범하지 않고 네 조합 모두 줄바꿈한다. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun largeButtonLabelsWrapBetweenIconSlots() {
        val label = "설정을 확인하고 계속 진행하기"
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(
                    DpSize(
                        320.dp,
                        2000.dp
                    )
                )
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MonitTheme {
                        Column {
                            for (type in MonitButtonType.entries) for (size in MonitButtonSize.entries) {
                                MonitButton(
                                    label,
                                    type = type,
                                    size = size,
                                    leadingIcon = { Text("+") },
                                    trailingIcon = { Text("→") }) {}
                            }
                        }
                    }
                }
            }
        }
        val labels = compose.onAllNodes(hasText(label), useUnmergedTree = true)
        labels.assertCountEquals(4)
        for (index in 0..3) {
            val layouts = mutableListOf<TextLayoutResult>()
            labels[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue(
                layout.lineCount > 1,
                "버튼 $index: size=${layout.size}, font=${layout.layoutInput.style.fontSize}"
            )
            assertFalse(
                layout.hasVisualOverflow,
                "버튼 $index: size=${layout.size}, constraints=${layout.layoutInput.constraints}"
            )
        }
    }

    /**
     * 목적: 일반 입력에서 호출자의 검색 IME·편집·아이콘 행동·readOnly를 보존한다.
     * 조건: Text/Search 키보드와 별도 지우기 슬롯을 제공한다.
     * 절차: 편집→IME→지우기→readOnly 전환 순서로 호출한다.
     * 기대: 값 변환 없이 최신 검색어가 전달되며 readOnly에서는 편집과 호출자가 잠근 지우기가 비활성이다.
     */
    @Test
    fun textFieldSupportsCallerKeyboardAndIndependentTrailingAction() {
        var value by mutableStateOf("abc")
        var readOnly by mutableStateOf(false)
        var submitted = ""
        compose.setContent {
            MonitTheme {
                MonitTextField(
                    value, { value = it }, label = "검색어", readOnly = readOnly,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { submitted = value }),
                    leadingIcon = { Text("⌕") }, trailingIcon = {
                        IconButton(
                            { value = "" },
                            enabled = !readOnly,
                            modifier = Modifier.semantics {
                                contentDescription = "지우기"
                            }) { Text("×") }
                    })
            }
        }
        val editable = compose.onNode(hasSetTextAction())
        editable.performTextReplacement("Monit 검색")
        editable.performImeAction()
        compose.runOnIdle { assertEquals("Monit 검색", submitted) }
        compose.onNodeWithContentDescription("지우기").performClick()
        compose.runOnIdle { assertEquals("", value); readOnly = true }
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true
        )
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        compose.onNodeWithContentDescription("지우기").assertIsNotEnabled()
    }

    /** 카드·상태·모달·시트의 슬롯은 임의 콘텐츠와 호출자가 제공한 행동만 표시하며 선택/적용을 추정하지 않는다. */
    @Test
    fun containersRenderCallerContentAndOnlySuppliedActions() {
        var dialogs = 0
        var sheets = 0
        compose.setContent {
            MonitTheme {
                Column {
                    MonitCard { Text("장치 정보") }
                    MonitStatusPanel("연결 대기", "장치를 선택하세요")
                    MonitDialogContent(title = { Text("연결 설정") }, actions = {
                        MonitButton("연결", onClick = { dialogs++ })
                    }) { Text("사용자가 선택한 연결 방식") }
                    MonitBottomSheetContent(title = { Text("정렬 기준") }, actions = {
                        MonitButton("이름순", onClick = { sheets++ })
                    }) { Text("정렬 옵션 본문") }
                }
            }
        }
        compose.onNodeWithText("장치 정보").assertExists()
        compose.onNodeWithText("정렬 옵션 본문").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        compose.onNodeWithText("연결").performClick()
        compose.onNodeWithText("이름순").performClick()
        compose.runOnIdle { assertEquals(1, dialogs); assertEquals(1, sheets) }
    }

    /** 두 개·다섯 개 목적지와 아이콘 슬롯을 허용하며 선택과 비활성 동작을 소유자에게 전달한다. */
    @Test
    fun navigationAcceptsCallerDestinationsAndLocksActions() {
        var count by mutableStateOf(2)
        var selected by mutableStateOf(0)
        var enabled by mutableStateOf(true)
        compose.setContent {
            MonitTheme {
                MonitBottomNavigation(
                    (1..count).map { "목적지 $it" },
                    selected,
                    { selected = it },
                    enabled = enabled,
                    icon = { Text("◇") })
            }
        }
        val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tabs).assertCountEquals(2)
        compose.runOnIdle { count = 5 }
        compose.onAllNodes(tabs).assertCountEquals(5)
        compose.onNodeWithText("목적지 5").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(4, selected); enabled = false }
        compose.onNodeWithText("목적지 1").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(4, selected) }
    }
}
