package com.monit.feature.accounts

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.DraftSnapshot
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerAccount
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerEntryResult
import com.monit.core.model.LedgerRepository
import com.monit.core.model.LedgerSnapshot
import com.monit.core.model.PendingRecoverySnapshot
import com.monit.core.model.PendingWrite
import com.monit.core.model.WriteReceipt
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AccountRegistrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** 장부 식별자 없이 남은 초안 참조를 현재 장부의 새 입력으로 바꿔 쓰지 않는다. */
    @Test fun partialDraftBindingCannotAdoptCurrentLedger() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = SavedStateHandle(mapOf(AccountRegistrationViewModel.DRAFT_ID to "00000000-0000-0000-0000-000000000074"))
        val model = viewModel(repository, saved)
        model.bind(context)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, model.state.value.problem)
        assertFalse(model.state.value.editable)
        assertEquals(0, repository.initializations)
        assertEquals(0, repository.drafts.saves)
        assertTrue(repository.requests.isEmpty())
    }

    /** 이전 VM/SavedState 없이도 미제출 이름·날짜·유형·사유 원문을 명시 선택하여 복구한다. */
    @Test fun durableAccountDraftRestoresOnlyAfterExplicitChoice() = runTest(dispatcher) {
        val repository = FakeRepository()
        val first = ready(repository)
        first.onAction(AccountRegistrationAction.NameChanged("  합성 원문  "))
        first.onAction(AccountRegistrationAction.AnchorDateChanged("날짜 불완전"))
        first.onAction(AccountRegistrationAction.NatureChanged(com.monit.core.model.AccountNature.INVESTMENT))
        first.onAction(AccountRegistrationAction.UnknownReasonChanged(" 원문 사유\n "))
        advanceUntilIdle()
        stores.forEach { it.clear() }
        val recovered = viewModel(repository)
        advanceUntilIdle()
        assertEquals(com.monit.core.common.draft.DraftEditorPhase.CHOOSING, recovered.state.value.draftPersistence?.phase)
        assertEquals("", recovered.state.value.draft.name)
        recovered.restoreDraft(assertNotNull(recovered.state.value.draftPersistence).choices.single())
        advanceUntilIdle()
        assertEquals(first.state.value.draft, recovered.state.value.draft)
        assertTrue(repository.requests.isEmpty())
    }

    /** 계좌 초안 저장 실패는 금융 제출을 막고 수정 없는 재시도로만 다음 단계에 진입한다. */
    @Test fun accountSubmissionWaitsForDraftCommit() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = ready(repository)
        repository.drafts.failSave = true
        model.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertTrue(repository.requests.isEmpty())
        assertTrue(assertNotNull(model.state.value.draftPersistence).failed)
        assertEquals(AccountSavePhase.EDITING, model.state.value.phase)
        repository.drafts.failSave = false
        model.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(1, repository.requests.size)
        assertEquals(AccountSavePhase.SAVED, model.state.value.phase)
    }

    /** 다른 부모/슬롯의 자식 초안은 독립 계좌 등록에서 적용하지 않는다. */
    @Test fun childAccountDraftCannotRestoreIntoStandaloneForm() = runTest(dispatcher) {
        val repository = FakeRepository()
        val scope = com.monit.core.model.DraftScope(
            com.monit.core.model.DraftKind.ACCOUNT,
            com.monit.core.model.DraftOrigin.TRANSACTION_ENTRY,
            "00000000-0000-0000-0000-000000000071",
            "00000000-0000-0000-0000-000000000072",
            com.monit.core.model.DraftSlot.CREDIT,
        )
        val choice = repository.drafts.saveDraft(
            context,
            "00000000-0000-0000-0000-000000000073",
            0,
            scope,
            com.monit.core.model.DraftContent.Account("자식 초안", com.monit.core.model.AccountNature.GENERAL, "날짜 원문", "사유"),
        )
        val model = viewModel(repository)
        advanceUntilIdle()
        assertFalse(model.matchesDraft(choice))
        model.restoreDraft(choice)
        advanceUntilIdle()
        assertEquals(com.monit.core.common.draft.DraftEditorPhase.CHOOSING, model.state.value.draftPersistence?.phase)
        assertEquals("", model.state.value.draft.name)
        assertTrue(repository.requests.isEmpty())
        assertEquals(DraftSnapshot.Editable(choice), repository.drafts.draft(context, choice.reference.id))
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:30:00Z"), ZoneOffset.UTC)
    private val stores = mutableListOf<ViewModelStore>()
    private val context = LedgerContext(
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000002",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** 장부 식별자가 없으면 새 장부를 임의 생성하거나 저장하지 않는다. */
    @Test
    fun missingContextDoesNotInitializeOrWrite() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(AccountProblem.CONTEXT_REQUIRED, vm.state.value.problem)
        assertEquals(0, repository.initializations)
        vm.onAction(AccountRegistrationAction.Submit)
        assertTrue(repository.requests.isEmpty())
    }

    /** 앱 Ready context를 한 번 연결하며 read-only 발견으로 준비하고 신규 장부를 만들지 않는다. */
    @Test
    fun bindReadyContextWithoutCreatingMetadata() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository, SavedStateHandle())
        vm.bind(context)
        vm.bind(context)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.EDITING, vm.state.value.phase)
        assertEquals(1, repository.initializations)
        assertTrue(repository.requests.isEmpty())
    }

    /** 지원되는 task 재생성에서 미제출 성격/오류 입력도 그대로 유지한다. 재부팅 영속 초안은 별도 시험이다. */
    @Test
    fun taskDraftRestoresWithoutSubmitting() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        val first = viewModel(repository, saved)
        advanceUntilIdle()
        first.onAction(AccountRegistrationAction.NameChanged("  "))
        first.onAction(AccountRegistrationAction.AnchorDateChanged("날짜 원문"))
        first.onAction(AccountRegistrationAction.NatureChanged(com.monit.core.model.AccountNature.SAVINGS))
        val restored = viewModel(repository, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals("  ", restored.state.value.draft.name)
        assertEquals("날짜 원문", restored.state.value.draft.anchorDate)
        assertEquals(com.monit.core.model.AccountNature.SAVINGS, restored.state.value.draft.nature)
        assertTrue(repository.requests.isEmpty())
    }

    /** 복원된 초안의 원래 장부 세대가 다르면 현재 context로 바꿔 편집하지 않는다. */
    @Test
    fun foreignDraftGenerationBlocksEditing() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        val first = viewModel(repository, saved)
        advanceUntilIdle()
        first.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        val bundle = assertNotNull(saved.get<Bundle>(AccountRegistrationViewModel.DRAFT))
        bundle.putString(AccountRegistrationViewModel.STORAGE_GENERATION, "00000000-0000-0000-0000-000000000099")
        val restored = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, restored.state.value.problem)
        assertFalse(restored.state.value.editable)
        assertTrue(repository.requests.isEmpty())
    }

    /** UTC Clock에서도 기본 기준일은 서울 어제이고 빈 이름은 입력 그대로 보존한다. */
    @Test
    fun seoulDateAndValidationPreserveInput() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals("2026-11-10", vm.state.value.uiState.draft.anchorDate)
        vm.onAction(AccountRegistrationAction.NameChanged("  "))
        vm.onAction(AccountRegistrationAction.Submit)
        assertEquals(AccountInputError.NAME, vm.state.value.uiState.inputError)
        assertEquals("  ", vm.state.value.uiState.draft.name)
        assertTrue(repository.requests.isEmpty())
    }

    /** 기준일은 엄격한 ISO 날짜·1900년부터 서울 어제까지 허용한다. */
    @Test
    fun anchorDateBoundariesAreStrict() = runTest(dispatcher) {
        for (date in listOf("1899-12-31", "2026-11-11", "2026-11-12", "2026-02-30")) {
            val repository = FakeRepository()
            val vm = ready(repository)
            vm.onAction(AccountRegistrationAction.AnchorDateChanged(date))
            vm.onAction(AccountRegistrationAction.Submit)
            assertEquals(AccountInputError.ANCHOR_DATE, vm.state.value.uiState.inputError, date)
            assertTrue(repository.requests.isEmpty())
        }
        for (date in listOf("1900-01-01", "2026-11-10")) {
            val repository = FakeRepository()
            val vm = ready(repository)
            vm.onAction(AccountRegistrationAction.AnchorDateChanged(date))
            vm.onAction(AccountRegistrationAction.Submit)
            advanceUntilIdle()
            assertEquals(AccountSavePhase.SAVED, vm.state.value.phase, date)
        }
    }

    /** 모델과 같은 UTF-8·Unicode·미확인 사유 검증을 적용한다. */
    @Test
    fun invalidTextDoesNotCreateSubmission() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ready(repository)
        for (name in listOf("가".repeat(6000), "\uD800")) {
            vm.onAction(AccountRegistrationAction.NameChanged(name))
            vm.onAction(AccountRegistrationAction.Submit)
            assertEquals(AccountInputError.NAME, vm.state.value.uiState.inputError)
        }
        vm.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        vm.onAction(AccountRegistrationAction.UnknownReasonChanged(""))
        vm.onAction(AccountRegistrationAction.Submit)
        assertEquals(AccountInputError.UNKNOWN_REASON, vm.state.value.uiState.inputError)
        assertTrue(repository.requests.isEmpty())
    }

    /**
     * 목적: 반복 탭과 저장 중 편집이 별도 요청을 만들지 않는다.
     * 조건: 저장이 gate에서 중단된다.
     * 절차: 제출 두 번·편집 시도 후 gate를 해제한다.
     * 기대: 요청 하나·원래 이름·미확인 잔액 null이다.
     */
    @Test
    fun duplicateTapsFreezeOneSubmission() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.beforeWrite = { gate.await() }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        vm.onAction(AccountRegistrationAction.Submit)
        vm.onAction(AccountRegistrationAction.NameChanged("변경 시도"))
        runCurrent()
        assertEquals(AccountSavePhase.SAVING, vm.state.value.phase)
        assertEquals(1, repository.requests.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        assertEquals("합성 계좌", vm.state.value.uiState.account?.name)
        assertNull(assertNotNull(vm.state.value.uiState.account).balance)
        assertEquals("기준 잔액을 아직 확인하지 않았습니다", vm.state.value.uiState.account?.unknownReason)
        assertEquals(context, repository.requests.single().context)
    }

    /**
     * 목적: 커밋 후 응답 유실은 기존 결과로 확인한다.
     * 조건: 저장 완료 후 IOException을 던진다.
     * 절차: 제출과 동일 operation 영수증 조회를 완료한다.
     * 기대: SAVED·저장 한 번·결과 조회 한 번이다.
     */
    @Test
    fun lostResponseChecksReceiptWithoutSecondWrite() = runTest(dispatcher) {
        val repository = FakeRepository().apply { loseResponse = true }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        assertEquals(1, repository.requests.size)
        assertEquals(1, repository.receiptReads)
    }

    /**
     * 목적: 재시도는 같은 제출·계좌·기준점·내용을 유지한다.
     * 조건: 첫 쓰기가 커밋 전에 실패한다.
     * 절차: 실패 확인·편집 시도 후 Retry를 두 번 누른다.
     * 기대: 두 요청 전체가 같고 두 번째 Retry는 무시된다.
     */
    @Test
    fun explicitRetryUsesIdenticalSnapshot() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeWrite = { throw IOException() } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.state.value.problem)
        assertFalse(vm.state.value.editable)
        vm.onAction(AccountRegistrationAction.NameChanged("다른 이름"))
        repository.beforeWrite = {}
        vm.onAction(AccountRegistrationAction.RetrySave)
        vm.onAction(AccountRegistrationAction.RetrySave)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        assertEquals(2, repository.requests.size)
        assertEquals(repository.requests[0], repository.requests[1])
    }

    /**
     * 목적: 결과 조회 실패를 미저장으로 추측하지 않는다.
     * 조건: 저장·영수증 조회 모두 IOException이다.
     * 절차: 제출 후 명시 Retry까지 진행한다.
     * 기대: 미확정 상태·추가 쓰기 없음·입력 유지다.
     */
    @Test
    fun failedResultCheckNeverAutomaticallyResubmits() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            beforeWrite = { throw IOException() }
            beforeReceipt = { throw IOException() }
        }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.RetrySave)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.state.value.problem)
        assertEquals(1, repository.requests.size)
        assertEquals("합성 계좌", vm.state.value.uiState.draft.name)
    }

    /**
     * 목적: 저장 후 조회 실패는 재저장을 유발하지 않는다.
     * 조건: 저장 성공·snapshot 실패.
     * 절차: ReloadSavedAccount로 조회만 재시도한다.
     * 기대: SAVED 유지·쓰기 한 번·조회 두 번이다.
     */
    @Test
    fun readFailureKeepsConfirmedSave() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeSnapshot = { throw IOException() } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        assertEquals(AccountReadPhase.FAILED, vm.state.value.readPhase)
        assertNotNull(vm.state.value.savedAccountId)
        repository.beforeSnapshot = {}
        vm.onAction(AccountRegistrationAction.ReloadSavedAccount)
        advanceUntilIdle()
        assertEquals(AccountReadPhase.READY, vm.state.value.readPhase)
        assertEquals(1, repository.requests.size)
        assertEquals(2, repository.snapshotReads)
    }

    /**
     * 목적: 이전 조회가 새 입력 상태를 덮어쓰지 않는다.
     * 조건: 저장 확정 후 snapshot 응답이 gate에서 대기한다.
     * 절차: StartNew 후 gate를 해제한다.
     * 기대: 편집 가능·새 빈 이름·조회 전 상태를 유지한다.
     */
    @Test
    fun lateReadCannotOverwriteNewDraft() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.beforeSnapshot = { gate.await() }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        vm.onAction(AccountRegistrationAction.StartNew)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.EDITING, vm.state.value.phase)
        assertEquals("", vm.state.value.uiState.draft.name)
        assertEquals(AccountReadPhase.NOT_REQUESTED, vm.state.value.readPhase)
    }

    /** 새 등록을 명시하면 같은 이름이어도 별도 ID의 새 계좌가 된다. */
    @Test
    fun separateSameNameIntentGetsNewIdentifiers() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.StartNew)
        advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        val (first, second) = repository.requests
        assertNotEquals(first.operationId, second.operationId)
        assertNotEquals(first.value.id, second.value.id)
        assertNotEquals(first.value.anchorId, second.value.anchorId)
        assertEquals(first.value.name, second.value.name)
    }

    /**
     * 목적: 취소 후 복원은 기존 결과부터 조회한다.
     * 조건: 커밋 전 gate에서 ViewModelStore.clear로 취소한다.
     * 절차: 저장 스냅샷을 별도 handle로 복원 후 명시 Retry한다.
     * 기대: 취소 정리 완료·자동 쓰기 없음·재시도 요청 전체 동일이다.
     */
    @Test
    fun cancelledSubmissionRestoresSameIntent() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        repository.beforeWrite = {
            try {
                gate.await()
            } finally {
                finished = true
            }
        }
        val saved = handle()
        val vm = ready(repository, saved)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        val recovered = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        stores.first().clear()
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(AccountSavePhase.RETRY_AVAILABLE, vm.state.value.phase)
        val restored = viewModel(repository, recovered)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.RETRY_AVAILABLE, restored.state.value.phase)
        assertEquals(1, repository.requests.size)
        assertEquals(1, repository.receiptReads)
        repository.beforeWrite = {}
        restored.onAction(AccountRegistrationAction.RetrySave)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, restored.state.value.phase)
        assertEquals(repository.requests[0], repository.requests[1])
    }

    /**
     * 목적: 성공 제출의 재진입은 같은 계좌로 복귀한다.
     * 조건: 성공한 제출 스냅샷을 보관한다.
     * 절차: 새 ViewModel 초기화를 완료한다.
     * 기대: 같은 계좌 ID·SAVED이며 저장 호출은 한 번이다.
     */
    @Test
    fun committedSubmissionReentryOnlyReads() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        val vm = ready(repository, saved)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        val restored = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(vm.state.value.savedAccountId, restored.state.value.savedAccountId)
        assertEquals(AccountSavePhase.SAVED, restored.state.value.phase)
        assertEquals(1, repository.requests.size)
    }

    /** 손상되거나 다른 저장소 세대의 제출을 재생성·실행하지 않는다. */
    @Test
    fun corruptOrCrossGenerationRecoveryIsBlocked() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle().apply {
            this[AccountRegistrationViewModel.SUBMISSION] = arrayListOf("broken")
        }
        val vm = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, vm.state.value.problem)
        assertEquals(0, repository.initializations)
        val valid = handle()
        val original = ready(repository, valid)
        original.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        valid[AccountRegistrationViewModel.STORAGE_GENERATION] =
            "00000000-0000-0000-0000-000000000003"
        val changed = viewModel(repository, valid)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, changed.state.value.problem)
        assertEquals(1, repository.requests.size)
    }

    /**
     * 목적: 충돌·다른 제출 영수증을 성공으로 표시하지 않는다.
     * 조건: DUPLICATE_REQUEST 또는 다른 operationId를 반환한다.
     * 절차: 각각의 제출을 완료한다.
     * 기대: UNAVAILABLE·각 오류 코드·성공한 계좌 ID 없음이다.
     */
    @Test
    fun conflictAndMismatchedReceiptNeverShowSuccess() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            beforeWrite = { throw LedgerConflictException(LedgerConflict.DUPLICATE_REQUEST) }
        }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.CONFLICT, vm.state.value.problem)
        assertEquals(AccountSavePhase.UNAVAILABLE, vm.state.value.phase)
        assertEquals(0, repository.receiptReads)
        val other = FakeRepository().apply { mismatchReceipt = true }
        val otherVm = ready(other)
        otherVm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_MISMATCH, otherVm.state.value.problem)
        assertNull(otherVm.state.value.savedAccountId)
    }

    /**
     * 목적: 초기화 오류를 빈 결과로 위장하지 않는다.
     * 조건: 첫 initialize가 실패한다.
     * 절차: 명시 Retry로 초기화만 재실행한다.
     * 기대: 편집 가능·초기화 두 번·금융 쓰기 없음이다.
     */
    @Test
    fun initializationFailureHasExplicitRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeInitialize = { throw IOException() } }
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(AccountProblem.INITIALIZATION_FAILED, vm.state.value.problem)
        repository.beforeInitialize = {}
        vm.onAction(AccountRegistrationAction.RetryInitialization)
        advanceUntilIdle()
        assertTrue(vm.state.value.editable)
        assertEquals(2, repository.initializations)
        assertTrue(repository.requests.isEmpty())
    }

    /**
     * 목적: 저장 확정 뒤 조회 취소도 성공을 미확정 상태로 되돌리지 않는다.
     * 조건: snapshot이 gate에서 대기한다.
     * 절차: 저장 확정 확인 후 ViewModelStore를 지우고 취소 정리를 완료한다.
     * 기대: SAVED·계좌 ID 유지·조회만 FAILED이며 금융 쓰기는 한 번이다.
     */
    @Test
    fun readCancellationKeepsConfirmedSave() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        repository.beforeSnapshot = {
            try {
                gate.await()
            } finally {
                finished = true
            }
        }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        stores.first().clear()
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
        assertNotNull(vm.state.value.savedAccountId)
        assertEquals(AccountReadPhase.FAILED, vm.state.value.readPhase)
        assertEquals(1, repository.requests.size)
    }

    /** 제출하지 않은 이름은 새 handle에서 복원되지 않아 영속 초안 지원으로 오인하지 않는다. */
    @Test
    fun unsubmittedDraftIsNotPromisedAsDurable() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        ready(repository, saved)
        assertFalse(saved.contains(AccountRegistrationViewModel.SUBMISSION))
        val restored = viewModel(repository, handle())
        advanceUntilIdle()
        assertEquals("", restored.state.value.uiState.draft.name)
        assertTrue(repository.requests.isEmpty())
    }

    /**
     * 목적: 결과 확인은 추가 쓰기 권한을 갖지 않는다.
     * 조건: 커밋 전 실패·영수증 없음, 이후 영수증 조회도 실패한다.
     * 절차: gate로 조회 중 중복 탭을 제어하고 순서대로 두 조건을 확인한다.
     * 기대: 추가 저장 없이 미확정 상태이며 CheckResult 연타는 한 번의 조회다.
     */
    @Test
    fun checkResultIsReadOnlyAndRejectsDuplicateTaps() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeWrite = { throw IOException() } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        repository.beforeWrite = {}
        repository.beforeReceipt = { gate.await() }
        vm.onAction(AccountRegistrationAction.CheckResult)
        vm.onAction(AccountRegistrationAction.CheckResult)
        vm.onAction(AccountRegistrationAction.RetrySave)
        runCurrent()
        assertEquals(AccountSavePhase.CHECKING_RESULT, vm.state.value.phase)
        assertEquals(2, repository.receiptReads)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.RETRY_AVAILABLE, vm.state.value.phase)
        assertEquals(1, repository.requests.size)
        repository.beforeReceipt = { throw IOException() }
        vm.onAction(AccountRegistrationAction.CheckResult)
        advanceUntilIdle()
        assertEquals(1, repository.requests.size)
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.state.value.problem)
    }

    /**
     * 목적: v1 task를 읽어 v2로 바꾸어도 같은 금융 제출을 유지한다.
     * 조건: 합성 v1 스냅샷만 있고 영수증은 없다.
     * 절차: 복원·조회 전용 확인 후 명시 RetrySave한다.
     * 기대: v2로 기록·복원 중 쓰기 없음·재시도는 원래 ID와 값이다.
     */
    @Test
    fun legacyTaskMigratesWithoutNewSubmission() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle().apply {
            this[AccountRegistrationViewModel.SUBMISSION] = arrayListOf(
                "1", context.ledgerId, context.storageGeneration,
                "00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000005",
                "복원 계좌", "GENERAL", "2026-11-10", "미확인",
            )
        }
        val vm = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(
            2,
            saved.get<Bundle>(AccountRegistrationViewModel.SUBMISSION)?.getInt("version"),
        )
        assertTrue(repository.requests.isEmpty())
        vm.onAction(AccountRegistrationAction.CheckResult)
        advanceUntilIdle()
        assertTrue(repository.requests.isEmpty())
        vm.onAction(AccountRegistrationAction.RetrySave)
        advanceUntilIdle()
        val request = repository.requests.single()
        assertEquals("00000000-0000-0000-0000-000000000003", request.operationId)
        assertEquals("00000000-0000-0000-0000-000000000004", request.value.id)
        assertEquals("00000000-0000-0000-0000-000000000005", request.value.anchorId)
        assertEquals("복원 계좌", request.value.name)
        assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
    }

    /** 잘못된 v2는 초기화·조회·쓰기 없이 명시적인 복원 불가 상태가 된다. */
    @Test
    fun malformedBundleRecoveryNeverWrites() = runTest(dispatcher) {
        for (raw in listOf(
            Bundle().apply { putInt("version", 99) },
            Bundle().apply { putString("version", "2") },
        )) {
            val repository = FakeRepository()
            val saved = handle().apply { this[AccountRegistrationViewModel.SUBMISSION] = raw }
            val vm = viewModel(repository, saved)
            advanceUntilIdle()
            vm.onAction(AccountRegistrationAction.CheckResult)
            vm.onAction(AccountRegistrationAction.RetrySave)
            advanceUntilIdle()
            assertEquals(AccountProblem.INVALID_RECOVERY, vm.state.value.problem)
            assertEquals(0, repository.initializations)
            assertEquals(0, repository.receiptReads)
            assertTrue(repository.requests.isEmpty())
        }
    }

    /**
     * 목적: 이전 조회의 실패·취소도 새 입력을 덮어쓰지 않는다.
     * 조건: 저장은 확정됐고 snapshot이 gate에서 대기한다.
     * 절차: StartNew 후 IOException 또는 CancellationException으로 조회를 종료한다.
     * 기대: 새 편집 상태·빈 이름·조회 전 상태가 유지된다.
     */
    @Test
    fun lateReadFailureAndCancellationCannotOverwriteNewDraft() = runTest(dispatcher) {
        for (failure in listOf(IOException(), kotlinx.coroutines.CancellationException())) {
            val repository = FakeRepository()
            val gate = CompletableDeferred<Unit>()
            repository.beforeSnapshot = {
                gate.await()
                throw failure
            }
            val vm = ready(repository)
            vm.onAction(AccountRegistrationAction.Submit)
            runCurrent()
            assertEquals(AccountSavePhase.SAVED, vm.state.value.phase)
            vm.onAction(AccountRegistrationAction.StartNew)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(AccountSavePhase.EDITING, vm.state.value.phase)
            assertEquals("", vm.state.value.uiState.draft.name)
            assertEquals(AccountReadPhase.NOT_REQUESTED, vm.state.value.readPhase)
        }
    }

    /**
     * 목적: 기본 viewModelScope의 취소 수명주기와 ViewModel 간 작업 격리를 검증한다.
     * 조건: Main을 같은 TestDispatcher로 교체하고 두 ViewModel의 초기화가 각각 대기한다.
     * 절차: 첫 ViewModelStore만 clear하고 취소 정리를 실행한 뒤 두 번째 gate를 해제한다.
     * 기대: 첫 Scope와 초기화는 취소·정리되고 두 번째 Scope는 살아서 편집 상태로 진입한다.
     */
    @Test
    fun defaultScopesFollowEachViewModelLifetime() = runTest(dispatcher) {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val firstCleanedUp = CompletableDeferred<Unit>()
        val first = FakeRepository().apply {
            beforeInitialize = {
                try {
                    firstGate.await()
                } finally {
                    firstCleanedUp.complete(Unit)
                }
            }
        }
        val second = FakeRepository().apply { beforeInitialize = { secondGate.await() } }
        val firstVm = viewModel(first)
        val secondVm = viewModel(second)
        runCurrent()
        stores[0].clear()
        runCurrent()
        assertFalse(firstVm.viewModelScope.isActive)
        assertTrue(firstCleanedUp.isCompleted)
        assertTrue(secondVm.viewModelScope.isActive)
        assertEquals(AccountSavePhase.INITIALIZING, secondVm.state.value.phase)
        secondGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.EDITING, secondVm.state.value.phase)
    }

    /** 오류 진단에는 작업·예외 타입만 남기고 예외 메시지·계좌 입력·Throwable은 기록하지 않는다. */
    @Test
    fun diagnosticsExcludeExceptionMessagesAndFinancialInput() = runTest(dispatcher) {
        val sensitive = "합성 계좌명 987654원"
        val repository =
            FakeRepository().apply { beforeWrite = { throw IllegalStateException(sensitive) } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.NameChanged(sensitive))
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.state.value.problem)
        val log = ShadowLog.getLogsForTag("AccountRegistration").single()
        assertEquals("write: IllegalStateException", log.msg)
        assertNull(log.throwable)
    }

    private fun handle() = SavedStateHandle(
        mapOf(
            AccountRegistrationViewModel.LEDGER_ID to context.ledgerId,
            AccountRegistrationViewModel.STORAGE_GENERATION to context.storageGeneration,
        ),
    )

    private fun viewModel(repository: FakeRepository, saved: SavedStateHandle = handle()) = AccountRegistrationViewModel(repository, clock, saved, repository.drafts).also { vm ->
        stores += ViewModelStore().also { it.put("account", vm) }
        if (saved.contains(AccountRegistrationViewModel.LEDGER_ID) && saved.contains(AccountRegistrationViewModel.STORAGE_GENERATION)) vm.bind(context)
    }

    private fun ready(
        repository: FakeRepository,
        saved: SavedStateHandle = handle(),
    ): AccountRegistrationViewModel {
        val vm = viewModel(repository, saved)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        return vm
    }
}

private data class AccountRequest(
    val context: LedgerContext,
    val operationId: String,
    val value: CreateAccount,
)

private class FakeRepository : LedgerRepository {
    val drafts = TestDraftRepository(this)
    override suspend fun submissionRecovery(context: LedgerContext): com.monit.core.model.SubmissionRecoverySnapshot {
        initializations++
        beforeInitialize()
        return com.monit.core.model.SubmissionRecoverySnapshot(context, emptyList())
    }

    override suspend fun finishSubmissionRecovery(
        context: LedgerContext,
        operationId: String,
    ): com.monit.core.model.FinishSubmissionRecoveryResult = error("이 테스트는 제출 종료를 호출하지 않는다")

    var beforeInitialize: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var beforeReceipt: suspend () -> Unit = {}
    var beforeSnapshot: suspend () -> Unit = {}
    var loseResponse = false
    var mismatchReceipt = false
    var initializations = 0
    var receiptReads = 0
    var snapshotReads = 0
    val requests = mutableListOf<AccountRequest>()
    private val receipts = mutableMapOf<String, WriteReceipt>()
    private val accounts = mutableMapOf<String, LedgerAccount>()

    override suspend fun openLedger(): LedgerEntryResult = error("계좌 등록 상태 시험은 준비된 장부 context를 전달합니다")

    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot = error("계좌 등록 상태 시험에서 복구 대상 선택을 대신하지 않습니다")

    override suspend fun initialize(context: LedgerContext) {
        error("화면 진입은 장부를 생성하거나 수리하지 않는다")
    }

    override suspend fun createAccount(
        context: LedgerContext,
        operationId: String,
        value: CreateAccount,
    ): WriteReceipt {
        requests += AccountRequest(context, operationId, value)
        beforeWrite()
        val receipt = receipts.getOrPut(operationId) {
            accounts[value.id] = LedgerAccount(
                value.id,
                value.name,
                value.nature,
                AccountStatus.ACTIVE,
                1,
                BalanceAnchor(value.anchorId, value.anchorDate, null, null, value.unknownReason),
            )
            WriteReceipt(
                operationId,
                2,
                listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1)),
            )
        }
        if (loseResponse) throw IOException()
        return if (mismatchReceipt) receipt.copy(operationId = "other") else receipt
    }

    override suspend fun paymentRejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.PaymentRejection? = null
    override suspend fun rejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.TransactionRejection? = null
    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? {
        receiptReads++
        beforeReceipt()
        return receipts[operationId]
    }

    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        snapshotReads++
        beforeSnapshot()
        return LedgerSnapshot(
            2,
            accounts.values.toList(),
            emptyList(),
            accounts.keys.associateWith { null },
            0,
            0,
        )
    }

    override suspend fun updateTransaction(context: com.monit.core.model.LedgerContext, operationId: String, value: com.monit.core.model.UpdateTransaction): com.monit.core.model.WriteReceipt = error("Unexpected update")
    override suspend fun deleteTransaction(context: com.monit.core.model.LedgerContext, operationId: String, value: com.monit.core.model.DeleteTransaction): com.monit.core.model.WriteReceipt = error("Unexpected delete")

    override suspend fun createTransaction(
        context: LedgerContext,
        operationId: String,
        value: CreateTransaction,
    ): WriteReceipt = error("계좌 등록에서 거래 쓰기를 호출하면 안 됩니다")

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = error("계좌 등록에서 불투명한 다른 제출을 실행하면 안 됩니다")

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = error("계좌 등록은 캡처한 typed 요청만 재시도합니다")
}
