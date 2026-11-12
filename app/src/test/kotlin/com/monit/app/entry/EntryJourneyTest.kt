package com.monit.app.entry

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
import com.monit.app.query.QueryRepository
import com.monit.app.query.testContext
import com.monit.app.ui.MonitAppShell
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*
import com.monit.feature.accounts.AccountRegistrationRoute
import com.monit.feature.accounts.AccountRegistrationViewModel
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.AccountRegistrationOrigin
import com.monit.feature.transactions.entry.TransactionEntryRoute
import com.monit.feature.transactions.entry.TransactionEntryViewModel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Route·VM·Nav2를 연결한 합성 Repository UI 시험. Room·기기 쓰기 시험과 구분한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class EntryJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val repo = JourneyRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
    private lateinit var rootView: View

    /** 복수 초안의 안정 key Lazy 목록은 320dp/큰 글꼴에서도 새 입력 버튼까지 스크롤된다. */
    @Test fun narrowLargeFontRecoveryListReachesExplicitNewForm() {
        runBlocking {
            repeat(12) { index ->
                repo.drafts.saveDraft(
                    testContext,
                    UUID.randomUUID().toString(),
                    0,
                    DraftScope(DraftKind.TRANSACTION, DraftOrigin.HOME),
                    DraftContent.Transaction(TransactionKind.EXPENSE, "${index + 1}000", "2026-11-02", null, null, "합성 초안"),
                )
            }
        }
        launch(dark = true, width = 320, font = 2f)
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("미제출 초안 확인").assertExists()
        captureDraft("chooser-dark-320-font2")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("새 입력 시작"))
        compose.onNodeWithText("새 입력 시작").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").assertExists()
        assertEquals(12, runBlocking { repo.drafts.drafts(testContext).size })
        assertTrue(repo.transactions.isEmpty())
    }

    /**
     * 목적: Nav2 폼을 닫은 뒤 새 entry에서는 원문을 자동 적용하지 않는다.
     * 조건: 잘못된 금액/날짜·메모를 보관하고 원래 홈으로 복귀한다.
     * 절차: 새 거래 → 원문 입력 → 보관 이탈 → 새 거래 → 명시 복구.
     * 기대: chooser 전에는 폼이 없고 복구 후 원문이 같으며 금융 쓰기는 없다.
     */
    @Test fun retainedDraftRecoversExplicitlyAcrossNewNavigationEntry() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("00.원문")
        compose.onNodeWithContentDescription("거래일").performTextReplacement("2026-02-30")
        compose.onNodeWithContentDescription("메모 (선택)").performTextInput("  복구 메모  ")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("보관하고 나가기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("미제출 초안 확인").assertExists()
        compose.onNodeWithContentDescription("금액 (원)").assertDoesNotExist()
        captureDraft("chooser-light-360-font1")
        compose.onNodeWithText("이 초안 복구").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("00.원문")
        compose.onNodeWithContentDescription("거래일").assertTextContains("2026-02-30")
        compose.onNodeWithContentDescription("메모 (선택)").assertTextContains("  복구 메모  ")
        assertTrue(repo.transactions.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /** 초안 폐기는 확인 대화상자의 명시 선택으로만 실행하고 금융 기록을 만들지 않는다. */
    @Test fun discardChoiceRequiresConfirmationAndDoesNotWriteFinance() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("4000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("보관하고 나가기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("이 초안 폐기").performScrollTo().performClick()
        compose.onNodeWithText("이 미제출 초안을 폐기할까요?").assertExists()
        compose.onNodeWithText("보관하기").performClick()
        compose.onNodeWithText("이 초안 복구").assertExists()
        compose.onNodeWithText("이 초안 폐기").performScrollTo().performClick()
        compose.onNodeWithText("초안 폐기").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("")
        assertTrue(repo.transactions.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /** 저장 실패 뒤 보관 이탈은 유지되고, 사용자가 최신 입력 유실을 확인한 이탈만 허용한다. */
    @Test fun failedRetentionOffersExplicitLeaveWithoutLatestSave() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        repo.drafts.failSave = true
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("6000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("보관하고 나가기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("거래 입력에서 나갈까요?").assertExists()
        compose.onNodeWithText("저장 없이 나가기").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("원래 홈").assertExists()
        assertTrue(repo.transactions.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /**
     * 목적: 입력 중 계좌 등록 왕복과 명시 적용, 저장 완료 복귀를 실제 탐색에서 검증한다.
     * 조건: 실제 셸·입력/등록 Route·VM과 합성 장부 대역이다.
     * 절차: 금액/메모 입력 → 계좌 등록 → 등록 확인 → 후보 직접 적용 → 저장 → 목록 복귀.
     * 기대: 원래 입력 보존, 자동 적용 없음, 계좌·거래 각 한 번 저장, 명시 확인된 참조만 종료한다.
     */
    @Test fun registerAccountThenExplicitlyApplyAndSaveTransaction() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("2000")
        compose.onNodeWithContentDescription("메모 (선택)").performTextInput("합성 점심")
        compose.onNodeWithText("계좌 선택").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("새 계좌 추가").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("계좌 이름").performTextInput("합성 생활비")
        compose.onNodeWithText("계좌 저장").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("등록 확인").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repo.transactions.isEmpty())
        compose.onNodeWithText("이 계좌 사용").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("2000")
        compose.onNodeWithContentDescription("메모 (선택)").assertTextContains("합성 점심")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("기록을 저장했어요").assertExists()
        compose.onNodeWithText("목록 보기").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("원래 홈").assertExists()
        assertEquals(1, repo.accounts.size)
        assertEquals(1, repo.transactions.size)
        assertEquals(2000L, repo.transactions.single().amount)
        assertEquals("합성 점심", repo.transactions.single().memo)
        assertEquals(repo.accounts.single().id, repo.transactions.single().debitAccountId)
        assertEquals(2, repo.finished.size)
    }

    /** 실제 시스템 Back은 확인 대화상자를 거치며 나가기를 저장 취소나 금융 쓰기로 취급하지 않는다. */
    @Test fun systemBackRequiresExplicitExitWithoutWriting() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("1000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("거래 입력에서 나갈까요?").assertExists()
        compose.onNodeWithText("계속 입력하기").performClick()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("1000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("보관하고 나가기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("원래 홈").assertExists()
        assertTrue(repo.accounts.isEmpty())
        assertTrue(repo.transactions.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /** 첫 오류 입력으로 초점을 돌리고 오류 값·다른 필드를 지우지 않는다. */
    @Test fun invalidAmountFocusesFieldAndPreservesMemo() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("0")
        compose.onNodeWithContentDescription("메모 (선택)").performTextInput("보존")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithContentDescription("금액 (원)").assertIsFocused().assertTextContains("0")
        compose.onNodeWithContentDescription("메모 (선택)").assertTextContains("보존")
        assertTrue(repo.transactions.isEmpty())
    }

    private fun launch(dark: Boolean = false, width: Int = 360, font: Float = 1f) {
        compose.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val view = LocalView.current
            SideEffect { rootView = view }
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                    MonitTheme(dark) {
                        Box(Modifier.fillMaxSize().testTag("draft-root")) {
                            MonitAppShell(
                                home = { actions ->
                                    Text("원래 홈")
                                    MonitButton("새 거래", onClick = { actions.newTransaction(null) })
                                },
                                transactions = { Text("원래 거래") },
                                accounts = { Text("원래 계좌") },
                                transactionEntry = { entry, destination, navigator ->
                                    val factory = remember { viewModelFactory { initializer { TransactionEntryViewModel(repo, clock, createSavedStateHandle(), repo.drafts) } } }
                                    val vm: TransactionEntryViewModel = viewModel(factory = factory)
                                    TransactionEntryRoute(
                                        testContext,
                                        destination,
                                        entry,
                                        onRegisterAccount = { request, parent, slot ->
                                            navigator.openAccountRegistration(
                                                entry,
                                                AccountRegistrationDestination(UUID.randomUUID().toString(), AccountRegistrationOrigin.TRANSACTION_ENTRY, destination.entryId, request, parent, slot.name),
                                            )
                                        },
                                        onClose = { navigator.closeConfirmedTransactionEntry(entry) },
                                        viewModel = vm,
                                    )
                                },
                                accountRegistration = { entry, destination, navigator ->
                                    val factory = remember { viewModelFactory { initializer { AccountRegistrationViewModel(repo, clock, createSavedStateHandle(), repo.drafts) } } }
                                    val vm: AccountRegistrationViewModel = viewModel(factory = factory)
                                    AccountRegistrationRoute(
                                        testContext,
                                        { navigator.closeConfirmedAccountRegistration(entry) },
                                        { navigator.returnFromAccountRegistration(entry, it) },
                                        vm,
                                        draftScope = if (destination.origin == AccountRegistrationOrigin.ACCOUNTS) DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS) else DraftScope(DraftKind.ACCOUNT, DraftOrigin.TRANSACTION_ENTRY, destination.callerDraftId, destination.selectionRequestId, destination.selectionSlot?.let(DraftSlot::valueOf)),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun captureDraft(name: String) {
        val bounds = compose.onNodeWithTag("draft-root").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            rootView.draw(canvas)
        }
        val directory = File("build/reports/drafts/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

private class JourneyRepository : LedgerRepository by QueryRepository() {
    val drafts = TestDraftRepository(this)
    val accounts = mutableListOf<LedgerAccount>()
    val transactions = mutableListOf<CreateTransaction>()
    val finished = mutableListOf<String>()
    private val receipts = mutableMapOf<String, WriteReceipt>()
    override suspend fun submissionRecovery(context: LedgerContext) = SubmissionRecoverySnapshot(context, emptyList())
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate) = LedgerSnapshot(1, accounts.toList(), emptyList(), accounts.associate { it.id to null }, 0, 0)
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt {
        accounts += LedgerAccount(
            value.id,
            value.name,
            value.nature,
            AccountStatus.ACTIVE,
            1,
            BalanceAnchor(value.anchorId, value.anchorDate, null, null, value.unknownReason),
        )
        return WriteReceipt(operationId, 1, listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1))).also { receipts[operationId] = it }
    }
    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt {
        transactions += value
        return WriteReceipt(operationId, 2, listOf(EntityRef("transactions", value.id, 1))).also { receipts[operationId] = it }
    }
    override suspend fun receipt(context: LedgerContext, operationId: String) = receipts[operationId]
    override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult {
        finished += operationId
        return FinishSubmissionRecoveryResult.Finished
    }
}
