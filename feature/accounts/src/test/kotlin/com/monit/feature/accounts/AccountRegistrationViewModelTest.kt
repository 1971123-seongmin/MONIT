package com.monit.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerAccount
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.LedgerSnapshot
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRegistrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:30:00Z"), ZoneOffset.UTC)
    private val stores = mutableListOf<ViewModelStore>()
    private val context = LedgerContext(
        "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** 장부 식별자가 없으면 새 장부를 임의 생성하거나 저장하지 않는다. */
    @Test fun missingContextDoesNotInitializeOrWrite() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(AccountProblem.CONTEXT_REQUIRED, vm.uiState.value.problem)
        assertEquals(0, repository.initializations)
        vm.onAction(AccountRegistrationAction.Submit)
        assertTrue(repository.requests.isEmpty())
    }

    /** UTC Clock에서도 기본 기준일은 서울 어제이고 빈 이름은 입력 그대로 보존한다. */
    @Test fun seoulDateAndValidationPreserveInput() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals("2026-11-10", vm.uiState.value.draft.anchorDate)
        vm.onAction(AccountRegistrationAction.NameChanged("  "))
        vm.onAction(AccountRegistrationAction.Submit)
        assertEquals(AccountInputError.NAME, vm.uiState.value.inputError)
        assertEquals("  ", vm.uiState.value.draft.name)
        assertTrue(repository.requests.isEmpty())
    }

    /** 기준일은 엄격한 ISO 날짜·1900년부터 서울 어제까지 허용한다. */
    @Test fun anchorDateBoundariesAreStrict() = runTest(dispatcher) {
        for (date in listOf("1899-12-31", "2026-11-11", "2026-11-12", "2026-02-30")) {
            val repository = FakeRepository()
            val vm = ready(repository)
            vm.onAction(AccountRegistrationAction.AnchorDateChanged(date))
            vm.onAction(AccountRegistrationAction.Submit)
            assertEquals(AccountInputError.ANCHOR_DATE, vm.uiState.value.inputError, date)
            assertTrue(repository.requests.isEmpty())
        }
        for (date in listOf("1900-01-01", "2026-11-10")) {
            val repository = FakeRepository()
            val vm = ready(repository)
            vm.onAction(AccountRegistrationAction.AnchorDateChanged(date))
            vm.onAction(AccountRegistrationAction.Submit)
            advanceUntilIdle()
            assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase, date)
        }
    }

    /** 모델과 같은 UTF-8·Unicode·미확인 사유 검증을 적용한다. */
    @Test fun invalidTextDoesNotCreateSubmission() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ready(repository)
        for (name in listOf("가".repeat(6000), "\uD800")) {
            vm.onAction(AccountRegistrationAction.NameChanged(name))
            vm.onAction(AccountRegistrationAction.Submit)
            assertEquals(AccountInputError.NAME, vm.uiState.value.inputError)
        }
        vm.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        vm.onAction(AccountRegistrationAction.UnknownReasonChanged(""))
        vm.onAction(AccountRegistrationAction.Submit)
        assertEquals(AccountInputError.UNKNOWN_REASON, vm.uiState.value.inputError)
        assertTrue(repository.requests.isEmpty())
    }

    /**
     * 목적: 반복 탭과 저장 중 편집이 별도 요청을 만들지 않는다.
     * 조건: 저장이 gate에서 중단된다.
     * 절차: 제출 두 번·편집 시도 후 gate를 해제한다.
     * 기대: 요청 하나·원래 이름·미확인 잔액 null이다.
     */
    @Test fun duplicateTapsFreezeOneSubmission() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.beforeWrite = { gate.await() }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        vm.onAction(AccountRegistrationAction.Submit)
        vm.onAction(AccountRegistrationAction.NameChanged("변경 시도"))
        runCurrent()
        assertEquals(AccountSavePhase.SAVING, vm.uiState.value.phase)
        assertEquals(1, repository.requests.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        assertEquals("합성 계좌", vm.uiState.value.account?.name)
        assertNull(assertNotNull(vm.uiState.value.account).balance)
        assertEquals("기준 잔액을 아직 확인하지 않았습니다", vm.uiState.value.account?.unknownReason)
        assertEquals(context, repository.requests.single().context)
    }

    /**
     * 목적: 커밋 후 응답 유실은 기존 결과로 확인한다.
     * 조건: 저장 완료 후 IOException을 던진다.
     * 절차: 제출과 동일 operation 영수증 조회를 완료한다.
     * 기대: SAVED·저장 한 번·결과 조회 한 번이다.
     */
    @Test fun lostResponseChecksReceiptWithoutSecondWrite() = runTest(dispatcher) {
        val repository = FakeRepository().apply { loseResponse = true }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        assertEquals(1, repository.requests.size)
        assertEquals(1, repository.receiptReads)
    }

    /**
     * 목적: 재시도는 같은 제출·계좌·기준점·내용을 유지한다.
     * 조건: 첫 쓰기가 커밋 전에 실패한다.
     * 절차: 실패 확인·편집 시도 후 Retry를 두 번 누른다.
     * 기대: 두 요청 전체가 같고 두 번째 Retry는 무시된다.
     */
    @Test fun explicitRetryUsesIdenticalSnapshot() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeWrite = { throw IOException() } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.uiState.value.problem)
        assertFalse(vm.uiState.value.editable)
        vm.onAction(AccountRegistrationAction.NameChanged("다른 이름"))
        repository.beforeWrite = {}
        vm.onAction(AccountRegistrationAction.Retry)
        vm.onAction(AccountRegistrationAction.Retry)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        assertEquals(2, repository.requests.size)
        assertEquals(repository.requests[0], repository.requests[1])
    }

    /**
     * 목적: 결과 조회 실패를 미저장으로 추측하지 않는다.
     * 조건: 저장·영수증 조회 모두 IOException이다.
     * 절차: 제출 후 명시 Retry까지 진행한다.
     * 기대: 미확정 상태·추가 쓰기 없음·입력 유지다.
     */
    @Test fun failedResultCheckNeverAutomaticallyResubmits() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            beforeWrite = { throw IOException() }
            beforeReceipt = { throw IOException() }
        }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.Retry)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_UNCONFIRMED, vm.uiState.value.problem)
        assertEquals(1, repository.requests.size)
        assertEquals("합성 계좌", vm.uiState.value.draft.name)
    }

    /**
     * 목적: 저장 후 조회 실패는 재저장을 유발하지 않는다.
     * 조건: 저장 성공·snapshot 실패.
     * 절차: ReloadSavedAccount로 조회만 재시도한다.
     * 기대: SAVED 유지·쓰기 한 번·조회 두 번이다.
     */
    @Test fun readFailureKeepsConfirmedSave() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeSnapshot = { throw IOException() } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        assertEquals(AccountReadPhase.FAILED, vm.uiState.value.readPhase)
        assertNotNull(vm.uiState.value.savedAccountId)
        repository.beforeSnapshot = {}
        vm.onAction(AccountRegistrationAction.ReloadSavedAccount)
        advanceUntilIdle()
        assertEquals(AccountReadPhase.READY, vm.uiState.value.readPhase)
        assertEquals(1, repository.requests.size)
        assertEquals(2, repository.snapshotReads)
    }

    /**
     * 목적: 이전 조회가 새 입력 상태를 덮어쓰지 않는다.
     * 조건: 저장 확정 후 snapshot 응답이 gate에서 대기한다.
     * 절차: StartNew 후 gate를 해제한다.
     * 기대: 편집 가능·새 빈 이름·조회 전 상태를 유지한다.
     */
    @Test fun lateReadCannotOverwriteNewDraft() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        repository.beforeSnapshot = { gate.await() }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        vm.onAction(AccountRegistrationAction.StartNew)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.EDITING, vm.uiState.value.phase)
        assertEquals("", vm.uiState.value.draft.name)
        assertEquals(AccountReadPhase.NOT_REQUESTED, vm.uiState.value.readPhase)
    }

    /** 새 등록을 명시하면 같은 이름이어도 별도 ID의 새 계좌가 된다. */
    @Test fun separateSameNameIntentGetsNewIdentifiers() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.StartNew)
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
    @Test fun cancelledSubmissionRestoresSameIntent() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        repository.beforeWrite = { try { gate.await() } finally { finished = true } }
        val saved = handle()
        val vm = ready(repository, saved)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        val recovered = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        stores.first().clear()
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(AccountSavePhase.RETRY_AVAILABLE, vm.uiState.value.phase)
        val restored = viewModel(repository, recovered)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.RETRY_AVAILABLE, restored.uiState.value.phase)
        assertEquals(1, repository.requests.size)
        assertEquals(1, repository.receiptReads)
        repository.beforeWrite = {}
        restored.onAction(AccountRegistrationAction.Retry)
        advanceUntilIdle()
        assertEquals(AccountSavePhase.SAVED, restored.uiState.value.phase)
        assertEquals(repository.requests[0], repository.requests[1])
    }

    /**
     * 목적: 성공 제출의 재진입은 같은 계좌로 복귀한다.
     * 조건: 성공한 제출 스냅샷을 보관한다.
     * 절차: 새 ViewModel 초기화를 완료한다.
     * 기대: 같은 계좌 ID·SAVED이며 저장 호출은 한 번이다.
     */
    @Test fun committedSubmissionReentryOnlyReads() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        val vm = ready(repository, saved)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        val restored = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(vm.uiState.value.savedAccountId, restored.uiState.value.savedAccountId)
        assertEquals(AccountSavePhase.SAVED, restored.uiState.value.phase)
        assertEquals(1, repository.requests.size)
    }

    /** 손상되거나 다른 저장소 세대의 제출을 재생성·실행하지 않는다. */
    @Test fun corruptOrCrossGenerationRecoveryIsBlocked() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle().apply {
            this[AccountRegistrationViewModel.SUBMISSION] = arrayListOf("broken")
        }
        val vm = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, vm.uiState.value.problem)
        assertEquals(0, repository.initializations)
        val valid = handle()
        val original = ready(repository, valid)
        original.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        valid[AccountRegistrationViewModel.STORAGE_GENERATION] = "00000000-0000-0000-0000-000000000003"
        val changed = viewModel(repository, valid)
        advanceUntilIdle()
        assertEquals(AccountProblem.INVALID_RECOVERY, changed.uiState.value.problem)
        assertEquals(1, repository.requests.size)
    }

    /**
     * 목적: 충돌·다른 제출 영수증을 성공으로 표시하지 않는다.
     * 조건: DUPLICATE_REQUEST 또는 다른 operationId를 반환한다.
     * 절차: 각각의 제출을 완료한다.
     * 기대: UNAVAILABLE·각 오류 코드·성공한 계좌 ID 없음이다.
     */
    @Test fun conflictAndMismatchedReceiptNeverShowSuccess() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            beforeWrite = { throw LedgerConflictException(LedgerConflict.DUPLICATE_REQUEST) }
        }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.CONFLICT, vm.uiState.value.problem)
        assertEquals(AccountSavePhase.UNAVAILABLE, vm.uiState.value.phase)
        assertEquals(0, repository.receiptReads)
        val other = FakeRepository().apply { mismatchReceipt = true }
        val otherVm = ready(other)
        otherVm.onAction(AccountRegistrationAction.Submit)
        advanceUntilIdle()
        assertEquals(AccountProblem.RESULT_MISMATCH, otherVm.uiState.value.problem)
        assertNull(otherVm.uiState.value.savedAccountId)
    }

    /**
     * 목적: 초기화 오류를 빈 결과로 위장하지 않는다.
     * 조건: 첫 initialize가 실패한다.
     * 절차: 명시 Retry로 초기화만 재실행한다.
     * 기대: 편집 가능·초기화 두 번·금융 쓰기 없음이다.
     */
    @Test fun initializationFailureHasExplicitRetry() = runTest(dispatcher) {
        val repository = FakeRepository().apply { beforeInitialize = { throw IOException() } }
        val vm = viewModel(repository)
        advanceUntilIdle()
        assertEquals(AccountProblem.INITIALIZATION_FAILED, vm.uiState.value.problem)
        repository.beforeInitialize = {}
        vm.onAction(AccountRegistrationAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editable)
        assertEquals(2, repository.initializations)
        assertTrue(repository.requests.isEmpty())
    }

    /**
     * 목적: 저장 확정 뒤 조회 취소도 성공을 미확정 상태로 되돌리지 않는다.
     * 조건: snapshot이 gate에서 대기한다.
     * 절차: 저장 확정 확인 후 ViewModelStore를 지우고 취소 정리를 완료한다.
     * 기대: SAVED·계좌 ID 유지·조회만 FAILED이며 금융 쓰기는 한 번이다.
     */
    @Test fun readCancellationKeepsConfirmedSave() = runTest(dispatcher) {
        val repository = FakeRepository()
        val gate = CompletableDeferred<Unit>()
        var finished = false
        repository.beforeSnapshot = { try { gate.await() } finally { finished = true } }
        val vm = ready(repository)
        vm.onAction(AccountRegistrationAction.Submit)
        runCurrent()
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        stores.first().clear()
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(AccountSavePhase.SAVED, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.savedAccountId)
        assertEquals(AccountReadPhase.FAILED, vm.uiState.value.readPhase)
        assertEquals(1, repository.requests.size)
    }

    /** 제출하지 않은 이름은 새 handle에서 복원되지 않아 영속 초안 지원으로 오인하지 않는다. */
    @Test fun unsubmittedDraftIsNotPromisedAsDurable() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = handle()
        ready(repository, saved)
        assertFalse(saved.contains(AccountRegistrationViewModel.SUBMISSION))
        val restored = viewModel(repository, saved)
        advanceUntilIdle()
        assertEquals("", restored.uiState.value.draft.name)
        assertTrue(repository.requests.isEmpty())
    }

    private fun handle() = SavedStateHandle(mapOf(
        AccountRegistrationViewModel.LEDGER_ID to context.ledgerId,
        AccountRegistrationViewModel.STORAGE_GENERATION to context.storageGeneration,
    ))
    private fun viewModel(repository: FakeRepository, saved: SavedStateHandle = handle()) =
        AccountRegistrationViewModel(repository, clock, saved).also { vm ->
            stores += ViewModelStore().also { it.put("account", vm) }
        }
    private fun ready(repository: FakeRepository, saved: SavedStateHandle = handle()): AccountRegistrationViewModel {
        val vm = viewModel(repository, saved)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onAction(AccountRegistrationAction.NameChanged("합성 계좌"))
        return vm
    }
}

private data class AccountRequest(val context: LedgerContext, val operationId: String, val value: CreateAccount)

private class FakeRepository : LedgerRepository {
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

    override suspend fun initialize(context: LedgerContext) { initializations++; beforeInitialize() }
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt {
        requests += AccountRequest(context, operationId, value)
        beforeWrite()
        val receipt = receipts.getOrPut(operationId) {
            accounts[value.id] = LedgerAccount(value.id, value.name, value.nature, AccountStatus.ACTIVE, 1,
                BalanceAnchor(value.anchorId, value.anchorDate, null, null, value.unknownReason))
            WriteReceipt(operationId, 2, listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1)))
        }
        if (loseResponse) throw IOException()
        return if (mismatchReceipt) receipt.copy(operationId = "other") else receipt
    }
    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? {
        receiptReads++
        beforeReceipt()
        return receipts[operationId]
    }
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        snapshotReads++
        beforeSnapshot()
        return LedgerSnapshot(2, accounts.values.toList(), emptyList(), accounts.keys.associateWith { null }, 0, 0)
    }
    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt =
        error("계좌 등록에서 거래 쓰기를 호출하면 안 됩니다")
    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> =
        error("계좌 등록에서 불투명한 다른 제출을 실행하면 안 됩니다")
    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt =
        error("계좌 등록은 캡처한 typed 요청만 재시도합니다")
}
