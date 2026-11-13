package com.monit.feature.accounts.importing

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private val store = ViewModelStore()
    private var next = 0
    private val uri = Uri.parse("content://synthetic-import/preview")

    @Before fun setup() = Dispatchers.setMain(dispatcher)

    @After fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun model(ledger: ReadOnlyLedger, reader: ImportDocumentReader) = ImportPreviewViewModel(ledger, reader, clock, contexts).also { store.put("import-${next++}", it) }

    /** 실제 parser/evaluator를 사용하며 성공한 미리보기는 snapshot 읽기 한 번과 실제 반영0뿐이다. */
    @Test fun previewReadsSnapshotAndNeverCallsFinancialApi() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        val vm = model(ledger, ImportDocumentReader { _, _ -> ImportPreviewFixture.bytes() })
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
        assertEquals(6, vm.state.value.report?.rows?.size)
        assertEquals(3, vm.state.value.report?.expectedTransactionCount)
        assertEquals(1, ledger.reads)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /** 파일 오류를 빈 정상 결과로 표시하지 않고 장부 조회 전 실패하며 이전 결과도 새 결과로 남기지 않는다. */
    @Test fun invalidFileIsUnknownCountAndDoesNotReadLedger() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        val vm = model(ledger, ImportDocumentReader { _, _ -> "broken".toByteArray() })
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.FAILED, vm.state.value.phase)
        assertNull(vm.state.value.report)
        assertEquals("확인 전", vm.state.value.ui.sourceRowCount)
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /**
     * 목적: 두 파일 읽기의 늦은 이전 응답이 최신 미리보기를 덮어쓰지 않는다.
     * 조건: 첫 reader가 NonCancellable gate로 늦게 응답한다.
     * 절차: 첫 선택/대기 → 두 번째 선택 완료 → 첫 gate 해제.
     * 기대: 최신 파일 지문/READY 상태를 유지하고 이전 취소는 전파된다.
     */
    @Test fun latePreviousFileCannotReplaceLatestPreview() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        val gate = CompletableDeferred<Unit>()
        val different = ImportPreviewFixture.edited(changeSnapshot = { it.put("scope", "두 번째 합성 범위") })
        val vm = model(
            ledger,
            ImportDocumentReader { selected, _ ->
                if (selected.lastPathSegment == "first") withContext(NonCancellable) {
                    gate.await()
                    ImportPreviewFixture.bytes()
                } else different
            },
        )
        vm.bind(ImportPreviewFixture.context)
        vm.select(Uri.parse("content://synthetic-import/first"))
        runCurrent()
        vm.select(Uri.parse("content://synthetic-import/second"))
        runCurrent()
        val current = assertNotNull(vm.state.value.report).fingerprint
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(current, vm.state.value.report?.fingerprint)
        assertEquals("두 번째 합성 범위", vm.state.value.report?.input?.scope)
        assertEquals(1, ledger.reads)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /** 원본 파일/매핑 bytes 또는 ledger revision 변경은 이전 결과를 유지한 재검토 상태다. */
    @Test fun changedFileOrRevisionRequiresExplicitReviewAgain() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        var bytes = ImportPreviewFixture.bytes()
        val vm = model(ledger, ImportDocumentReader { _, _ -> bytes })
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        advanceUntilIdle()
        val first = assertNotNull(vm.state.value.report)
        ledger.revision = 5
        vm.checkRevision()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.STALE, vm.state.value.phase)
        assertEquals(first, vm.state.value.report)
        vm.reviewAgain()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.READY, vm.state.value.phase)
        assertEquals(5, vm.state.value.report?.ledgerRevision)
        bytes = ImportPreviewFixture.edited(changeMapping = { it.put("version", "changed-synthetic-mapping") })
        vm.checkRevision()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.STALE, vm.state.value.phase)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /** 원본 재확인 I/O 실패는 변경 없음으로 판정하지 않고 이전 결과를 재검토 상태로 보존한다. */
    @Test fun failedRecheckCannotCertifyUnchangedPreview() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        var failed = false
        val vm = model(ledger, ImportDocumentReader { _, _ -> if (failed) throw java.io.IOException() else ImportPreviewFixture.bytes() })
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        advanceUntilIdle()
        val report = assertNotNull(vm.state.value.report)
        failed = true
        vm.checkRevision()
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.STALE, vm.state.value.phase)
        assertEquals(report, vm.state.value.report)
        assertNotNull(vm.state.value.problem)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /** 다른 장부의 매핑이나 현재 binding 변경은 자동 보정하지 않고 원래 장부를 보호한다. */
    @Test fun foreignTargetAndRebindingAreBlockedWithoutReadsOrWrites() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        val bytes = ImportPreviewFixture.edited(changeMapping = { it.getJSONObject("target").put("storageGeneration", ImportPreviewFixture.id(99)) })
        val vm = model(ledger, ImportDocumentReader { _, _ -> bytes })
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.FAILED, vm.state.value.phase)
        assertEquals(0, ledger.reads)
        vm.bind(ImportPreviewFixture.context.copy(storageGeneration = ImportPreviewFixture.id(98)))
        vm.select(uri)
        advanceUntilIdle()
        assertEquals(ImportPreviewPhase.BLOCKED, vm.state.value.phase)
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.forbiddenCalls)
    }

    /** VM 제거는 중단 가능한 입력 읽기를 취소하며 부분 성공이나 뒤늦은 장부 조회를 만들지 않는다. */
    @Test fun clearingViewModelCancelsInputWithoutFinancialWork() = runTest(dispatcher) {
        val ledger = ReadOnlyLedger()
        var cleaned = false
        val vm = model(
            ledger,
            ImportDocumentReader { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            },
        )
        vm.bind(ImportPreviewFixture.context)
        vm.select(uri)
        runCurrent()
        store.clear()
        advanceUntilIdle()
        assertTrue(cleaned)
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.forbiddenCalls)
        assertNull(vm.state.value.report)
    }
}

private class ReadOnlyLedger : LedgerRepository {
    var reads = 0
    var forbiddenCalls = 0
    var revision = 4L
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        reads++
        return ImportPreviewFixture.snapshot(revision)
    }
    private fun forbidden(): Nothing {
        forbiddenCalls++
        error("미리보기는 snapshot 이외 API를 호출할 수 없다")
    }
    override suspend fun openLedger(): LedgerEntryResult = forbidden()
    override suspend fun initialize(context: LedgerContext): Unit = forbidden()
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt = forbidden()
    override suspend fun updateTransaction(context: com.monit.core.model.LedgerContext, operationId: String, value: com.monit.core.model.UpdateTransaction): com.monit.core.model.WriteReceipt = error("Unexpected update")
    override suspend fun deleteTransaction(context: com.monit.core.model.LedgerContext, operationId: String, value: com.monit.core.model.DeleteTransaction): com.monit.core.model.WriteReceipt = error("Unexpected delete")

    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt = forbidden()
    override suspend fun paymentRejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.PaymentRejection? = null
    override suspend fun rejection(context: com.monit.core.model.LedgerContext, operationId: String): com.monit.core.model.TransactionRejection? = null
    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = forbidden()
    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = forbidden()
    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot = forbidden()
    override suspend fun submissionRecovery(context: LedgerContext): SubmissionRecoverySnapshot = forbidden()
    override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult = forbidden()
    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = forbidden()
}
