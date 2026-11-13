package com.monit.app.payments

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
import com.monit.core.model.*
import com.monit.feature.home.HomeRoute
import com.monit.feature.home.HomeViewModel
import com.monit.feature.home.payments.*
import com.monit.feature.transactions.entry.TransactionEntryRoute
import com.monit.feature.transactions.entry.TransactionEntryViewModel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Home/납입 Route·VM·Nav2의 합성 프로토콜 시험. 금융/파일 원자성은 data 시험과 구분한다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val env = PaymentTestEnvironment()
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var back: androidx.activity.OnBackPressedDispatcher
    private lateinit var root: View
    private lateinit var model: PaymentsViewModel

    private fun show(dark: Boolean = false, width: Int = 360, font: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                    MonitTheme(dark) {
                        val owner = LocalOnBackPressedDispatcherOwner.current
                        val view = LocalView.current
                        SideEffect {
                            back = requireNotNull(owner).onBackPressedDispatcher
                            root = view
                        }

                        Box(Modifier.fillMaxSize().testTag("payments-root")) {
                            MonitAppShell(
                                home = { actions ->
                                    val factory = remember { viewModelFactory { initializer { HomeViewModel(env.ledger, clock, contexts, env.payments) } } }
                                    val home: HomeViewModel = viewModel(factory = factory)
                                    HomeRoute(
                                        env.context,
                                        home,
                                        onPayments = actions.payments,
                                    )
                                },
                                transactions = { Text("원래 거래 탭") },
                                accounts = { Text("원래 계좌 탭") },
                                payments = { entry, destination, navigator ->
                                    val factory = remember(entry) { viewModelFactory { initializer { PaymentsViewModel(env.payments, env.ledger, env.drafts, clock, contexts, createSavedStateHandle()) } } }
                                    val vm: PaymentsViewModel = viewModel(factory = factory)
                                    SideEffect { model = vm }

                                    PaymentsRoute(
                                        env.context,
                                        destination.entryId,
                                        onClose = { navigator.closePayments(entry) },
                                        onEditTransaction = { id -> navigator.openTransactionEntry(entry, com.monit.feature.transactions.navigation.TransactionEntryDestination(java.util.UUID.randomUUID().toString(), com.monit.feature.transactions.navigation.TransactionEntryKind.EXPENSE, transactionId = id)) },
                                        viewModel = vm,
                                    )
                                },
                                transactionEntry = { entry, destination, navigator ->
                                    val factory = remember(entry) { viewModelFactory { initializer { TransactionEntryViewModel(env.ledger, clock, createSavedStateHandle(), env.drafts) } } }
                                    val vm: TransactionEntryViewModel = viewModel(factory = factory)
                                    TransactionEntryRoute(
                                        env.context,
                                        destination,
                                        entry,
                                        { _, _, _ -> error("No account registration in this journey") },
                                        { navigator.closeConfirmedTransactionEntry(entry) },
                                        vm,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun awaitPhase(phase: PaymentsPhase) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            model.state.value.phase == phase
        }
    }
    private fun click(text: String) {
        awaitText(text)
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun field(label: String, value: String) {
        compose.onNodeWithContentDescription(label).performScrollTo().performTextReplacement(value)
    }
    private fun openMonth() {
        click("월별 납입 보기")
        awaitText("반복 납입 등록")
    }
    private fun openItem() {
        compose.onNodeWithTag("payment-row:${paymentTestId(50)}").performScrollTo().performClick()
        awaitText("합성 보험 납입 확인")
    }
    private fun newPayment() {
        openMonth()
        openItem()
        click("새 납입 내용 입력")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("실제 금액 (원)").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun tx() = LedgerTransaction(paymentTestId(70), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 12), 30_000, paymentTestId(10), null, AccountNature.GENERAL, null, "합성 기존 거래", 1, 1)

    /** 홈 실제 조회에서 규칙 입력→검토→명시 등록→결과 확인→원래 홈 복귀까지 이동한다. */
    @Test fun registerRuleFromHomeRequiresFinalConfirmationAndReturnsToHome() {
        show()
        openMonth()
        click("반복 납입 등록")
        field("납입 이름", "합성 새 보험")
        click("보험")
        field("예정 금액 (원)", "30000")
        click("출금 계좌 · 계좌 선택")
        click("합성 계좌 10")
        click("납입 내용 검토")
        awaitText("반복 납입 등록 확인")
        assertTrue(env.calls.isEmpty())
        capture("rule-review-light")
        click("반복 납입 등록")
        awaitPhase(PaymentsPhase.SAVED)
        val command = env.calls.single().command as PaymentCommand.RegisterRule
        assertEquals("합성 새 보험", command.terms.name)
        assertEquals(PaymentPurpose.INSURANCE, command.terms.purpose)
        assertEquals(paymentTestId(10), command.terms.debitAccountId)
        click("결과 확인 마치기")
        awaitPhase(PaymentsPhase.READY)
        compose.runOnIdle { back.onBackPressed() }
        awaitText("이번 달 루틴")
        assertEquals(listOf(env.calls.single().operationId), env.finished)
    }

    /** 새 납입 입력·부분 선택·차이 사유를 검토한 뒤에만 거래/미완료 의미를 제출한다. */
    @Test fun partialConfirmationRetainsIncompleteMeaningAndActualValues() {
        show()
        newPayment()
        field("실제 금액 (원)", "25000")
        field("실제 납입일 (YYYY-MM-DD)", "2026-11-12")
        click("일부만 기록하고 미완료로 유지해요")
        field("차이·일부 기록 사유", "일부만 기록")
        click("납입 내용 검토")
        assertTrue(env.calls.isEmpty())
        click("거래만 보존하고 미완료 유지")
        awaitPhase(PaymentsPhase.SAVED)
        val command = env.calls.single().command as PaymentCommand.Confirm
        assertFalse(command.full)
        assertEquals(25_000, command.transaction.value.amount)
        assertEquals(LocalDate.of(2026, 11, 12), command.transaction.value.date)
        awaitText("거래만 보존했어요 · 납입은 미완료예요")
        capture("partial-result-light")
    }

    /** 기존 거래는 선택·전액 확인·최종 확인을 거쳐 원래 ID/금융 revision으로만 연결한다. */
    @Test fun existingTransactionUsesOriginalIdWithoutCopy() {
        env.ledgerValue = env.ledgerValue.copy(transactions = listOf(tx()))
        show()
        openMonth()
        openItem()
        click("기존 거래 연결")
        click("지출 · 30,000원")
        compose.onNodeWithContentDescription("실제 금액 (원)").assertIsNotEnabled()
        click("이번 회차 전액을 냈음을 확인해요")
        click("납입 내용 검토")
        assertTrue(env.calls.isEmpty())
        click("확인하고 납입 완료")
        awaitPhase(PaymentsPhase.SAVED)
        val command = env.calls.single().command as PaymentCommand.Confirm
        assertEquals(paymentTestId(70), command.transaction.value.id)
        assertEquals(1, command.transaction.financialRevision)
        assertNull(command.transaction.revision)
        assertEquals(listOf(tx()), env.ledgerValue.transactions)
    }

    /** 시스템 Back은 입력을 보관할지 묻고 새 entry의 명시 복구에서 불완전 원문을 유지한다. */
    @Test fun systemBackRetainsRawDraftWithoutSubmitting() {
        show()
        newPayment()
        field("실제 금액 (원)", "00.입력중")
        compose.runOnIdle { back.onBackPressed() }
        awaitText("납입 입력에서 나갈까요?")
        click("보관하고 목록으로")
        awaitPhase(PaymentsPhase.READY)
        openItem()
        click("새 납입 내용 입력")
        awaitText("미제출 납입 초안 확인")
        click("이 초안 복구")
        compose.onNodeWithContentDescription("실제 금액 (원)").performScrollTo().assertTextContains("00.입력중")
        assertTrue(env.calls.isEmpty())
    }

    /** 320dp/큰 글꼴의 실제 선택·검토도 마지막 최종 버튼까지 도달하고 Dark로 렌더된다. */
    @Test fun darkLargeFontReviewReachesFinalConfirmation() {
        show(dark = true, width = 320, font = 2f)
        newPayment()
        click("이번 회차 전액을 냈음을 확인해요")
        click("납입 내용 검토")
        compose.onNodeWithText("기록하고 납입 완료").performScrollTo().assertIsDisplayed()
        assertTrue(env.calls.isEmpty())
        capture("new-confirm-dark-large")
        click("기록하고 납입 완료")
        awaitPhase(PaymentsPhase.SAVED)
        assertTrue((env.calls.single().command as PaymentCommand.Confirm).full)
    }

    /** 금융 수정 화면의 실제 저장/복귀 뒤 같은 납입 caller가 재조회해 재확인을 표시한다. */
    @Test fun transactionEditReturnsToExactPaymentCallerAndRefreshesConfirmation() {
        val old = tx()
        env.ledgerValue = env.ledgerValue.copy(transactions = listOf(old))
        env.monthly = env.monthly.copy(items = listOf(env.baseItem.copy(reference = env.baseItem.reference.copy(expectedRevision = 1), status = PaymentStatus.COMPLETED, transactionId = old.id, confirmedFinancialRevision = 1, confirmedTransaction = CreateTransaction(old.id, old.kind, old.date, old.amount, old.debitAccountId, old.creditAccountId, old.memo))))
        env.onTransactionUpdate = { operation, value ->
            assertEquals(25_000, value.value.amount)
            val changed = old.copy(amount = 25_000, revision = 2, financialRevision = 2)
            env.ledgerValue = env.ledgerValue.copy(revision = 2, transactions = listOf(changed))
            env.monthly = env.monthly.copy(ledgerRevision = 2, items = env.monthly.items.map { it.copy(reference = it.reference.copy(expectedRevision = 2), status = PaymentStatus.RECONFIRM) })
            WriteReceipt(operation, 2, listOf(EntityRef("transactions", old.id, 2)))
        }
        show()
        openMonth()
        openItem()
        click("연결 거래 확인·수정")
        awaitText("거래 상세·수정")
        click("수정하기")
        field("금액 (원)", "25000")
        click("저장")
        awaitText("기록을 저장했어요")
        click("목록 보기")
        awaitText("합성 보험 납입 확인")
        awaitText("재확인 필요 · 금융 근거가 바뀌었어요")
        compose.onNodeWithText("연결 거래 재확인").performScrollTo().assertIsDisplayed()
        assertTrue(env.calls.isEmpty())
        capture("reconfirmation-after-edit-light")
    }

    /** 미래 적용 월을 사용자가 검토한 규칙 변경만 제출하며 현재 월 값으로 치환하지 않는다. */
    @Test fun ruleChangePreservesExplicitFutureEffectiveMonth() {
        show()
        openMonth()
        click("합성 보험 규칙 변경")
        field("예정 금액 (원)", "40000")
        field("매월 납입일 (1~31)", "15")
        field("변경 적용 시작 월 (YYYY-MM)", "2026-12")
        click("납입 내용 검토")
        assertTrue(env.calls.isEmpty())
        click("규칙 변경 확정")
        awaitPhase(PaymentsPhase.SAVED)
        val command = env.calls.single().command as PaymentCommand.ChangeRule
        assertEquals("2026-12", command.effectiveMonth)
        assertEquals(1, command.expectedRevision)
        assertEquals(40_000, command.terms.amount)
    }

    /** P01의 회차 상세와 신규 전액 최종 확인을 Light 합성 자료로 렌더한다. */
    @Test
    fun lightDetailAndFinalReviewKeepExplicitConfirmation() {
        show()
        openMonth()
        openItem()
        capture("payment-detail-light")
        click("새 납입 내용 입력")
        click("이번 회차 전액을 냈음을 확인해요")
        click("납입 내용 검토")
        capture("new-confirm-light")
        assertTrue(env.calls.isEmpty())
    }

    /** 최종 확인 Dark도 같은 표시 계약과 접근 가능한 최종 행동을 유지한다. */
    @Test
    fun darkFinalReviewKeepsExplicitConfirmation() {
        show(dark = true)
        newPayment()
        click("이번 회차 전액을 냈음을 확인해요")
        click("납입 내용 검토")
        capture("new-confirm-dark")
        compose.onNodeWithText("기록하고 납입 완료").assertIsDisplayed()
        assertTrue(env.calls.isEmpty())
    }

    /** 건너뜀 선택은 검토만 열고 최종 확인에서만 SKIP을 제출한다. */
    @Test
    fun skipRequiresSeparateFinalConfirmation() {
        show()
        openMonth()
        openItem()
        click("이번 달 건너뛰기")
        awaitText("납입 상태 변경 확인")
        assertTrue(env.calls.isEmpty())
        click("건너뛰기 확인")
        awaitPhase(PaymentsPhase.SAVED)
        assertEquals(PaymentAction.SKIP, (env.calls.single().command as PaymentCommand.ChangeItem).action)
    }

    /** 연결 해제는 원래 거래를 다시 생성·삭제하지 않고 별도 확인한 UNLINK만 전달한다. */
    @Test
    fun unlinkKeepsOriginalTransactionAndRequiresFinalConfirmation() {
        val old = tx()
        env.ledgerValue = env.ledgerValue.copy(transactions = listOf(old))
        env.monthly = env.monthly.copy(items = listOf(env.baseItem.copy(reference = env.baseItem.reference.copy(expectedRevision = 1), status = PaymentStatus.COMPLETED, transactionId = old.id, confirmedFinancialRevision = 1)))
        show()
        openMonth()
        openItem()
        click("완료 연결 해제")
        assertTrue(env.calls.isEmpty())
        click("연결 해제 확인")
        awaitPhase(PaymentsPhase.SAVED)
        assertEquals(PaymentAction.UNLINK, (env.calls.single().command as PaymentCommand.ChangeItem).action)
        assertEquals(listOf(old), env.ledgerValue.transactions)
    }

    /** 종료 월 원문과 규칙 revision을 명시 확인한 뒤에만 종료 요청을 보낸다. */
    @Test
    fun endingRuleRequiresExplicitInclusiveMonthConfirmation() {
        show()
        openMonth()
        click("합성 보험 일정 종료")
        field("종료 월 (YYYY-MM)", "2026-12")
        click("납입 내용 검토")
        awaitText("종료 월 2026-12 포함")
        assertTrue(env.calls.isEmpty())
        click("일정 종료 확정")
        awaitPhase(PaymentsPhase.SAVED)
        val value = env.calls.single().command as PaymentCommand.EndRule
        assertEquals("2026-12", value.endMonth)
        assertEquals(1, value.expectedRevision)
    }

    /** 최종 확인의 취소는 금융 제출 없이 원문 초안을 보관하고 월별 목록으로 돌아간다. */
    @Test
    fun cancelReviewRetainsDraftWithoutFinancialSubmission() {
        show()
        newPayment()
        click("이번 회차 전액을 냈음을 확인해요")
        click("납입 내용 검토")
        click("취소")
        awaitPhase(PaymentsPhase.READY)
        awaitText("반복 납입 등록")
        assertEquals(PaymentsMode.MONTH, model.state.value.mode)
        assertTrue(env.calls.isEmpty())
        assertTrue(env.rows.values.single() is DraftSnapshot.Editable)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("payments-root").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            root.draw(canvas)
        }
        val directory = File("build/reports/payments/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
