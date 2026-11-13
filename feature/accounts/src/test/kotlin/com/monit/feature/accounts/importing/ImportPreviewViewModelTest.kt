package com.monit.feature.accounts.importing

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.io.CancellableDocumentAccess
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.model.*
import java.time.LocalDate
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repository 경계의 VM 상태·취소·중복 방지를 시험한다. 실제 parser/금융 writer는 core:data의 파일 Room 시험으로 검증한다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ImportPreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val store = ViewModelStore()
    private var next = 0
    private val uri = Uri.parse("content://synthetic-import/source")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun model(repository: FakeImports, reader: ImportDocumentReader = ImportDocumentReader { _, _ -> byteArrayOf(7, 8) }, gate: LocalDataWorkGate = LocalDataWorkGate(), saved: SavedStateHandle = SavedStateHandle()) = ImportPreviewViewModel(repository, reader, contexts, gate, saved).also { store.put("import-${next++}", it) }

    private suspend fun timeoutRead(cleanupEntered: CompletableDeferred<Unit>, cleanupRelease: CompletableDeferred<Unit>): ByteArray = withTimeout(60_000) {
        CancellableDocumentAccess(contexts).access { _, _ ->
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    cleanupRelease.await()
                }
            }
        }
    }

    private fun TestScope.readyToApply(vm: ImportPreviewViewModel) {
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        advanceUntilIdle()
        vm.confirmTarget()
        advanceUntilIdle()
        vm.backupReturned(id(700))
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
        assertTrue(vm.state.value.review?.canApply == true)
    }

    /**
     * 목적: 첫 읽기 시간초과는 실제 child 정리가 끝난 뒤 재시도 가능한 실패가 된다.
     * 조건: 60초 timeout과 실제 CancellableDocumentAccess의 비협력 정리 gate를 같은 가상 스케줄러로 제어한다.
     * 절차: 읽기→60초 경계→정리 진입/새 선택 차단 확인→정리 해제→실패 확인→명시 재선택.
     * 기대: 정리 전 lease 유지, 종료 후 FAILED/lease 해제, 늦은 검토0·재선택 검토1·반영0이다. provider 즉시 종료 보장이 아니다.
     */
    @Test fun initialReadTimeoutWaitsForActualCleanupThenAllowsExplicitRetry() = runTest(dispatcher) {
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val repository = FakeImports()
        val gate = LocalDataWorkGate()
        var reads = 0
        val vm = model(repository, ImportDocumentReader { _, _ -> if (++reads == 1) timeoutRead(cleanupEntered, cleanupRelease) else byteArrayOf(7, 8) }, gate)
        vm.bind(context)
        advanceUntilIdle()
        try {
            vm.select(uri)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(cleanupEntered.isCompleted)
            assertTrue(gate.held)
            assertEquals(ImportPreviewPhase.LOADING, vm.state.value.phase)
            vm.select(uri)
            runCurrent()
            assertEquals(1, reads)
            cleanupRelease.complete(Unit)
            advanceUntilIdle()
            assertFalse(gate.held)
            assertEquals(ImportPreviewPhase.FAILED, vm.state.value.phase)
            assertNotNull(vm.state.value.problem)
            assertEquals(0, repository.reviews)
            vm.select(uri)
            advanceUntilIdle()
            assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
            assertEquals(2, reads)
            assertEquals(1, repository.reviews)
            assertEquals(0, repository.applies)
        } finally {
            cleanupRelease.complete(Unit)
        }
    }

    /**
     * 목적: 반영 전 재읽기 시간초과는 APPLYING을 남기지 않고 쓰기 없는 재검토로 돌아간다.
     * 조건: 첫 읽기는 성공하고 명시 apply의 재읽기만 60초에 timeout된다.
     * 절차: 검토/확인/백업→apply→가상60초→이전 확인 재실행 차단→명시 재검토.
     * 기대: STALE·lease 해제·batch 미보관·apply0이며 재검토 뒤에도 자동 apply0이다.
     */
    @Test fun preApplyReadTimeoutBecomesStaleWithoutCapturingOrReplayingBatch() = runTest(dispatcher) {
        var reads = 0
        val repository = FakeImports()
        val gate = LocalDataWorkGate()
        val saved = SavedStateHandle()
        val vm = model(
            repository,
            ImportDocumentReader { _, _ ->
                if (++reads == 2) withTimeout(60_000) { awaitCancellation() }
                byteArrayOf(7, 8)
            },
            gate,
            saved,
        )
        readyToApply(vm)
        vm.apply()
        runCurrent()
        assertEquals(ImportPreviewPhase.APPLYING, vm.state.value.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(ImportPreviewPhase.STALE, vm.state.value.phase)
        assertFalse(gate.held)
        assertNull(saved.get<String>("import-batch"))
        assertEquals(0, repository.applies)
        vm.apply()
        advanceUntilIdle()
        assertEquals(2, reads)
        vm.reviewAgain()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
        assertEquals(3, reads)
        assertEquals(0, repository.applies)
    }

    /**
     * 목적: batch 보관 뒤 시간초과는 결과를 추정하거나 자동 재실행하지 않는다.
     * 조건: 합성 Repository가 확정 결과를 보관한 뒤 응답을 30초 timeout까지 중단한다.
     * 절차: 명시 apply→가상30초→RECOVERY→apply 재호출 차단→명시 영속 조회.
     * 기대: 조회 전 결과 미표시, 조회 후 RESULT, batch 보존·실행1회다.
     */
    @Test fun capturedBatchTimeoutRequiresDurableLookupWithoutAutomaticReplay() = runTest(dispatcher) {
        val repository = FakeImports().apply { afterCommit = { withTimeout(30_000) { awaitCancellation() } } }
        val gate = LocalDataWorkGate()
        val saved = SavedStateHandle()
        val vm = model(repository, gate = gate, saved = saved)
        readyToApply(vm)
        vm.apply()
        runCurrent()
        val batch = checkNotNull(saved.get<String>("import-batch"))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(ImportPreviewPhase.RECOVERY, vm.state.value.phase)
        assertFalse(gate.held)
        assertNull(vm.state.value.result)
        assertEquals(batch, saved.get<String>("import-batch"))
        vm.apply()
        advanceUntilIdle()
        assertEquals(1, repository.applies)
        assertEquals(0, repository.attemptReads)
        vm.resolve()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.RESULT, vm.state.value.phase)
        assertEquals(1, repository.attemptReads)
        assertEquals(1, repository.applies)
    }

    /**
     * 목적: timeout 정리 중 장부가 바뀌면 이전 완료 callback이 BLOCKED를 덮지 않는다.
     * 조건: 실제 문서 접근 child의 비협력 정리 gate가 timeout 뒤에도 남아 있다.
     * 절차: 읽기→60초/정리 진입→다른 장부 bind→정리 해제→이전 읽기 재시도.
     * 기대: 실제 정리 전 lease 유지, 종료 후 BLOCKED 보존·추가 읽기/검토/반영0이다.
     */
    @Test fun invalidBindingDuringTimedOutCleanupPreservesBlockedState() = runTest(dispatcher) {
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val repository = FakeImports()
        val gate = LocalDataWorkGate()
        var reads = 0
        val vm = model(
            repository,
            ImportDocumentReader { _, _ ->
                reads++
                timeoutRead(cleanupEntered, cleanupRelease)
            },
            gate,
        )
        vm.bind(context)
        advanceUntilIdle()
        try {
            vm.select(uri)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(cleanupEntered.isCompleted)
            vm.bind(LedgerContext(id(90), id(91)))
            assertEquals(ImportPreviewPhase.BLOCKED, vm.state.value.phase)
            assertTrue(gate.held)
            cleanupRelease.complete(Unit)
            advanceUntilIdle()
            assertEquals(ImportPreviewPhase.BLOCKED, vm.state.value.phase)
            assertFalse(gate.held)
            vm.select(uri)
            advanceUntilIdle()
            assertEquals(1, reads)
            assertEquals(0, repository.reviews)
            assertEquals(0, repository.applies)
        } finally {
            cleanupRelease.complete(Unit)
        }
    }

    /** 검토/판정·백업 귀환만으로 반영하지 않고 마지막 사용자 행동에서만 한 번 실행한다. */
    @Test fun explicitApplyIsTheOnlyFinancialTriggerAndSavedStateContainsOnlyLookupIds() = runTest(dispatcher) {
        val repository = FakeImports()
        val saved = SavedStateHandle()
        val vm = model(repository, saved = saved)
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
        assertEquals(1, repository.reviews)
        assertEquals(0, repository.applies)
        vm.confirmTarget()
        advanceUntilIdle()
        vm.confirmRow("rows/0")
        advanceUntilIdle()
        vm.backupReturned(id(700))
        advanceUntilIdle()
        assertEquals(0, repository.applies)
        vm.apply()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.RESULT, vm.state.value.phase)
        assertEquals(1, repository.applies)
        assertEquals(1, vm.state.value.result?.createdTransactions)
        assertEquals(setOf("import-ledger", "import-generation", "import-batch"), saved.keys())
        vm.apply()
        advanceUntilIdle()
        assertEquals(1, repository.applies)
    }

    /** 읽기 실패는 빈 정상 검토로 바뀌지 않으며 Repository review도 호출하지 않는다. */
    @Test fun failedReadHasUnknownCountsAndNoReviewCall() = runTest(dispatcher) {
        val repository = FakeImports()
        val vm = model(repository, ImportDocumentReader { _, _ -> throw java.io.IOException("synthetic read failure") })
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.FAILED, vm.state.value.phase)
        assertEquals("확인 전", vm.state.value.ui.sourceRowCount)
        assertEquals(0, repository.reviews)
        assertEquals(0, repository.applies)
    }

    /** 목적: 취소 요청만으로 lease를 풀지 않는다.
     * 조건: reader가 NonCancellable gate에서 실제 종료하지 않는다.
     * 절차: 읽기 시작→취소→다른 파일 선택→gate 해제/완료→명시 재선택.
     * 기대: 종료 전 새 읽기0·STOPPING, 늦은 원문 review0, 종료 후 새 읽기/검토1이다.
     */
    @Test fun nonReturningReadPreventsNewWorkAndLateResultIsDiscarded() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val repository = FakeImports()
        var reads = 0
        val vm = model(
            repository,
            ImportDocumentReader { _, _ ->
                if (++reads == 1) withContext(NonCancellable) { release.await() }
                byteArrayOf(7, 8)
            },
        )
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        runCurrent()
        vm.requestCancel()
        vm.select(uri)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(ImportPreviewPhase.STOPPING, vm.state.value.phase)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, repository.reviews)
        assertEquals(ImportPreviewPhase.STALE, vm.state.value.phase)
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(2, reads)
        assertEquals(1, repository.reviews)
    }

    /** 목적: timeout 중 VM이 제거되어도 실제 파일 작업 종료 전 새 VM의 작업을 막는다.
     * 조건: 두 VM이 같은 singleton gate를 사용하며 첫 reader는 시간초과 뒤에도 정리 child가 남아 있다.
     * 절차: 첫 읽기→가상60초/정리 진입→store.clear→새 bind/대기 확인→정리 해제→새 VM의 명시 결과 조회.
     * 기대: 종료 전 pending 조회0·RECOVERY, 종료 후 조회1·EMPTY이며 제거된 VM의 상태/늦은 검토는 바뀌지 않는다.
     */
    @Test fun recreatedViewModelSharesActualCompletionLease() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val gate = LocalDataWorkGate()
        val firstRepository = FakeImports()
        val first = model(
            firstRepository,
            ImportDocumentReader { _, _ -> timeoutRead(cleanupEntered, release) },
            gate,
        )
        first.bind(context)
        advanceUntilIdle()
        try {
            first.select(uri)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(cleanupEntered.isCompleted)
            store.clear()
            val cleared = first.state.value
            val repository = FakeImports()
            val second = model(repository, gate = gate)
            second.bind(context)
            runCurrent()
            assertTrue(gate.held)
            assertEquals(0, repository.pendingReads)
            assertEquals(ImportPreviewPhase.RECOVERY, second.state.value.phase)
            release.complete(Unit)
            advanceUntilIdle()
            assertFalse(gate.held)
            assertEquals(cleared, first.state.value)
            assertEquals(0, firstRepository.reviews)
            assertEquals(0, repository.pendingReads)
            second.resolve()
            advanceUntilIdle()
            assertEquals(1, repository.pendingReads)
            assertEquals(ImportPreviewPhase.EMPTY, second.state.value.phase)
        } finally {
            release.complete(Unit)
        }
    }

    /** 저장된 batch ID로 결과만 조회하며 프로세스 재생성에서 apply를 다시 호출하지 않는다. */
    @Test fun savedRequestQueriesCommittedResultWithoutReplay() = runTest(dispatcher) {
        val repository = FakeImports().apply { committed = outcome(id(801)) }
        val saved = SavedStateHandle(mapOf("import-ledger" to context.ledgerId, "import-generation" to context.storageGeneration, "import-batch" to id(801)))
        val vm = model(repository, saved = saved)
        vm.bind(context)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.RESULT, vm.state.value.phase)
        assertEquals(1, repository.attemptReads)
        assertEquals(0, repository.applies)
        assertEquals(0, repository.reviews)
    }

    /** 목적: commit 뒤 늦은 응답과 취소를 성공/실패로 추정하지 않는다.
     * 조건: Fake는 확정 결과를 저장하고 응답만 NonCancellable gate로 지연한다.
     * 절차: 명시 apply→취소→응답 실제 종료→명시 결과 조회.
     * 기대: 취소 후 RECOVERY·결과 미표시, 조회 후 RESULT이며 apply는 계속1회다.
     */
    @Test fun cancelledResponseRequiresDurableResultLookupAndNeverReplay() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val repository = FakeImports().apply { afterCommit = { withContext(NonCancellable) { release.await() } } }
        val vm = model(repository)
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        advanceUntilIdle()
        vm.confirmTarget()
        advanceUntilIdle()
        vm.backupReturned(id(700))
        advanceUntilIdle()
        vm.apply()
        runCurrent()
        vm.requestCancel()
        runCurrent()
        assertEquals(ImportPreviewPhase.STOPPING, vm.state.value.phase)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.RECOVERY, vm.state.value.phase)
        assertNull(vm.state.value.result)
        vm.resolve()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.RESULT, vm.state.value.phase)
        assertEquals(1, repository.applies)
    }

    /** 다른 세대의 SavedState를 현재 장부 대상으로 바꿔 조회/실행하지 않는다. */
    @Test fun savedForeignContextIsBlockedWithoutRepositoryCall() = runTest(dispatcher) {
        val repository = FakeImports()
        val vm = model(repository, saved = SavedStateHandle(mapOf("import-ledger" to id(901), "import-generation" to id(902), "import-batch" to id(801))))
        vm.bind(context)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.BLOCKED, vm.state.value.phase)
        assertEquals(0, repository.attemptReads)
        assertEquals(0, repository.applies)
    }

    /** 기준액 입력 중에는 기존 검토/백업이 있어도 반영하지 않고 명시 유지·재백업 확인 후에만 실행한다. */
    @Test fun unconfirmedBasisDraftBlocksApplyAndExplicitKeepClearsIt() = runTest(dispatcher) {
        val repository = FakeImports()
        val vm = model(repository)
        vm.bind(context)
        advanceUntilIdle()
        vm.select(uri)
        advanceUntilIdle()
        vm.confirmTarget()
        advanceUntilIdle()
        vm.backupReturned(id(700))
        advanceUntilIdle()
        vm.anchorAmount(id(10), "123")
        assertEquals("123", vm.state.value.ui.workflow.accounts.single().draft.amount)
        assertFalse(vm.state.value.ui.workflow.canApply)
        vm.apply()
        advanceUntilIdle()
        assertEquals(0, repository.applies)
        vm.keepAnchor(id(10))
        advanceUntilIdle()
        assertFalse(vm.state.value.ui.workflow.accounts.single().draft.dirty)
        vm.backupReturned(id(700))
        advanceUntilIdle()
        vm.apply()
        advanceUntilIdle()
        assertEquals(1, repository.applies)
    }

    /** 영속 요청이 없다고 확인한 조회는 명시적으로 닫을 수 있으며 없는 요청을 재실행/ack 쓰기하지 않는다. */
    @Test fun verifiedMissingRequestCanBeAcknowledgedLocallyWithoutReplay() = runTest(dispatcher) {
        val repository = FakeImports().apply { missing = true }
        val saved = SavedStateHandle(mapOf("import-ledger" to context.ledgerId, "import-generation" to context.storageGeneration, "import-batch" to id(801)))
        val vm = model(repository, saved = saved)
        vm.bind(context)
        advanceUntilIdle()
        assertTrue(vm.state.value.missingVerified)
        assertTrue(vm.state.value.ui.canAcknowledge)
        vm.acknowledge()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.EMPTY, vm.state.value.phase)
        assertNull(saved.get<String>("import-batch"))
        assertEquals(0, repository.applies)
        assertEquals(0, repository.acknowledgements)
    }

    private class FakeImports : ImportRepository {
        var reviews = 0
        var applies = 0
        var pendingReads = 0
        var attemptReads = 0
        private var latest: ImportReview? = null
        var committed: ImportResult? = null
        var afterCommit: suspend () -> Unit = {}
        var missing = false
        var acknowledgements = 0
        override suspend fun review(context: LedgerContext, source: ByteArray, options: ImportReviewOptions): ImportReview {
            reviews++
            return reviewValue(options).also { latest = it }
        }
        override suspend fun revise(context: LedgerContext, token: String, options: ImportReviewOptions) = review(context, byteArrayOf(), options)
        override suspend fun bindBackup(context: LedgerContext, token: String, exportId: String) = checkNotNull(latest).copy(verifiedBackupId = exportId).also { latest = it }
        override suspend fun checkSource(context: LedgerContext, token: String, readBack: ByteArray) = Unit
        override suspend fun apply(context: LedgerContext, token: String, onStage: (ImportStage) -> Unit): ImportResult {
            applies++
            val result = outcome(checkNotNull(latest).batchId)
            committed = result
            afterCommit()
            return result
        }
        override suspend fun attempt(context: LedgerContext, batchId: String): ImportAttempt {
            attemptReads++
            return if (missing) ImportAttempt.Missing else committed?.let(ImportAttempt::Committed) ?: ImportAttempt.Requested(batchId, context)
        }
        override suspend fun pending(context: LedgerContext): List<ImportAttempt> {
            pendingReads++
            return emptyList()
        }
        override suspend fun acknowledge(context: LedgerContext, batchId: String) {
            acknowledgements++
        }
        override fun discard(token: String) = Unit
        private fun reviewValue(options: ImportReviewOptions): ImportReview {
            val row = ImportSourceRow("synthetic", "transaction", "S1", "rows/0", "{}", "a".repeat(64), true, "wallet")
            val input = ImportPreviewInput("a".repeat(64), "b".repeat(64), "c".repeat(64), "2026-11-13T00:00:00Z", "합성 VM 경계 대역", emptyList(), listOf(row), "test", "test", context, emptyList(), emptyList(), emptyList())
            val assessment = ImportRowAssessment(row, ImportDecision.HELD, setOf(ImportHoldReason.REVIEW_CONFIRMATION_REQUIRED), "합성 거래", "1", "지출", "KRW", null)
            val report = ImportPreviewReport(input, context, 1, listOf(assessment), emptyList(), emptyMap(), emptyMap(), "d".repeat(64))
            val account = LedgerAccount(id(10), "합성 계좌", AccountNature.GENERAL, AccountStatus.ACTIVE, 1, BalanceAnchor(id(20), LocalDate.of(2026, 1, 1), null, null, "미확인"))
            return ImportReview("token-$reviews", id(800 + reviews), report, options, listOf(account), emptyList(), options.targetConfirmed)
        }
    }

    companion object {
        private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
        private val context = LedgerContext(id(1), id(2))
        private fun outcome(batch: String) = ImportResult(batch, context, "2026-11-13T00:00:00Z", 2, 1, 1, 0, 0, 0, 1, 0, false, id(900))
    }
}
