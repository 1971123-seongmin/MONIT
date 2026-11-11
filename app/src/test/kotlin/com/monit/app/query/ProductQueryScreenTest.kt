package com.monit.app.query

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.monit.app.ui.MonitAppShell
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.accounts.list.AccountRowUiState
import com.monit.feature.accounts.list.AccountsScreen
import com.monit.feature.accounts.list.AccountsState
import com.monit.feature.accounts.list.AccountsUiState
import com.monit.feature.home.HomeScreen
import com.monit.feature.home.HomeState
import com.monit.feature.transactions.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProductQueryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** 같은 값의 container 역할이 있더라도 화면 본문은 강조색 대신 onSurface를 상속한다. */
    @Test
    fun shellProvidesNeutralContentColor() {
        var expected = androidx.compose.ui.graphics.Color.Unspecified
        var observed = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            MonitTheme {
                val neutral = MonitTheme.colors.onSurface
                androidx.compose.runtime.SideEffect { expected = neutral }
                MonitAppShell(
                    home = {
                        val color = androidx.compose.material3.LocalContentColor.current
                        androidx.compose.runtime.SideEffect { observed = color }
                        Text("본문")
                    },
                    transactions = {},
                    accounts = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(expected, observed)
    }

    /** 실제 셸의 3탭을 왕복해도 feature의 saveable 표시 상태가 보존된다. */
    @Test
    fun appShellSwitchesRealRootsAndRestoresFeatureState() {
        compose.setContent {
            MonitTheme {
                MonitAppShell(
                    home = { Text("홈 본문") },
                    transactions = {
                        var month by rememberSaveable { mutableStateOf("2026-11") }
                        androidx.compose.material3.TextButton(onClick = {
                            month = "2026-08"
                        }) { Text("선택 월 " + month) }
                    },
                    accounts = { Text("계좌 본문") },
                )
            }
        }
        compose.onNodeWithText("홈 본문").assertIsDisplayed()
        compose.onNodeWithText("거래", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("선택 월 2026-11").performClick()
        compose.onNodeWithText("계좌", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("계좌 본문").assertIsDisplayed()
        compose.onNodeWithText("거래", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("선택 월 2026-08").assertIsDisplayed()
    }

    /** 실패·진행·정상 빈 상태를 구별하고 재시도는 전달된 조회 콜백 한 번만 호출한다. */
    @Test
    fun transactionsNeverRenderFailureAsEmptySuccess() {
        var load by mutableStateOf(TransactionsLoadState.FAILED)
        var retries = 0
        compose.setContent {
            MonitTheme { Screen(TransactionsState(ui(), load), onRetry = { retries++ }) }
        }
        compose.onNodeWithText("조회하지 못했어요").assertExists()
        compose.onNodeWithText("거래가 없어요").assertDoesNotExist()
        compose.onNodeWithText("다시 불러오기").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.runOnIdle { load = TransactionsLoadState.LOADING }
        compose.onNodeWithText("불러오는 중").assertExists()
        compose.onNodeWithText("거래가 없어요").assertDoesNotExist()
        compose.runOnIdle { load = TransactionsLoadState.READY }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("거래가 없어요"))
        compose.onNodeWithText("거래가 없어요").assertIsDisplayed()
    }

    /** 달력의 일요일 시작·윤년 마지막 날과 비어 있는 자리의 비상호작용을 실제 semantics로 확인한다. */
    @Test
    fun leapCalendarExposesOnlyRealDatesAndSelectionCallback() {
        var selected: LocalDate? = null
        compose.setContent {
            MonitTheme {
                Screen(
                    TransactionsState(ui(), TransactionsLoadState.READY),
                    onDate = { selected = it })
            }
        }
        val lastDay = compose.onNode(hasContentDescription("2028년 2월 29일", substring = true))
        repeat(12) {
            if (!lastDay.isDisplayed()) compose.onNode(hasScrollToIndexAction())
                .performTouchInput { swipeUp() }
        }
        lastDay.assertIsDisplayed().performClick()
        assertEquals(LocalDate.of(2028, 2, 29), selected)
        val firstDay = compose.onNode(hasContentDescription("2028년 2월 1일", substring = true))
            .fetchSemanticsNode().boundsInRoot
        val tuesday = compose.onNodeWithText("화").fetchSemanticsNode().boundsInRoot
        assertEquals(tuesday.center.x, firstDay.center.x, 1f)
        compose.onNode(hasContentDescription("2028년 2월 30일", substring = true)).assertDoesNotExist()
    }

    /** 계좌 화면은 미확인과 0원을 각각 표시하며 존재하지 않는 추가/기준액 확인 행동을 만들지 않는다. */
    @Test
    fun accountRowsKeepUnknownDistinctFromConfirmedZero() {
        compose.setContent {
            MonitTheme {
                AccountsScreen(
                    AccountsState.Ready(
                        AccountsUiState(
                            "확인된 잔액 합계 0원", listOf(
                                AccountRowUiState("one", "합성 계좌 A", "잔액 미확인", "현재 잔액 합계에서 제외"),
                                AccountRowUiState("two", "합성 계좌 B", "0원", "장부 기준 잔액"),
                            )
                        )
                    ),
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("잔액 미확인", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0원", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("계좌 추가").assertDoesNotExist()
    }

    /** 홈 조회 실패에서는 임의 금액·납입 완료를 표시하지 않는다. */
    @Test
    fun homeReadFailureDoesNotInventFinancialSuccess() {
        compose.setContent { MonitTheme { HomeScreen(HomeState.Failed, onRetry = {}) } }
        compose.onNodeWithText("조회하지 못했어요").assertExists()
        compose.onNodeWithText("수입 0원").assertDoesNotExist()
        compose.onNodeWithText("납입 여부 확인").assertDoesNotExist()
    }

    /** 좁고 낮은 창/200% 글꼴에서도 마지막 계좌와 정확한 잔액까지 실제 목록을 스크롤한다. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun largeTextAndShortWindowKeepLastAccountReachable() {
        val title = "마지막 합성 계좌 · 긴 이름도 읽을 수 있어요"
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(
                    androidx.compose.ui.unit.DpSize(320.dp, 480.dp)
                )
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    MonitTheme {
                        AccountsScreen(
                            AccountsState.Ready(
                                AccountsUiState(
                                    "확인된 잔액 합계 0원",
                                    (1..20).map {
                                        AccountRowUiState(
                                            it.toString(),
                                            if (it == 20) title else "합성 계좌 $it", "0원", "장부 기준 잔액"
                                        )
                                    })
                            ),
                            onRetry = {},
                        )
                    }
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Screen(
        state: TransactionsState,
        onRetry: () -> Unit = {},
        onDate: (LocalDate) -> Unit = {}
    ) {
        Box(Modifier.size(360.dp, 780.dp)) {
            TransactionsScreen(state, onRetry, {}, onDate, {}, {}, {}, {})
        }
    }

    private fun ui(): TransactionsUiState {
        val date = LocalDate.of(2028, 2, 29)
        return TransactionsUiState(
            YearMonth.from(date), date, date, income = "0원", expense = "0원",
            dates = (1..29).map { TransactionDateUiState(date.withDayOfMonth(it), "", "") },
        )
    }
}
