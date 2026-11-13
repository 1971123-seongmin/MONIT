package com.monit.app.entry

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.monit.app.query.QueryRepository
import com.monit.app.query.queryId
import com.monit.app.query.testContext
import com.monit.core.model.*
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
@Config(sdk = [28], application = Application::class)
class EntryRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun vm(repository: RecoveryRepository) = EntryRecoveryViewModel(repository, clock).also { store.put("recovery", it) }

    /** 기준액 확정 충돌은 명시 선택·결과 확인만 허용하고 자동 재시도나 새 기준액 치환을 하지 않는다. */
    @Test fun anchorConflictIsAcknowledgedWithoutRetryingFinance() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val outcome = AnchorRejection(testContext, queryId(12), queryId(32), 1, 2, Instant.parse("2026-11-12T00:00:00Z"))
        repo.items = listOf(SubmissionRecoveryItem.AnchorRejected(SubmissionReference(testContext, outcome.operationId), outcome)) + repo.items
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.finish()
        advanceUntilIdle()
        assertTrue(repo.finished.isEmpty())
        model.select(0)
        advanceUntilIdle()
        assertEquals(outcome, model.state.value.anchorRejection)
        assertFalse(model.state.value.resultAbsent)
        model.retry()
        advanceUntilIdle()
        assertTrue(repo.retried.isEmpty())
        model.finish()
        advanceUntilIdle()
        assertEquals(listOf(outcome.operationId), repo.finished)
        assertNull(model.state.value.anchorRejection)
        assertEquals(2, model.state.value.items.size)
    }

    /** 충돌 결과는 사용자가 선택·확인한 한 건만 종료하고 금융 재시도 없이 다른 참조를 보존한다. */
    @Test fun rejectedSubmissionNeedsExplicitConfirmationAndNeverRetries() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val outcome = TransactionRejection(testContext, queryId(12), queryId(32), 1, 2, null, "2026-11-12T00:00:00Z")
        repo.rejections[outcome.operationId] = outcome
        repo.items = listOf(SubmissionRecoveryItem.Rejected(SubmissionReference(testContext, outcome.operationId), outcome)) + repo.items
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.finish()
        advanceUntilIdle()
        assertTrue(repo.finished.isEmpty())
        model.select(0)
        advanceUntilIdle()
        assertEquals(outcome, model.state.value.rejection)
        assertNull(model.state.value.receipt)
        assertFalse(model.state.value.resultAbsent)
        model.retry()
        advanceUntilIdle()
        assertTrue(repo.retried.isEmpty())
        model.finish()
        advanceUntilIdle()
        assertEquals(listOf(outcome.operationId), repo.finished)
        assertEquals(2, model.state.value.items.size)
        assertNull(model.state.value.rejection)
    }

    /** 최초 발견은 선택·일괄 재시도·참조 종료를 자동 실행하지 않는다. */
    @Test fun discoveryDoesNotExecuteAnySubmission() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        assertEquals(2, model.state.value.items.size)
        assertNull(model.state.value.selected)
        assertTrue(repo.retried.isEmpty())
        assertTrue(repo.finished.isEmpty())
        assertEquals(0, repo.receiptReads)
        assertFalse(model.state.value.ready)
    }

    /**
     * 목적: 복수 미확정 제출 중 사용자가 선택한 한 건만 처리한다.
     * 조건: 정상 typed pending 두 건과 결과 없음 조회다.
     * 절차: 두 번째 선택 → 결과 없음 확인 → 명시 재시도 → 명시 확인 종료.
     * 기대: 같은 두 번째 operation만 실행/종료하고 첫 번째 참조는 남는다.
     */
    @Test fun onlySelectedSubmissionCanRetryAndFinish() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.select(1)
        advanceUntilIdle()
        assertTrue(model.state.value.resultAbsent)
        assertTrue(repo.retried.isEmpty())
        model.retry()
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(queryId(11)), repo.retried)
        assertNotNull(model.state.value.receipt)
        assertTrue(repo.finished.isEmpty())
        model.finish()
        advanceUntilIdle()
        assertEquals(listOf(queryId(11)), repo.finished)
        assertEquals(1, model.state.value.items.size)
        assertFalse(model.state.value.ready)
    }

    /** 선택 후 commit된 결과가 있으면 재시도 버튼에서도 재저장하지 않고 기존 결과를 보여 준다. */
    @Test fun lateCommitPreventsRetryWriter() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.select(0)
        advanceUntilIdle()
        repo.receipts[queryId(10)] = WriteReceipt(queryId(10), 1, listOf(EntityRef("transactions", queryId(30), 1)))
        model.retry()
        advanceUntilIdle()
        assertTrue(repo.retried.isEmpty())
        assertEquals(queryId(10), model.state.value.receipt?.operationId)
    }

    /** 조회 실패·불명 상태에서는 결과 없음으로 추정하거나 재시도/종료하지 않는다. */
    @Test fun receiptReadFailureDoesNotPermitWrites() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { failReceipt = true }
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.select(0)
        advanceUntilIdle()
        model.retry()
        model.finish()
        advanceUntilIdle()
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.resultAbsent)
        assertTrue(repo.retried.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /**
     * 목적: 납입 확정 거절을 성공/미확정 재시도와 구분하고 선택한 결과 하나만 종료한다.
     * 조건: 기존 두 제출과 별도의 납입 거절 한 건이 발견된다.
     * 절차: 발견→명시 선택→retry 호출→명시 finish 순서로 가상 스케줄러를 진행한다.
     * 기대: 금융 재시도 0, paymentRejection만 표시, 선택한 ID만 종료하고 다른 두 참조를 보존한다.
     */
    @Test fun paymentRejectionNeedsExplicitAcknowledgementWithoutFinancialRetry() = runTest(dispatcher) {
        val repo = RecoveryRepository()
        val outcome = PaymentRejection(testContext, queryId(13), "payment_items", queryId(33), 1, 2, "2026-11-13T03:00:00Z")
        repo.paymentRejections[outcome.operationId] = outcome
        repo.items = listOf(SubmissionRecoveryItem.PaymentRejected(SubmissionReference(testContext, outcome.operationId), outcome)) + repo.items
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.finish()
        assertTrue(repo.finished.isEmpty())
        model.select(0)
        advanceUntilIdle()
        assertEquals(outcome, model.state.value.paymentRejection)
        assertNull(model.state.value.receipt)
        assertNull(model.state.value.rejection)
        assertFalse(model.state.value.resultAbsent)
        model.retry()
        advanceUntilIdle()
        assertTrue(repo.retried.isEmpty())
        model.finish()
        advanceUntilIdle()
        assertEquals(listOf(outcome.operationId), repo.finished)
        assertEquals(2, model.state.value.items.size)
    }

    /** 손상/세대 불일치 항목은 선택으로 실행 가능한 참조로 바꾸지 않는다. */
    @Test fun blockedItemCannotExecuteOrFinish() = runTest(dispatcher) {
        val repo = RecoveryRepository().apply { items = listOf(SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)) }
        val model = vm(repo)
        model.bind(testContext)
        advanceUntilIdle()
        model.select(0)
        model.retry()
        model.finish()
        advanceUntilIdle()
        assertNull(model.state.value.selected)
        assertTrue(repo.retried.isEmpty())
        assertTrue(repo.finished.isEmpty())
        assertFalse(model.state.value.ready)
    }

    /** 복구 조회가 정상 완료되어 비었을 때만 신규 입력을 허용한다. */
    @Test fun emptySuccessfulDiscoveryEntersForm() = runTest(dispatcher) {
        val model = vm(RecoveryRepository().apply { items = emptyList() })
        model.bind(testContext)
        assertFalse(model.state.value.ready)
        advanceUntilIdle()
        assertTrue(model.state.value.ready)
    }

    /** 첫 발견이 대기 중인 상태의 재조회 연타가 여러 응답 경합을 만들지 않는다. */
    @Test fun repeatedDiscoveryCannotRace() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = RecoveryRepository().apply { beforeDiscovery = { gate.await() } }
        val model = vm(repo)
        model.bind(testContext)
        model.reload()
        runCurrent()
        assertEquals(1, repo.discoveries)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, model.state.value.items.size)
    }
}

private class RecoveryRepository : LedgerRepository by QueryRepository() {
    var items: List<SubmissionRecoveryItem> = listOf(10, 11).map { number ->
        SubmissionRecoveryItem.Pending(
            SubmissionReference(testContext, queryId(number)),
            PendingRecoveryItem.TransactionCreation(
                queryId(number),
                CreateTransaction(
                    queryId(number + 20),
                    TransactionKind.EXPENSE,
                    LocalDate.of(2026, 11, 3),
                    2000,
                    debitAccountId = queryId(5),
                ),
            ),
        )
    }
    val rejections = mutableMapOf<String, TransactionRejection>()
    val paymentRejections = mutableMapOf<String, PaymentRejection>()
    override suspend fun paymentRejection(context: LedgerContext, operationId: String): PaymentRejection? = paymentRejections[operationId]
    override suspend fun rejection(context: LedgerContext, operationId: String): TransactionRejection? = rejections[operationId]
    val receipts = mutableMapOf<String, WriteReceipt>()
    val retried = mutableListOf<String>()
    val finished = mutableListOf<String>()
    var receiptReads = 0
    var failReceipt = false
    var discoveries = 0
    var beforeDiscovery: suspend () -> Unit = {}
    override suspend fun submissionRecovery(context: LedgerContext): SubmissionRecoverySnapshot {
        discoveries++
        beforeDiscovery()
        return SubmissionRecoverySnapshot(context, items)
    }
    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? {
        receiptReads++
        if (failReceipt) throw IOException()
        return receipts[operationId]
    }
    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt {
        retried += operationId
        return WriteReceipt(operationId, 1, listOf(EntityRef("transactions", queryId(30), 1))).also { receipts[operationId] = it }
    }
    override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult {
        finished += operationId
        items = items.filterNot { (it is SubmissionRecoveryItem.Pending && it.reference.operationId == operationId) || (it is SubmissionRecoveryItem.Rejected && it.reference.operationId == operationId) || (it is SubmissionRecoveryItem.PaymentRejected && it.reference.operationId == operationId) || (it is SubmissionRecoveryItem.AnchorRejected && it.reference.operationId == operationId) }
        return FinishSubmissionRecoveryResult.Finished
    }
}
