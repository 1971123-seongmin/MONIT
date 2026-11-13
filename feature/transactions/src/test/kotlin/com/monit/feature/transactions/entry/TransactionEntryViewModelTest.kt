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
    private val route = TransactionEntryDestination("form-1", TransactionEntryKind.EXPENSE, "2026-11-03", com.monit.feature.transactions.navigation.TransactionEntryOrigin.HOME)
    private val store = ViewModelStore()
    private var next = 0

    /** task context가 없는 초안 ID만으로 현재 장부의 새 편집을 시작하지 않는다. */
    @Test fun partialDraftReferenceCannotBecomeNewCurrentForm() = runTest(dispatcher) {
        val repo = EntryRepository()
        val saved = SavedStateHandle(mapOf(TransactionEntryViewModel.DRAFT_ID to id(74)))
        val model = vm(repo, saved)
        model.bind(context, route)
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, model.state.value.phase)
        assertEquals(0, repo.drafts.saves)
        assertTrue(repo.writes.isEmpty())
    }

    /** 새 SavedState의 새 폼도 Room 계약 대역의 원문을 명시 선택한 뒤에만 복구한다. */
    @Test fun durableRawDraftNeedsExplicitRecoveryInNewForm() = runTest(dispatcher) {
        val repo = EntryRepository()
        val first = ready(repo)
        first.edit { copy(amount = " 00.불완전 ", date = "2026-02-30", memo = " 원문\n ") }
        advanceUntilIdle()
        store.clear()
        val recovered = vm(repo)
        recovered.bind(context, route.copy(entryId = "new-form"))
        advanceUntilIdle()
        assertEquals(com.monit.core.common.draft.DraftEditorPhase.CHOOSING, recovered.state.value.draftPersistence?.phase)
        assertEquals("", recovered.state.value.ui.amount)
        val choice = assertNotNull(recovered.state.value.draftPersistence).choices.single()
        recovered.restoreDraft(choice)
        advanceUntilIdle()
        assertEquals(" 00.불완전 ", recovered.state.value.ui.amount)
        assertEquals("2026-02-30", recovered.state.value.ui.date)
        assertEquals(" 원문\n ", recovered.state.value.ui.memo)
        assertEquals(id(10), recovered.state.value.ui.debitAccountId)
        assertTrue(repo.writes.isEmpty())
    }

    /** 홈 초안은 거래 탭에서 자동 적용하지 않으며 새 입력은 원래 초안을 지우지 않는다. */
    @Test fun otherOriginDraftIsNotAppliedOrDeletedByNewInput() = runTest(dispatcher) {
        val repo = EntryRepository()
        ready(repo).edit { copy(memo = "홈 원문") }
        advanceUntilIdle()
        store.clear()
        val recovered = vm(repo)
        recovered.bind(context, route.copy(entryId = "other-form", origin = com.monit.feature.transactions.navigation.TransactionEntryOrigin.TRANSACTIONS))
        advanceUntilIdle()
        val choice = assertNotNull(recovered.state.value.draftPersistence).choices.single()
        assertFalse(recovered.matchesDraft(choice))
        recovered.restoreDraft(choice)
        advanceUntilIdle()
        assertEquals(com.monit.core.common.draft.DraftEditorPhase.CHOOSING, recovered.state.value.draftPersistence?.phase)
        recovered.newDraft()
        advanceUntilIdle()
        recovered.edit { copy(memo = "거래 탭 새 원문") }
        advanceUntilIdle()
        assertEquals(2, repo.drafts.drafts(context).size)
        assertEquals(DraftSnapshot.Editable(choice), repo.drafts.draft(context, choice.reference.id))
    }

    /** 제출 전 flush 실패는 operation/금융 쓰기를 만들지 않고 입력과 마지막 저장본을 유지한다. */
    @Test fun failedFlushBlocksFinancialSubmissionUntilExplicitRetry() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        repo.drafts.failSave = true
        model.submit()
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertEquals(EntryPhase.EDITING, model.state.value.phase)
        assertTrue(assertNotNull(model.state.value.draftPersistence).failed)
        assertEquals("2000", model.state.value.ui.amount)
        repo.drafts.failSave = false
        model.submit()
        advanceUntilIdle()
        assertEquals(1, repo.writes.size)
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
    }

    /** 계좌 추가 callback은 부모 초안의 요청/슬롯 commit 확인 후에만 호출한다. */
    @Test fun registrationNavigationRequiresCommittedParentCaller() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        model.edit { copy(kind = TransactionKind.TRANSFER, memo = "반대편 보존") }
        model.choose(AccountSlot.CREDIT)
        advanceUntilIdle()
        var captured: Triple<String, String, DraftSlot>? = null
        repo.drafts.failSave = true
        model.openRegistration { request, parent, slot -> captured = Triple(request, parent, slot) }
        advanceUntilIdle()
        assertNull(captured)
        repo.drafts.failSave = false
        model.openRegistration { request, parent, slot -> captured = Triple(request, parent, slot) }
        advanceUntilIdle()
        val caller = assertNotNull(captured)
        val parent = (repo.drafts.draft(context, caller.second) as DraftSnapshot.Editable).draft
        val content = parent.content as DraftContent.Transaction
        assertEquals(caller.first, content.selectionRequestId)
        assertEquals(DraftSlot.CREDIT, content.selectionSlot)
        assertEquals(id(10), content.debitAccountId)
        assertEquals("반대편 보존", content.memo)
        assertTrue(repo.writes.isEmpty())
    }

    /** 보관 이탈 실패는 화면을 닫지 않으며 명시 폐기 성공만 늦은 저장을 막고 닫는다. */
    @Test fun exitRequiresSuccessfulFlushOrExplicitDiscard() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = ready(repo)
        var exits = 0
        repo.drafts.failSave = true
        model.leave { exits++ }
        advanceUntilIdle()
        assertEquals(0, exits)
        repo.drafts.failSave = false
        model.leave(discard = true) { exits++ }
        advanceUntilIdle()
        assertEquals(1, exits)
        model.edit { copy(memo = "늦은 입력") }
        advanceUntilIdle()
        assertTrue(repo.drafts.drafts(context).isEmpty())
        assertTrue(repo.writes.isEmpty())
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun vm(repo: EntryRepository, saved: SavedStateHandle = SavedStateHandle()) = TransactionEntryViewModel(repo, clock, saved, repo.drafts).also { store.put("entry-${next++}", it) }

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

    /** 확정 거절은 성공으로 표시하지 않고 확인 후 종료하며 재생성에서도 같은 결과만 조회한다. */
    @Test fun terminalRejectionIsExplicitAndRecreationNeverReplays() = runTest(dispatcher) {
        val repo = existingRepo().apply { rejectChanges = true }
        val saved = SavedStateHandle()
        val model = vm(repo, saved)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        model.beginEdit()
        advanceUntilIdle()
        model.edit { copy(memo = "충돌 입력") }
        model.submit()
        advanceUntilIdle()
        assertEquals(EntryPhase.REJECTED, model.state.value.phase)
        assertEquals("기존 메모", repo.current!!.memo)
        store.clear()
        val restored = vm(repo, saved)
        restored.bind(context, existingRoute())
        advanceUntilIdle()
        assertEquals(EntryPhase.REJECTED, restored.state.value.phase)
        restored.check(allowWrite = true)
        advanceUntilIdle()
        assertEquals(1, repo.changes.size)
        var left = false
        restored.finish { left = true }
        advanceUntilIdle()
        assertTrue(left)
    }

    /** 조회 실패 후 명시 재조회는 기존 값을 다시 읽으며 빈 신규 폼으로 바꾸지 않는다. */
    @Test fun failedExistingLoadCanExplicitlyRetry() = runTest(dispatcher) {
        val repo = existingRepo().apply { failSnapshot = true }
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, model.state.value.phase)
        repo.failSnapshot = false
        model.reload()
        advanceUntilIdle()
        assertEquals(EntryPhase.EDITING, model.state.value.phase)
        assertTrue(model.state.value.viewing)
        assertEquals("100", model.state.value.ui.amount)
        assertTrue(repo.changes.isEmpty())
    }

    private fun existingRepo() = EntryRepository().apply {
        current = LedgerTransaction(id(30), TransactionKind.TRANSFER, LocalDate.of(2026, 11, 3), 100, id(10), id(11), AccountNature.GENERAL, AccountNature.GENERAL, "기존 메모", 4, 2)
    }
    private fun existingRoute(entry: String = "existing") = route.copy(entryId = entry, transactionId = id(30))

    /** 상세 조회와 뒤로 이동은 새 초안·금융 명령을 만들지 않는다. */
    @Test fun existingDetailLoadsWithoutDraftOrFinancialWrites() = runTest(dispatcher) {
        val repo = existingRepo()
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        assertTrue(model.state.value.viewing)
        assertEquals("100", model.state.value.ui.amount)
        assertEquals("기존 메모", model.state.value.ui.memo)
        assertNull(model.state.value.draftPersistence)
        model.edit { copy(amount = "999") }
        model.submit()
        var left = false
        model.leave { left = true }
        assertTrue(left)
        assertEquals("100", model.state.value.ui.amount)
        assertEquals(0, repo.drafts.saves)
        assertTrue(repo.changes.isEmpty())
    }

    /** 명시 수정 후 기존 ID/revision으로 저장하고 새 거래 생성은 호출하지 않는다. */
    @Test fun explicitEditUsesExistingIdRevisionAndConsumesDraft() = runTest(dispatcher) {
        val repo = existingRepo()
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        model.beginEdit()
        advanceUntilIdle()
        model.edit { copy(amount = "250", memo = "수정 메모") }
        model.submit()
        model.submit()
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
        val change = repo.changes.single().second
        assertEquals(id(30), change.value.id)
        assertEquals(4, change.expectedRevision)
        assertEquals(250, change.value.amount)
        assertTrue(repo.writes.isEmpty())
        assertTrue(repo.drafts.drafts(context).isEmpty())
    }

    /** 수정 중 불완전 원문은 거래·revision에 묶인 초안으로 명시 복구한다. */
    @Test fun existingEditRawDraftNeedsMatchingTargetAndExplicitRestore() = runTest(dispatcher) {
        val repo = existingRepo()
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        model.beginEdit()
        advanceUntilIdle()
        model.edit { copy(amount = " 미완성 ", date = "2026-02-30", memo = " 원문\n") }
        advanceUntilIdle()
        store.clear()
        val nextModel = vm(repo)
        nextModel.bind(context, existingRoute("again"))
        advanceUntilIdle()
        nextModel.beginEdit()
        advanceUntilIdle()
        val choice = nextModel.state.value.draftPersistence!!.choices.single()
        assertEquals(id(30), choice.scope.transactionId)
        assertEquals(4, choice.scope.transactionRevision)
        assertEquals(com.monit.core.common.draft.DraftEditorPhase.CHOOSING, nextModel.state.value.draftPersistence!!.phase)
        nextModel.restoreDraft(choice)
        advanceUntilIdle()
        assertEquals(" 미완성 ", nextModel.state.value.ui.amount)
        assertEquals("2026-02-30", nextModel.state.value.ui.date)
        assertTrue(repo.changes.isEmpty())
    }

    /** 저장 응답 유실 뒤 SavedState를 복원해 기존 영수증만 확인한다. */
    @Test fun editLostReplyAndRecreationDoNotSubmitAgain() = runTest(dispatcher) {
        val repo = existingRepo().apply { loseReply = true }
        val saved = SavedStateHandle()
        val model = vm(repo, saved)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        model.beginEdit()
        advanceUntilIdle()
        model.edit { copy(memo = "저장") }
        model.submit()
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
        store.clear()
        val restored = vm(repo, saved)
        restored.bind(context, existingRoute())
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, restored.state.value.phase)
        assertEquals(1, repo.changes.size)
        var returned = false
        restored.finish { returned = true }
        advanceUntilIdle()
        assertTrue(returned)
        assertEquals(listOf(repo.changes.single().first), repo.finished)
    }

    /**
     * 목적: 삭제 연타·응답 유실에서 중복 제출과 거짓 실패를 방지한다.
     * 조건: 첫 저장을 suspend gate에서 멈추고 commit 뒤 응답만 실패시키는 대역이다.
     * 절차: delete 두 번 후 runCurrent로 저장 중 상태를 확인하고 gate를 연다.
     * 기대: 삭제 한 건·원래 revision을 제출하며 영수증 조회로 SAVED에 도달하고 초안은 생성하지 않는다.
     */
    @Test fun deleteDoubleTapAndLostReplyProduceSingleDeletion() = runTest(dispatcher) {
        val repo = existingRepo().apply { loseReply = true }
        val gate = CompletableDeferred<Unit>()
        repo.beforeWrite = { gate.await() }
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        model.delete()
        model.delete()
        runCurrent()
        assertEquals(EntryPhase.SAVING, model.state.value.phase)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(EntryPhase.SAVED, model.state.value.phase)
        assertTrue(model.state.value.deleting)
        assertEquals(DeleteTransaction(id(30), 4), repo.deletions.single().second)
        assertNull(repo.current)
        assertEquals(0, repo.drafts.saves)
    }

    /** 삭제된/없는 거래의 상세 진입은 새 입력으로 대체하지 않는다. */
    @Test fun missingExistingTransactionBlocksWithoutCreatingNewForm() = runTest(dispatcher) {
        val repo = EntryRepository()
        val model = vm(repo)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, model.state.value.phase)
        model.beginEdit()
        model.delete()
        model.submit()
        advanceUntilIdle()
        assertTrue(repo.changes.isEmpty())
        assertTrue(repo.deletions.isEmpty())
        assertEquals(0, repo.drafts.saves)
    }

    /** 다른 대상 route에 저장된 입력을 재사용하지 않는다. */
    @Test fun existingSavedStateCannotBindDifferentTarget() = runTest(dispatcher) {
        val repo = existingRepo()
        val saved = SavedStateHandle()
        val model = vm(repo, saved)
        model.bind(context, existingRoute())
        advanceUntilIdle()
        store.clear()
        val wrong = vm(repo, saved)
        wrong.bind(context, existingRoute().copy(transactionId = id(31)))
        advanceUntilIdle()
        assertEquals(EntryPhase.BLOCKED, wrong.state.value.phase)
        wrong.delete()
        wrong.submit()
        advanceUntilIdle()
        assertTrue(repo.changes.isEmpty())
        assertTrue(repo.deletions.isEmpty())
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
    val drafts = TestDraftRepository(this)
    val writes = mutableListOf<Pair<String, CreateTransaction>>()
    val finished = mutableListOf<String>()
    var rejectChanges = false
    private val rejections = mutableMapOf<String, TransactionRejection>()
    var current: LedgerTransaction? = null
    val changes = mutableListOf<Pair<String, UpdateTransaction>>()
    val deletions = mutableListOf<Pair<String, DeleteTransaction>>()
    var beforeWrite: suspend () -> Unit = {}
    var beforeSnapshot: suspend () -> Unit = {}
    var failBeforeCommit = false
    var loseReply = false
    var failSnapshot = false
    var mismatch = false
    var active = true
    var reads = 0
    private val receipts = mutableMapOf<String, WriteReceipt>()
    override suspend fun updateTransaction(context: LedgerContext, operationId: String, value: UpdateTransaction): WriteReceipt {
        changes += operationId to value
        beforeWrite()
        if (failBeforeCommit) throw IOException()
        if (rejectChanges) {
            val result = TransactionRejection(context, operationId, value.value.id, value.expectedRevision, value.expectedRevision + 1, null, "2026-11-12T00:00:00Z")
            rejections[operationId] = result
            throw TransactionRejectedException(result)
        }
        val row = requireNotNull(current)
        require(row.revision == value.expectedRevision)
        current = row.copy(amount = value.value.amount, memo = value.value.memo, revision = row.revision + 1)
        val receipt = WriteReceipt(operationId, 10, listOf(EntityRef("transactions", value.value.id, value.expectedRevision + 1)))
        receipts[operationId] = receipt
        if (loseReply) throw IOException()
        return receipt
    }
    override suspend fun deleteTransaction(context: LedgerContext, operationId: String, value: DeleteTransaction): WriteReceipt {
        deletions += operationId to value
        beforeWrite()
        if (failBeforeCommit) throw IOException()
        require(current?.revision == value.expectedRevision)
        current = null
        val receipt = WriteReceipt(operationId, 10, listOf(EntityRef("transactions", value.id, value.expectedRevision + 1)))
        receipts[operationId] = receipt
        if (loseReply) throw IOException()
        return receipt
    }

    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt {
        writes += operationId to value
        beforeWrite()
        if (failBeforeCommit) throw IOException()
        val receipt = receipts.getOrPut(operationId) { WriteReceipt(operationId, 1, listOf(EntityRef("transactions", if (mismatch) id(99) else value.id, 1))) }
        if (loseReply) throw IOException()
        return receipt
    }
    override suspend fun paymentRejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.PaymentRejection? = null
    override suspend fun rejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.TransactionRejection? = rejections[operationId]
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
        return LedgerSnapshot(1, accounts, listOfNotNull(current), emptyMap(), 0, 0)
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
