package com.monit.feature.transactions.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.core.model.*
import com.monit.feature.transactions.navigation.RegisteredAccountResult
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import com.monit.feature.transactions.navigation.TransactionEntryKind
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class TransactionEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:30:00Z"), ZoneOffset.UTC)
    private val context = LedgerContext(id(1), id(2))
    private val route = TransactionEntryDestination("form-1", TransactionEntryKind.EXPENSE, "2026-11-03")
    private val store = ViewModelStore()
    private var next = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun vm(repo: EntryRepository, saved: SavedStateHandle = SavedStateHandle()) = TransactionEntryViewModel(repo, clock, saved).also { store.put("entry-${next++}", it) }

    /** 최초 선택일만 사용하며 이후 route 재전달이 편집 중 날짜를 덮어쓰지 않는다. */
    @Test fun initialDateDoesNotOverwriteEditedForm() = runTest(dispatcher) {
        val model = vm(EntryRepository())
        model.bind(context, route)
        advanceUntilIdle()
        assertEquals("2026-11-03", model.state.value.ui.date)
        model.edit { copy(date = "2026-11-04", memo = "합성 메모") }
        model.bind(context, route.copy(initialDate = "2026-11-05"))
        assertEquals("2026-11-04", model.state.value.ui.date)
    }

    /** 미래·존재하지 않는 일자, 0원·소수·범위 초과는 입력을 유지하고 제출하지 않는다. */
    @Test fun invalidInputsDoNotCreatePending() = runTest(dispatcher) {
        for ((amount, date) in listOf("0" to "2026-11-03", "1.5" to "2026-11-03", "1000000000000" to "2026-11-03", "2000" to "2026-11-13", "2000" to "2026-02-30")) {
            val repo = EntryRepository()
            val model = ready(repo)
            model.edit { copy(amount = amount, date = date) }
            model.submit()
            advanceUntilIdle()
            assertTrue(repo.writes.isEmpty())
            assertEquals(amount, model.state.value.ui.amount)
            assertEquals(EntryPhase.EDITING, model.state.value.phase)
            assertNotNull(model.state.value.ui.error)
        }
    }

    /**
     * 목적: 처리 예약 직후 연타와 편집을 차단한다.
     * 조건: Main TestDispatcher와 writer 진입 gate를 쓴다.
     * 절차: submit 두 번 → writer 대기 → 편집 시도 → 완료 순서다.
     * 기대: 한 요청, 원래 내용, 확인된 성공만 노출한다.
     */
    @Test fun doubleSubmitAndEditingWhileSavingAreBlocked() = runTest(dispatcher) {
        val repo = EntryRepository()
        val gate = CompletableDeferred<Unit>()
        repo.beforeWrite = { gate.await() }
        val model = ready(repo)
        model.submit()
        model.submit()
        model.edit { copy(amount = "9999") }
        runCurrent()
        assertEquals(1, repo.writes.size)
        assertEquals("2000", model.state.value.ui.amount)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
        assertTrue(repo.finished.isEmpty())
    }

    /** commit 후 응답 유실은 영수증 조회로 성공을 확인하며 writer를 다시 호출하지 않는다. */
    @Test fun lostReplyReadsExistingReceipt() = runTest(dispatcher) {
        val repo = EntryRepository().apply { loseReply = true }
        val model = ready(repo)
        model.submit()
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
        assertEquals(1, repo.writes.size)
        assertEquals(1, repo.reads)
    }

    /**
     * 목적: 결과 불명의 자동 재저장을 막는다.
     * 조건: 최초 writer가 commit 전에 실패하고 영수증은 없다.
     * 절차: 제출 → 조회만 재확인 → 명시 재시도 순서다.
     * 기대: 최초와 같은 ID·값으로만 두 번째 writer를 호출한다.
     */
    @Test fun explicitRetryKeepsSubmissionIdentity() = runTest(dispatcher) {
        val repo = EntryRepository().apply { failBeforeCommit = true }
        val model = ready(repo)
        model.submit()
        advanceUntilIdle()
        assertEquals(EntryPhase.UNCONFIRMED, model.state.value.phase)
        model.check()
        advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        repo.failBeforeCommit = false
        model.check(true)
        advanceUntilIdle()
        assertEquals(2, repo.writes.size)
        assertEquals(repo.writes[0], repo.writes[1])
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
    }

    /** saved state에 캡처된 제출 재생성은 조회만 수행한다. 전체 영속 초안 시험이 아니다. */
    @Test fun restoredSubmissionOnlyChecksResult() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val repo = EntryRepository().apply { failBeforeCommit = true }
        val model = ready(repo, saved)
        model.submit()
        advanceUntilIdle()
        val restored = vm(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        restored.bind(context, route)
        advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertEquals(EntryPhase.UNCONFIRMED, restored.state.value.phase)
        assertEquals("2000", restored.state.value.ui.amount)
    }

    /** 다른 세대에서 저장된 캡처는 재조회 버튼으로도 신규 편집/저장으로 전환하지 않는다. */
    @Test fun foreignGenerationCannotBeBypassedByReload() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val repo = EntryRepository()
        ready(repo, saved)
        val restored = vm(repo, saved)
        restored.bind(context.copy(storageGeneration = id(90)), route)
        restored.reload()
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, restored.state.value.phase)
        assertFalse(restored.state.value.canReload)
        assertTrue(repo.writes.isEmpty())
    }

    /**
     * 목적: 새 계좌 반환은 후보로만 받으며 반대편 이체 계좌를 보존한다.
     * 조건: 현재 요청 ID와 일치하는 활성 계좌 반환이다.
     * 절차: 받는 계좌 등록 → 잘못된 요청 거부 → 올바른 후보 → 명시 적용.
     * 기대: 적용 전 credit은 null, 적용 후 선택 계좌이며 debit/금액/메모는 같다.
     */
    @Test fun registrationReturnRequiresExplicitApply() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        model.edit { copy(kind = TransactionKind.TRANSFER, memo = "그대로 유지") }
        model.choose(AccountSlot.CREDIT)
        advanceUntilIdle()
        val request = assertNotNull(model.beginRegistration())
        assertFalse(model.receive(RegisteredAccountResult("old-request", id(11))))
        assertTrue(model.receive(RegisteredAccountResult(request, id(11))))
        advanceUntilIdle()
        assertNull(model.state.value.ui.creditAccountId)
        assertEquals(id(11), model.state.value.ui.candidateAccountId)
        model.applyCandidate()
        advanceUntilIdle()
        assertEquals(id(11), model.state.value.ui.creditAccountId)
        assertEquals(id(10), model.state.value.ui.debitAccountId)
        assertEquals("2000", model.state.value.ui.amount)
        assertEquals("그대로 유지", model.state.value.ui.memo)
    }

    /** 후보 표시 뒤 보관된 계좌와 같은 이체 계좌는 명시 적용에서도 거부한다. */
    @Test fun archivedAndOppositeAccountsCannotBeApplied() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        model.edit { copy(kind = TransactionKind.TRANSFER) }
        model.choose(AccountSlot.CREDIT)
        advanceUntilIdle()
        model.selectCandidate(id(10))
        model.applyCandidate()
        advanceUntilIdle()
        assertNull(model.state.value.ui.creditAccountId)
        model.selectCandidate(id(11))
        repo.active = false
        model.applyCandidate()
        advanceUntilIdle()
        assertNull(model.state.value.ui.creditAccountId)
        assertNotNull(model.state.value.ui.error)
    }

    /** 계좌 조회 실패가 모달 안에 갇히지 않으며 닫기·재조회 후 입력을 이어갈 수 있다. */
    @Test fun pickerReadFailureCanBeDismissedAndRetried() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        repo.failSnapshot = true
        model.choose(AccountSlot.DEBIT)
        advanceUntilIdle()
        assertTrue(model.state.value.canReload)
        model.dismissPicker()
        assertNull(model.state.value.slot)
        repo.failSnapshot = false
        model.reload()
        advanceUntilIdle()
        assertEquals(EntryPhase.EDITING, model.state.value.phase)
        assertEquals("2000", model.state.value.ui.amount)
    }

    /** context 변경 후 늦은 정상 조회가 차단 상태를 편집 가능으로 바꾸지 않는다. */
    @Test fun lateReadCannotUnlockForeignContext() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = EntryRepository().apply { beforeSnapshot = { gate.await() } }
        val model = vm(repo)
        model.bind(context, route)
        runCurrent()
        model.bind(context.copy(storageGeneration = id(99)), route)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, model.state.value.phase)
    }

    /** 영수증 대상이 다른 경우 성공/참조 종료/목록 복귀를 허용하지 않는다. */
    @Test fun mismatchedReceiptIsBlocked() = runTest(dispatcher) {
        val repo = EntryRepository().apply { mismatch = true }
        val model = ready(repo)
        model.submit()
        advanceUntilIdle()
        var returned = false
        model.finish { returned = true }
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, model.state.value.phase)
        assertFalse(returned)
        assertTrue(repo.finished.isEmpty())
    }

    /** 성공한 뒤 사용자의 확인만 선택한 참조를 종료하며 writer를 다시 호출하지 않는다. */
    @Test fun successNeedsExplicitFinish() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        model.submit()
        advanceUntilIdle()
        var returned = false
        model.finish { returned = true }
        advanceUntilIdle()
        assertTrue(returned)
        assertEquals(listOf(repo.writes.single().first), repo.finished)
        assertEquals(1, repo.writes.size)
    }

    /** 수입·지출·이체는 같은 금액이라도 각 방향의 계좌만 담은 별도 거래로 제출한다. */
    @Test fun allKindsCaptureTheRequiredAccountDirections() = runTest(dispatcher) {
        for (kind in TransactionKind.entries) {
            val repo = EntryRepository()
            val model = ready(repo)
            model.edit { copy(kind = kind, debitAccountId = id(10), creditAccountId = id(11)) }
            model.submit()
            advanceUntilIdle()
            val request = repo.writes.single().second
            assertEquals(kind, request.kind)
            assertEquals(if (kind == TransactionKind.INCOME) null else id(10), request.debitAccountId)
            assertEquals(if (kind == TransactionKind.EXPENSE) null else id(11), request.creditAccountId)
            assertEquals(EntryPhase.SAVED, model.state.value.phase)
        }
    }

    private suspend fun ready(repo: EntryRepository, saved: SavedStateHandle = SavedStateHandle()): TransactionEntryViewModel {
        val model = vm(repo, saved)
        model.bind(context, route)
        dispatcher.scheduler.advanceUntilIdle()
        model.edit { copy(amount = "2000", debitAccountId = id(10)) }
        return model
    }
}

private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

private class EntryRepository : LedgerRepository {
    val writes = mutableListOf<Pair<String, CreateTransaction>>()
    val finished = mutableListOf<String>()
    var beforeWrite: suspend () -> Unit = {}
    var beforeSnapshot: suspend () -> Unit = {}
    var failBeforeCommit = false
    var loseReply = false
    var failSnapshot = false
    var mismatch = false
    var active = true
    var reads = 0
    private val receipts = mutableMapOf<String, WriteReceipt>()
    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt {
        writes += operationId to value
        beforeWrite()
        if (failBeforeCommit) throw IOException()
        val receipt = receipts.getOrPut(operationId) { WriteReceipt(operationId, 1, listOf(EntityRef("transactions", if (mismatch) id(99) else value.id, 1))) }
        if (loseReply) throw IOException()
        return receipt
    }
    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? {
        reads++
        return receipts[operationId]
    }
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        beforeSnapshot()
        if (failSnapshot) throw IOException()
        val accounts = listOf(10, 11).map {
            LedgerAccount(
                id(it),
                "합성 계좌 $it",
                AccountNature.GENERAL,
                if (active) AccountStatus.ACTIVE else AccountStatus.ARCHIVED,
                1,
                BalanceAnchor(id(it + 10), LocalDate.of(2026, 1, 1), null, null, "미확인"),
            )
        }
        return LedgerSnapshot(1, accounts, emptyList(), emptyMap(), 0, 0)
    }
    override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult {
        finished += operationId
        return FinishSubmissionRecoveryResult.Finished
    }
    override suspend fun openLedger(): LedgerEntryResult = error("준비된 장부만 사용")
    override suspend fun initialize(context: LedgerContext): Unit = error("입력은 장부 메타데이터를 생성하지 않음")
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt = error("계좌 등록 화면의 책임")
    override suspend fun submissionRecovery(context: LedgerContext): SubmissionRecoverySnapshot = error("앱 진입 경계의 책임")
    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot = error("불투명한 다른 제출 실행 금지")
    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = error("원문 노출 금지")
    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = error("현재 폼은 캡처된 동일 요청 사용")
}
