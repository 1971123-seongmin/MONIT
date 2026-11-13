package com.monit.feature.home.payments

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PaymentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val store = ViewModelStore()
    private var number = 0

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun vm(env: PaymentTestEnvironment, saved: SavedStateHandle = SavedStateHandle(), entry: String = "entry"): PaymentsViewModel = PaymentsViewModel(env.payments, env.ledger, env.drafts, clock, contexts, saved).also {
        store.put("vm-${number++}", it)
        it.bind(env.context, entry)
        dispatcher.scheduler.advanceUntilIdle()
    }
    private fun newRule(model: PaymentsViewModel) {
        model.registerRule()
        dispatcher.scheduler.advanceUntilIdle()
        model.edit("name", "합성 보험")
        model.edit("purpose", "INSURANCE")
        model.edit("amount", "30000")
        model.edit("debit", paymentTestId(10))
        dispatcher.scheduler.advanceUntilIdle()
    }
    private fun newPayment(model: PaymentsViewModel, full: String = "true") {
        model.selectItem(paymentTestId(50))
        model.newPayment()
        dispatcher.scheduler.advanceUntilIdle()
        if (full.isNotEmpty()) model.edit("full", full)
        dispatcher.scheduler.advanceUntilIdle()
    }

    /** 알림의 원래 월/회차는 조회로만 열고 초안·거래·완료를 자동 생성하지 않는다. */
    @Test fun notificationDestinationOpensOriginalItemWithoutWrites() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val model = PaymentsViewModel(env.payments, env.ledger, env.drafts, clock, contexts, SavedStateHandle())
        store.put("notification", model)
        model.bind(env.context, "notification-entry", "2026-11", paymentTestId(50))
        advanceUntilIdle()
        assertEquals(PaymentsMode.DETAIL, model.state.value.mode)
        assertEquals(paymentTestId(50), model.state.value.ui.selected!!.id)
        assertEquals(listOf("2026-11"), env.reads)
        assertTrue(env.calls.isEmpty())
        assertTrue(env.rows.isEmpty())
    }

    /** 오래된 없는 항목은 해당 월 목록으로, 잘못된 월은 실패로 분리하고 금융 쓰기는 없다. */
    @Test fun missingOrMalformedNotificationTargetNeverCreatesAnItem() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val missing = PaymentsViewModel(env.payments, env.ledger, env.drafts, clock, contexts, SavedStateHandle())
        store.put("missing-notification", missing)
        missing.bind(env.context, "missing-entry", "2026-11", paymentTestId(999))
        advanceUntilIdle()
        assertEquals(PaymentsMode.MONTH, missing.state.value.mode)
        assertNull(missing.state.value.ui.selected)
        val invalid = PaymentsViewModel(env.payments, env.ledger, env.drafts, clock, contexts, SavedStateHandle())
        store.put("invalid-notification", invalid)
        invalid.bind(env.context, "invalid-entry", "2026-13")
        advanceUntilIdle()
        assertEquals(PaymentsPhase.BLOCKED, invalid.state.value.phase)
        assertTrue(env.calls.isEmpty())
        assertTrue(env.rows.isEmpty())
    }

    /** 월별 조회·폼 입력·검토는 금융 제출 없이 원문만 보존한다. */
    @Test fun readInputAndReviewNeverSubmitBeforeFinalConfirmation() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val model = vm(env)
        assertTrue(env.calls.isEmpty())
        assertTrue(env.rows.isEmpty())
        newRule(model)
        model.review()
        advanceUntilIdle()
        assertEquals(PaymentsMode.REVIEW, model.state.value.mode)
        assertEquals("반복 납입 등록", model.state.value.ui.review.action)
        assertTrue(env.calls.isEmpty())
        assertTrue(env.draftSaves > 0)
    }

    /**
     * 목적: 최종 확인 연타를 단일 원래 요청으로 제한한다.
     * 조건: writer 대역이 첫 제출을 suspend gate에서 대기한다.
     * 절차: submit 두 번·runCurrent 후 SAVING을 확인하고 gate를 해제한다.
     * 기대: 제출 한 건·동일 초안 참조·원래 규칙 ID, 저장 결과를 확인한 뒤에만 SAVED다.
     */
    @Test fun doubleTapFinalRuleConfirmationSubmitsOnce() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val gate = CompletableDeferred<Unit>()
        env.beforeSubmit = { gate.await() }
        val model = vm(env)
        newRule(model)
        model.review()
        model.submit()
        model.submit()
        runCurrent()
        assertEquals(PaymentsPhase.SAVING, model.state.value.phase)
        assertEquals(1, env.calls.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PaymentsPhase.SAVED, model.state.value.phase)
        assertTrue(env.calls.single().command is PaymentCommand.RegisterRule)
        assertNotNull(env.calls.single().draft)
    }

    /** 전액·일부 선택이 없으면 검토로 진행하지 않으며 명시 최종 확인만 제출한다. */
    @Test fun fullSelectionAndFinalConfirmationAreBothRequired() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val model = vm(env)
        newPayment(model, "")
        model.review()
        assertEquals(PaymentsMode.TRANSACTION_FORM, model.state.value.mode)
        assertTrue(env.calls.isEmpty())
        model.dismissMessage()
        model.edit("full", "true")
        model.review()
        assertEquals(PaymentsMode.REVIEW, model.state.value.mode)
        assertTrue(env.calls.isEmpty())
        model.submit()
        advanceUntilIdle()
        assertTrue((env.calls.single().command as PaymentCommand.Confirm).full)
        assertEquals(LocalDate.of(2026, 11, 13), (env.calls.single().command as PaymentCommand.Confirm).transaction.value.date)
    }

    /** 차액/일부 사유를 검사하고 부분 결과를 완료로 표시하지 않는다. */
    @Test fun partialPaymentRequiresReasonAndKeepsPartialResultMeaning() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val model = vm(env)
        newPayment(model, "false")
        model.edit("amount", "25000")
        model.review()
        assertEquals(PaymentsMode.TRANSACTION_FORM, model.state.value.mode)
        model.dismissMessage()
        model.edit("reason", "일부만 기록")
        model.review()
        model.submit()
        advanceUntilIdle()
        val command = env.calls.single().command as PaymentCommand.Confirm
        assertFalse(command.full)
        assertEquals("일부만 기록", command.reason)
        assertTrue(model.state.value.ui.review.action.contains("미완료"))
        assertEquals("false", model.state.value.ui.form.values["full"])
    }

    /** 기존 거래는 금융 revision만 고정하고 값 편집/신규 거래 경로로 변환하지 않는다. */
    @Test fun existingTransactionSelectionPreservesIdAndFinancialRevisionWithoutCopy() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        env.ledgerValue = env.ledgerValue.copy(transactions = listOf(LedgerTransaction(paymentTestId(70), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 12), 30_000, paymentTestId(10), null, AccountNature.GENERAL, null, "기존 거래", 4, 2)))
        val model = vm(env)
        model.selectItem(paymentTestId(50))
        model.chooseExisting()
        advanceUntilIdle()
        model.useTransaction(paymentTestId(70))
        advanceUntilIdle()
        model.edit("amount", "1")
        model.edit("memo", "변경 시도")
        assertEquals("30000", model.state.value.ui.form.values["amount"])
        model.edit("full", "true")
        model.review()
        model.submit()
        advanceUntilIdle()
        val command = env.calls.single().command as PaymentCommand.Confirm
        assertEquals(paymentTestId(70), command.transaction.value.id)
        assertEquals(2, command.transaction.financialRevision)
        assertNull(command.transaction.revision)
    }

    /** 새 Nav entry의 규칙 등록도 원래 ID를 유지하는 명시 초안 선택으로 복구한다. */
    @Test fun incompleteRuleDraftRestoresAcrossNewEntryWithoutInventingNewTarget() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val first = vm(env)
        newRule(first)
        first.edit("amount", " 00.입력중 ")
        first.edit("start", "2026-")
        advanceUntilIdle()
        val original = (env.rows.values.single() as DraftSnapshot.Editable).draft
        store.clear()
        val next = vm(env, entry = "another-entry")
        next.registerRule()
        advanceUntilIdle()
        assertEquals(DraftEditorPhase.CHOOSING, next.state.value.draft!!.phase)
        assertTrue(next.matchesDraft(original))
        next.restoreDraft(original)
        advanceUntilIdle()
        assertEquals(" 00.입력중 ", next.state.value.ui.form.values["amount"])
        assertEquals("2026-", next.state.value.ui.form.values["start"])
        assertEquals(original.scope, next.state.value.draft!!.committed!!.scope)
        assertTrue(env.calls.isEmpty())
    }

    /** 초안 flush 실패는 금융 제출 없이 현재 원문과 실패 표시를 유지한다. */
    @Test fun failedDraftFlushNeverSubmits() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val model = vm(env)
        newRule(model)
        env.draftFailure = true
        model.edit("name", "저장 실패 입력")
        model.review()
        model.submit()
        advanceUntilIdle()
        assertTrue(env.calls.isEmpty())
        assertEquals(PaymentsPhase.READY, model.state.value.phase)
        assertEquals("저장 실패 입력", model.state.value.ui.form.values["name"])
        assertTrue(model.state.value.draft!!.failed)
    }

    /** commit 응답 유실과 SavedState 재생성은 원래 ID의 결과만 조회하고 재제출하지 않는다. */
    @Test fun lostReplyAndSavedStateRecreationUseOriginalOutcome() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { loseReply = true }
        val saved = SavedStateHandle()
        val first = vm(env, saved)
        newPayment(first)
        first.review()
        first.submit()
        advanceUntilIdle()
        assertEquals(PaymentsPhase.SAVED, first.state.value.phase)
        val original = env.calls.single()
        store.clear()
        val restored = vm(env, saved)
        assertEquals(PaymentsPhase.SAVED, restored.state.value.phase)
        assertEquals(1, env.calls.size)
        restored.finish()
        advanceUntilIdle()
        assertEquals(listOf(original.operationId), env.finished)
    }

    /** 결과 조회 I/O 실패는 결과 없음으로 바꾸지 않고 재시도·새 입력을 차단한다. */
    @Test fun outcomeReadFailureStaysUnknownAndCannotTriggerWriteOrRelease() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply {
            failSubmit = true
            failOutcome = true
        }
        val model = vm(env)
        newPayment(model)
        model.review()
        model.submit()
        advanceUntilIdle()
        assertEquals(PaymentsPhase.UNCONFIRMED, model.state.value.phase)
        assertFalse(model.state.value.resultAbsent)
        model.check(true)
        model.releaseForEditing()
        advanceUntilIdle()
        assertEquals(1, env.calls.size)
        model.back { fail("Unknown result must not close as a new edit") }
        assertEquals(PaymentsMode.RESULT, model.state.value.mode)
    }

    /** 조회로 결과 없음이 확인된 요청만 같은 ID로 명시 재시도한다. */
    @Test fun retryAfterDefiniteAbsenceKeepsIdCommandAndDraft() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { failSubmit = true }
        val model = vm(env)
        newPayment(model)
        model.review()
        model.submit()
        advanceUntilIdle()
        assertTrue(model.state.value.resultAbsent)
        val original = env.calls.single()
        env.failSubmit = false
        model.check(true)
        advanceUntilIdle()
        assertEquals(PaymentsPhase.SAVED, model.state.value.phase)
        assertEquals(listOf(original, original), env.calls)
    }

    /** 명시 요청 해제 후 원문을 복구·수정하고 재확인해야 새 ID로 제출한다. */
    @Test fun explicitReleaseRetainsRawDraftAndRequiresFreshConfirmation() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { failSubmit = true }
        val model = vm(env)
        newPayment(model)
        model.review()
        model.submit()
        advanceUntilIdle()
        val original = env.calls.single()
        model.releaseForEditing()
        advanceUntilIdle()
        assertEquals(PaymentAttempt.Released, env.outcomes[original.operationId])
        assertEquals(PaymentsMode.TRANSACTION_FORM, model.state.value.mode)
        val choice = model.state.value.draft!!.choices.single()
        model.restoreDraft(choice)
        advanceUntilIdle()
        model.edit("memo", "명시 수정")
        model.review()
        env.failSubmit = false
        model.submit()
        advanceUntilIdle()
        assertEquals(2, env.calls.size)
        assertNotEquals(original.operationId, env.calls.last().operationId)
        assertEquals("명시 수정", (env.calls.last().command as PaymentCommand.Confirm).transaction.value.memo)
    }

    /** 확정 revision 거절은 SAVED와 분리하고 확인 종료 전까지 원래 요청으로 보존한다. */
    @Test fun terminalConflictIsNeverShownAsSavedAndCanFinishExplicitly() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { reject = true }
        val model = vm(env)
        newPayment(model)
        model.review()
        model.submit()
        advanceUntilIdle()
        assertEquals(PaymentsPhase.REJECTED, model.state.value.phase)
        assertTrue(env.rows.values.single() is DraftSnapshot.Rejected)
        model.finish()
        advanceUntilIdle()
        assertEquals(listOf(env.calls.single().operationId), env.finished)
        assertEquals(PaymentsMode.MONTH, model.state.value.mode)
    }

    /** 다른 entry/context의 SavedState를 새 장부 입력으로 해석하지 않는다. */
    @Test fun savedStateCannotBindAnotherEntry() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val saved = SavedStateHandle()
        val model = vm(env, saved)
        newRule(model)
        advanceUntilIdle()
        store.clear()
        val wrong = vm(env, saved, "different")
        assertEquals(PaymentsPhase.BLOCKED, wrong.state.value.phase)
        wrong.submit()
        advanceUntilIdle()
        assertTrue(env.calls.isEmpty())
    }

    /**
     * 목적: 검토 중 프로세스 재생성 후 누락된 command로 최종 버튼이 무응답이 되는 것을 막는다.
     * 조건: 미제출 초안과 동일 entry SavedState만 있고 금융 제출은 없다.
     * 절차: VM을 제거·재생성하고 원래 초안을 명시 복구한 뒤 다시 검토·확정한다.
     * 기대: 복구 시 쓰기 0, 원래 거래 ID·예정 내용·금액으로 한 번만 제출한다.
     */
    @Test fun reviewRecreationRequiresRestoredDraftReviewAndKeepsOriginalTransaction() = runTest(dispatcher) {
        val env = PaymentTestEnvironment()
        val saved = SavedStateHandle()
        val first = vm(env, saved)
        newPayment(first)
        first.review()
        advanceUntilIdle()
        val original = first.state.value.ui.form.values
        store.clear()
        val restored = vm(env, saved)
        assertTrue(env.calls.isEmpty())
        restored.restoreDraft(restored.state.value.draft!!.choices.single())
        advanceUntilIdle()
        assertEquals(PaymentsMode.TRANSACTION_FORM, restored.state.value.mode)
        assertEquals(original, restored.state.value.ui.form.values)
        restored.review()
        restored.submit()
        advanceUntilIdle()
        assertEquals(PaymentsPhase.SAVED, restored.state.value.phase)
        val value = env.calls.single().command as PaymentCommand.Confirm
        assertEquals(original["transactionId"], value.transaction.value.id)
        assertEquals(30_000, value.transaction.value.amount)
        assertTrue(restored.state.value.ui.review.schedule.contains("합성 보험"))
    }

    /** 원래 intent가 저장되기 전 실패도 새 operation을 만들지 않고 동일 요청으로 재시도한다. */
    @Test fun retryMissingIntentKeepsOriginalIdentityAndPayload() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { failSubmit = true }
        val model = vm(env)
        newPayment(model)
        model.review()
        model.submit()
        advanceUntilIdle()
        val original = env.calls.single()
        env.outcomes.remove(original.operationId)
        model.check()
        advanceUntilIdle()
        assertTrue(model.state.value.resultAbsent)
        env.failSubmit = false
        model.check(true)
        advanceUntilIdle()
        assertEquals(listOf(original, original), env.calls)
        assertEquals(PaymentsPhase.SAVED, model.state.value.phase)
    }

    /** 새 entry에서도 준비된 원래 요청을 사용자가 선택한 뒤에만 복구한다. */
    @Test fun preparedRequestDiscoveryIsReadOnlyUntilExplicitSelectionAndRetry() = runTest(dispatcher) {
        val env = PaymentTestEnvironment().apply { failSubmit = true }
        val first = vm(env)
        newPayment(first)
        first.review()
        first.submit()
        advanceUntilIdle()
        val request = env.calls.single()
        store.clear()
        val next = vm(env, entry = "new-entry")
        assertEquals(1, env.calls.size)
        assertEquals(request.operationId, next.state.value.ui.requests.single().id)
        next.recover(request.operationId)
        advanceUntilIdle()
        assertEquals(PaymentsPhase.UNCONFIRMED, next.state.value.phase)
        env.failSubmit = false
        next.check(true)
        advanceUntilIdle()
        assertEquals(listOf(request, request), env.calls)
        assertEquals(PaymentsPhase.SAVED, next.state.value.phase)
    }
}
