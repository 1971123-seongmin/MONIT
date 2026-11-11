package com.monit.app.query

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.monit.app.ui.MonitAppShell
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.accounts.list.*
import com.monit.feature.home.*
import com.monit.feature.transactions.*
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Compose UI를 Native Canvas로 남긴다. 합성 자료이며 기기·Prevue·프레임 성능 증거가 아니다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductQueryRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersHomeLight() = render("home", false)

    @Test
    fun rendersAccountsDark() = render("accounts", true)

    @Test
    fun rendersCalendarLight() = render("calendar", false)

    @Test
    fun rendersListDark() = render("list", true)

    @Test
    fun rendersNarrowCalendarWithLargeText() = render("calendar", false, 320, 480, 2f)

    private fun render(
        screen: String,
        dark: Boolean,
        width: Int = 360,
        height: Int = 708,
        scale: Float = 1f
    ) {
        lateinit var view: View
        val date = LocalDate.of(2028, 2, 29)
        val transactions = TransactionsState(
            TransactionsUiState(
                YearMonth.from(date), date, date, calendar = screen != "list",
                income = "30,000원", expense = "20,000원",
                dates = (1..29).map {
                    TransactionDateUiState(
                        date.withDayOfMonth(it),
                        if (it == 29) "＋3만\n−2만\n이체 1건" else "",
                        if (it == 29) "수입 30,000원, 지출 20,000원, 이체 1건" else ""
                    )
                },
                rows = listOf(
                    TransactionRowUiState(
                        "one",
                        "2월 29일 화요일",
                        "수입 · 합성 기록",
                        "+30,000원",
                        "합성 계좌",
                        true
                    ),
                    TransactionRowUiState(
                        "two",
                        "2월 29일 화요일",
                        "지출 · 합성 기록",
                        "−20,000원",
                        "합성 계좌",
                        false
                    ),
                ),
            ), TransactionsLoadState.READY
        )
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(
                    DpSize(
                        width.dp,
                        height.dp
                    )
                )
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    MonitTheme(dark) {
                        val current = LocalView.current
                        SideEffect { view = current }
                        Box(Modifier.fillMaxSize().testTag("query-render")) {
                            MonitAppShell(
                                home = {
                                    HomeScreen(
                                        HomeState.Ready(
                                            HomeUiState(
                                                "2028년 2월 전체",
                                                "0원",
                                                "0원"
                                            )
                                        ), {})
                                },
                                transactions = {
                                    TransactionsScreen(
                                        transactions,
                                        {},
                                        {},
                                        {},
                                        {},
                                        {},
                                        {},
                                        {})
                                },
                                accounts = {
                                    AccountsScreen(
                                        AccountsState.Ready(
                                            AccountsUiState(
                                                "확인된 잔액 합계 0원 · 잔액 미확인 1개 제외", listOf(
                                                    AccountRowUiState(
                                                        "unknown",
                                                        "합성 계좌 · 일반",
                                                        "잔액 미확인",
                                                        "현재 잔액 합계에서 제외"
                                                    ),
                                                    AccountRowUiState(
                                                        "zero",
                                                        "확인한 합성 계좌 · 저축",
                                                        "0원",
                                                        "장부 기준 잔액 · 은행 확인 금액이 아니에요"
                                                    ),
                                                )
                                            )
                                        ), {})
                                },
                            )
                        }
                    }
                }
            }
        }
        if (screen != "home") {
            compose.onNodeWithText(if (screen == "accounts") "계좌" else "거래", useUnmergedTree = true)
                .performClick()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val prefix = "${screen}-${if (dark) "dark" else "light"}-$width-${scale.toInt()}"
        capture(view, prefix + "-top")
        if (screen == "calendar") {
            val selected = compose.onNode(hasContentDescription("2028년 2월 29일", substring = true))
            // 날짜의 가장 가까운 scroll parent는 가로 그리드다. 세로 목록은 실제 제스처로 이동한다.
            repeat(12) {
                if (!selected.isDisplayed()) {
                    compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val selectedBounds = selected.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(
                selectedBounds.height >= 48 * scale,
                "선택 날짜 전체 터치 영역이 보여야 한다: $selectedBounds"
            )
            capture(view, prefix + "-date")
        }
    }

    private fun capture(view: View, name: String) {
        val bounds = compose.onNodeWithTag("query-render").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(
            bounds.width.toInt(),
            bounds.height.toInt(),
            Bitmap.Config.ARGB_8888
        )
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
        }
        val directory = File("build/reports/query/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream()
            .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
