package com.monit.app.accounts

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monit.app.ui.MonitAppShell
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.DraftContent
import com.monit.core.model.DraftSnapshot
import com.monit.feature.accounts.detail.AccountDetailRoute
import com.monit.feature.accounts.detail.AccountDetailViewModel
import com.monit.feature.accounts.list.AccountsRoute
import com.monit.feature.accounts.list.AccountsViewModel
import java.io.File
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Nav2/Route/VM와 Native Canvas 검증이다. 실제 기기·키보드·프로세스 종료 검증은 아니다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountAssetsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val repo = AnchorTestEnvironment()
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var root: View
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
    private fun show(dark: Boolean = false, width: Int = 360, font: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                    MonitTheme(darkTheme = dark) {
                        val view = LocalView.current
                        val owner = LocalOnBackPressedDispatcherOwner.current

                        SideEffect {
                            root = view
                            back = requireNotNull(owner).onBackPressedDispatcher
                        }

                        Box(Modifier.fillMaxSize().testTag("assets-root")) {
                            MonitAppShell(
                                home = { Text("합성 홈") },
                                transactions = { Text("합성 거래") },
                                accounts = { actions ->
                                    val factory = remember { viewModelFactory { initializer { AccountsViewModel(repo.ledger, clock, contexts) } } }
                                    val model: AccountsViewModel = viewModel(factory = factory)

                                    AccountsRoute(
                                        context = repo.context,
                                        viewModel = model,
                                        onAccountSelected = actions.accountDetail,
                                    )
                                },
                                accountDetail = { entry, destination, navigator ->
                                    val factory = remember(entry) { viewModelFactory { initializer { AccountDetailViewModel(repo.ledger, repo.anchors, repo.drafts, createSavedStateHandle(), clock, contexts) } } }
                                    val model: AccountDetailViewModel = viewModel(factory = factory)

                                    AccountDetailRoute(
                                        context = repo.context,
                                        accountId = destination.accountId,
                                        entryId = destination.entryId,
                                        onClose = { navigator.closeAccountDetail(entry) },
                                        viewModel = model,
                                        onTransactions = { navigator.openAccountTransactions(entry) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        click("계좌")
        awaitText("확인된 잔액이 아직 없어요 · 잔액 미확인 3개 제외")
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasText("합성 계좌 10 · 일반"))
    }
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            val tag = if (compose.onAllNodesWithTag("account-detail-scroll").fetchSemanticsNodes().isNotEmpty()) "account-detail-scroll" else "accounts-list"
            compose.onNodeWithTag(tag).performScrollToNode(hasText(text))
        }
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun detail() {
        click("합성 계좌 10 · 일반")
        awaitText("기준액 미확인 · 현재 잔액 합계 제외")
    }
    private fun form(amount: String, reason: String = "합성 마감액 확인") {
        click("기준액 확인·변경")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("기준일 마감액 (원)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("기준일 마감액 (원)").performScrollTo().performTextReplacement(amount)
        compose.onNodeWithContentDescription("확인·변경 사유").performScrollTo().performTextReplacement(reason)
    }

    /** 미확인 상세는 기준일/제외사유를 표시하고 조회만으로 0원·거래를 만들지 않는다. */
    @Test fun unknownDetailIsReadOnlyAndRendersLight() {
        show()
        detail()
        compose.onNodeWithText("기준액 미확인 · 현재 잔액 합계 제외").assertExists()
        assertTrue(repo.commands.isEmpty())
        capture("account-unknown-light")
    }

    /** 실제 클릭 흐름에서 미리보기 전후 쓰기0, 최종확정만1회이며 결과 확인 뒤 이력이 표시된다. */
    @Test fun previewThenExplicitConfirmationUpdatesDetailAndHistoryOnce() {
        show()
        detail()
        form("0", reason = "")
        click("변경 영향 확인")
        awaitText("이 기준일 마감액을 확인하고 확정")
        assertTrue(repo.commands.isEmpty())
        capture("anchor-review-light")
        click("이 기준일 마감액을 확인하고 확정")
        awaitText("기준액 저장 완료")
        assertEquals(1, repo.commands.size)
        click("결과 확인 마치기")
        awaitText("기준액 확인·변경")
        assertEquals(1, repo.histories.size)
        compose.onAllNodesWithText("0원").onFirst().assertExists()
        assertTrue(repo.base.finished.isNotEmpty())
    }

    /** 320dp·글자200%·Dark에서 음수 검토/최종행동까지 스크롤로 접근하고 결과를 저장한다. */
    @Test fun darkLargeTextReviewRemainsScrollableAndDoesNotDefaultToZero() {
        show(dark = true, width = 320, font = 2f)
        detail()
        form("-300")
        click("변경 영향 확인")
        awaitText("이 기준일 마감액을 확인하고 확정")
        assertTrue(repo.commands.isEmpty())
        capture("anchor-review-dark-large")
        click("이 기준일 마감액을 확인하고 확정")
        awaitText("기준액 저장 완료")
        assertEquals(-300, repo.commands.single().second.amount)
    }

    /** Back은 입력을 자동 저장 확정하지 않고, 보관 선택 뒤 재진입에서 원문 초안을 명시 복구한다. */
    @Test fun backPreservesRawDraftAndRequiresExplicitRestore() {
        show()
        detail()
        form("-00.입력중")
        compose.runOnIdle { back.onBackPressed() }
        click("초안 보관하고 닫기")
        awaitText("합성 계좌 10 · 일반")
        val draft = repo.base.rows.values.filterIsInstance<DraftSnapshot.Editable>().single().draft.content as DraftContent.Anchor
        assertEquals("-00.입력중", draft.amount)
        detail()
        click("기준액 확인·변경")
        awaitText("이 초안 복구")
        click("이 초안 복구")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("기준일 마감액 (원)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("기준일 마감액 (원)").assertTextContains("-00.입력중")
        assertTrue(repo.commands.isEmpty())
    }

    /** 응답 유실은 실패로 단정하지 않으며 결과확인 버튼이 원래receipt를 찾아 중복쓰기를 막는다. */
    @Test fun lostReplyKeepsResultUnknownUntilExplicitLookup() {
        repo.loseReply = true
        show()
        detail()
        form("200")
        click("변경 영향 확인")
        awaitText("이 기준일 마감액을 확인하고 확정")
        click("이 기준일 마감액을 확인하고 확정")
        awaitText("저장 결과 확인 필요")
        click("원래 결과 확인·같은 요청 재시도")
        awaitText("기준액 저장 완료")
        assertEquals(1, repo.commands.size)
    }

    /** 상세의 거래 행동은 기존 거래 탭 조회로만 이동하며 금융 쓰기를 자동 호출하지 않는다. */
    @Test fun detailOpensTransactionsWithoutWritingFinance() {
        show()
        detail()
        click("거래 기록")
        awaitText("합성 거래")
        assertTrue(repo.commands.isEmpty())
        assertTrue(repo.base.calls.isEmpty())
    }

    private fun capture(name: String) {
        compose.onNodeWithTag("account-detail-scroll").performScrollToIndex(0)
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("assets-root").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            root.draw(canvas)
        }
        val directory = File("build/reports/account-assets/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
