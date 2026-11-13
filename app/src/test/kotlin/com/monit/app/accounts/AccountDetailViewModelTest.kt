package com.monit.app.accounts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.app.payments.paymentTestId
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.model.*
import com.monit.feature.accounts.detail.*
import java.time.*
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
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AccountDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val repo = AnchorTestEnvironment()
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun close() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()): AccountDetailViewModel = AccountDetailViewModel(repo.ledger, repo.anchors, repo.drafts, saved, clock, contexts).also { value ->
        stores += ViewModelStore().also { it.put("anchor", value) }
        value.bind(repo.context, repo.accountId, paymentTestId(700))
    }
    private suspend fun TestScope.edit(): AccountDetailViewModel = model().also {
        advanceUntilIdle()
        it.edit()
        advanceUntilIdle()
    }

    /** 조회·미리보기는 금융 쓰기가 없고 기본값0 없이 최종확인 버튼에서만 저장한다. */
    @Test fun explicitFinalConfirmationIsTheOnlyWrite() = runTest(dispatcher) {
        val model = edit()
        assertEquals("", model.state.value.ui.form.amount)
        model.preview()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.EDITING, model.state.value.phase)
        assertTrue(repo.commands.isEmpty())
        model.change("0", "")
        model.preview()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.PREVIEW, model.state.value.phase)
        assertEquals("0원", model.state.value.ui.preview.amount)
        assertEquals(0, model.state.value.ui.preview.inclusionBefore)
        assertEquals(1, model.state.value.ui.preview.inclusionAfter)
        assertTrue(repo.commands.isEmpty())
        model.confirm()
        model.confirm()
        advanceUntilIdle()
        assertEquals(1, repo.commands.size)
        assertEquals(AccountDetailPhase.RESULT, model.state.value.phase)
        model.finish()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.READY, model.state.value.phase)
        assertEquals("0원", model.state.value.ui.anchorAmount)
        assertEquals(1, model.state.value.ui.history.size)
        assertEquals("", repo.histories.single().reason)
        assertEquals(AnchorChangeKind.INITIAL_CONFIRMATION, repo.histories.single().kind)
    }

    /** 목적: 응답 유실 후 새 ID·새 명령으로 중복 확정하지 않는다.
     * 조건: 대역은 commit 후 IOException을 던진다.
     * 절차: 최종확정→UNCERTAIN→결과확인 순서로 가상 스케줄러를 소진한다.
     * 기대: 원래receipt로 RESULT, writer호출1·이력1·음수원문 유지.
     */
    @Test fun lostReplyUsesReceiptBeforeRetry() = runTest(dispatcher) {
        repo.loseReply = true
        val model = edit()
        model.change("-300", "합성 음수 확인")
        model.preview()
        advanceUntilIdle()
        model.confirm()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.UNCERTAIN, model.state.value.phase)
        model.checkResult()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.RESULT, model.state.value.phase)
        assertEquals(1, repo.commands.size)
        assertEquals(1, repo.histories.size)
        assertEquals("-300", model.state.value.ui.form.amount)
    }

    /** 목적: commit 이전 실패를 terminal로 오분류하지 않고 같은 요청만 재시도한다.
     * 조건: 첫 writer는 금융 반영 전에 IOException을 던진다.
     * 절차: 실패→오류해제→명시 재시도→스케줄러소진.
     * 기대: 두 호출의 operation/명령 동일, 금융이력1·RESULT.
     */
    @Test fun retryKeepsOriginalIdRevisionAndAmount() = runTest(dispatcher) {
        repo.failBefore = true
        val model = edit()
        model.change("500", "확인")
        model.preview()
        advanceUntilIdle()
        model.confirm()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.UNCERTAIN, model.state.value.phase)
        model.change("900", "다른 요청")
        repo.failBefore = false
        model.checkResult()
        advanceUntilIdle()
        assertEquals(repo.commands[0], repo.commands[1])
        assertEquals(1, repo.histories.size)
        assertEquals(AccountDetailPhase.RESULT, model.state.value.phase)
    }

    /** 명시 충돌 결과는 새 기준액으로 자동 치환하지 않고 입력을 보존한다. */
    @Test fun revisionConflictKeepsOriginalInputAndRequiresResultAcknowledgement() = runTest(dispatcher) {
        repo.reject = true
        val model = edit()
        model.change("500", "이전 검토")
        model.preview()
        advanceUntilIdle()
        model.confirm()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.REJECTED, model.state.value.phase)
        assertEquals("500", model.state.value.ui.form.amount)
        assertEquals(1, repo.commands.size)
        model.confirm()
        model.checkResult()
        advanceUntilIdle()
        assertEquals(1, repo.commands.size)
        assertTrue(repo.histories.isEmpty())
    }

    /** 미완성·음수 입력 원문을 초안으로 보관하고 새 VM에서 명시 복구한다. 조회만으로 실행하지 않는다. */
    @Test fun newViewModelRestoresRawDraftOnlyAfterExplicitChoice() = runTest(dispatcher) {
        val first = edit()
        first.change("-00.입력중", " 원문\n")
        var closed = false
        first.keepDraftAndClose { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        stores.forEach { it.clear() }
        val second = edit()
        assertEquals(DraftEditorPhase.CHOOSING, second.state.value.draft.phase)
        assertEquals("", second.state.value.ui.form.amount)
        second.restore(second.state.value.draft.choices.single())
        advanceUntilIdle()
        assertEquals("-00.입력중", second.state.value.ui.form.amount)
        assertEquals(" 원문\n", second.state.value.ui.form.reason)
        assertTrue(repo.commands.isEmpty())
    }

    /** 초안 쓰기 실패 시 최종확정과 닫기를 막고 원문을 남긴다. */
    @Test fun draftFailureCannotBeReportedAsSavedOrClosed() = runTest(dispatcher) {
        val model = edit()
        repo.base.draftFailure = true
        model.change("100", "사유")
        model.preview()
        advanceUntilIdle()
        model.confirm()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.EDITING, model.state.value.phase)
        var closed = false
        model.keepDraftAndClose { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        assertEquals("100", model.state.value.ui.form.amount)
        assertTrue(repo.commands.isEmpty())
    }

    /** 목적: 기존 읽기가 늦게 끝나도 다른 장부·계좌 진입으로 오인하지 않는다.
     * 조건: 같은 VM에 다른 accountId를 다시 bind한다.
     * 절차: 초기read 예약→잘못된bind→스케줄러소진.
     * 기대: BLOCKED 유지·금융쓰기0.
     */
    @Test fun mismatchedBindingCannotAdoptLateResult() = runTest(dispatcher) {
        val model = model()
        model.bind(repo.context, paymentTestId(20), paymentTestId(700))
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.BLOCKED, model.state.value.phase)
        assertTrue(repo.commands.isEmpty())
    }

    /** 이미 확인된 기준액 변경은 빈 사유를 미리보기 단계에서 막고 최초 확인으로 바꾸지 않는다. */
    @Test fun correctionRequiresReasonBeforePreview() = runTest(dispatcher) {
        val selected = repo.base.ledgerValue.accounts.first().let { it.copy(anchor = it.anchor.copy(amount = 0, confirmedAt = Instant.EPOCH, unknownReason = null, revision = 2)) }
        repo.base.ledgerValue = repo.base.ledgerValue.copy(accounts = repo.base.ledgerValue.accounts.map { if (it.id == selected.id) selected else it }, balances = mapOf(selected.id to 0L))
        val model = edit()
        model.change("300", "")
        model.preview()
        advanceUntilIdle()
        assertEquals(AccountDetailPhase.EDITING, model.state.value.phase)
        assertTrue(model.state.value.ui.message.isNotBlank())
        assertTrue(repo.commands.isEmpty())
    }
}
