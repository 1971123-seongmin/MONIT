package com.monit.app.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.monit.core.model.*
import com.monit.feature.home.backup.BackupPhase
import java.io.IOException
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
class BackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = BackupTestRepository()
    private val files = object : BackupDocumentAccess {
        var fail = false
        override suspend fun read(uri: Uri) = ByteArray(68)
        override suspend fun writeAndRead(uri: Uri, encrypted: ByteArray): ByteArray {
            if (fail) throw IOException("synthetic reread")
            return encrypted.copyOf()
        }
    }
    private val notifications = object : BackupNotifications {
        var calls = 0
        var fail = true
        override suspend fun reconcile(result: RestoreResult): RestoreResult {
            calls++
            if (fail) throw IOException("synthetic notification")
            return result.copy(notificationResetNeeded = false)
        }
    }
    private fun model() = BackupViewModel(
        repository,
        files,
        object : RecoveryCopyStore {
            override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof = error("test repository never calls recovery")
        },
        notifications,
    ).also {
        store.put("backup", it)
        it.bind(repository.context)
    }

    @Before fun main() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun close() {
        store.clear()
        Dispatchers.resetMain()
    }
    private fun password() = "synthetic-password-2026".toCharArray()
    private fun select(vm: BackupViewModel, export: Boolean) {
        assertTrue(vm.picking(export))
        vm.selected(Uri.parse("content://synthetic/document"))
    }

    /** 불일치 암호는 KDF/쓰기 전에 거부하며 두 CharArray를 모두 지운다. */
    @Test fun passwordMismatchDoesNotStartExportAndClearsOwnedArrays() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        select(vm, true)
        val password = password()
        val confirmation = "different password".toCharArray()
        vm.submit(password, confirmation)
        assertEquals(BackupPhase.EXPORT_PASSWORD, vm.state.value.phase)
        assertEquals(0, repository.creates)
        assertTrue(password.all { it == '\u0000' } && confirmation.all { it == '\u0000' })
    }

    /** 외부 재읽기 실패는 exported/unverified이고 과거 검증 시각을 갱신하지 않는다. */
    @Test fun rereadFailureNeverVerifiesOrChangesPreviousSuccess() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        select(vm, true)
        files.fail = true
        val before = vm.state.value.ui.lastBackup
        val secret = password()
        vm.submit(secret, password())
        advanceUntilIdle()
        assertEquals(BackupPhase.EXPORTED_UNVERIFIED, vm.state.value.phase)
        assertEquals(1, repository.creates)
        assertEquals(0, repository.verifications)
        assertEquals(before, vm.state.value.ui.lastBackup)
        assertTrue(secret.all { it == '\u0000' })
    }

    /** 목적: 미리보기는 무쓰기이며 명시 restore 중 중복 호출을 막는다.
     * 조건: restore 완료를 gate로 통제하고 commit 응답만 유실한다.
     * 절차: 검증→미리보기→restore 두 번→응답 실패→결과 조회→알림 재시도→완료한다.
     * 기대: restore1회, 결과 조회가 금융 재실행하지 않음, 알림 실패와 복원 성공 분리, 완료 ack1회.
     */
    @Test fun explicitRestoreLostReplyAndNotificationRetryDoNotReplayReplacement() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        select(vm, false)
        vm.submit(password(), charArrayOf())
        advanceUntilIdle()
        assertEquals(BackupPhase.PREVIEW, vm.state.value.phase)
        assertEquals(0, repository.restores)
        repository.gate = CompletableDeferred()
        repository.loseReply = true
        vm.restore()
        runCurrent()
        vm.restore()
        assertEquals(1, repository.restores)
        repository.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(BackupPhase.UNKNOWN, vm.state.value.phase)
        vm.resolve()
        advanceUntilIdle()
        assertEquals(BackupPhase.RESULT, vm.state.value.phase)
        assertTrue(vm.state.value.ui.notificationResetNeeded)
        notifications.fail = false
        vm.retryNotifications()
        advanceUntilIdle()
        assertFalse(vm.state.value.ui.notificationResetNeeded)
        assertEquals(1, repository.restores)
        var finished = false
        vm.finish { finished = true }
        advanceUntilIdle()
        assertTrue(finished)
        assertEquals(1, repository.acknowledgements)
    }

    /** 목적: 취소 전에 시작되지 못한 작업도 암호를 지우고 늦은 picker 결과를 무시한다.
     * 조건: 같은 test scheduler의 launch가 실행되기 전 ViewModelStore를 비운다.
     * 절차: URI 결과 없는 callback→정상 선택→submit→store.clear→스케줄러 drain.
     * 기대: 잘못된 callback은 ENTRY 유지, create0회, 암호 배열 NUL.
     */
    @Test fun cancellationBeforeCoroutineStartsClearsPasswordAndIgnoresUnexpectedPicker() = runTest(dispatcher) {
        val vm = model()
        advanceUntilIdle()
        vm.selected(Uri.parse("content://unexpected"))
        assertEquals(BackupPhase.ENTRY, vm.state.value.phase)
        select(vm, true)
        val secret = password()
        vm.submit(secret, password())
        store.clear()
        advanceUntilIdle()
        assertEquals(0, repository.creates)
        assertTrue(secret.all { it == '\u0000' })
    }

    /** 시작 시 미확인 복원 표식을 먼저 읽고, 확인 전 재실행/제품 진입을 허용하지 않는다. */
    @Test fun startupCompletionReadsPersistedResultAndOnlyAcknowledgesIt() = runTest(dispatcher) {
        repository.committed = repository.outcome
        val vm = RestoreCompletionViewModel(repository, notifications).also { store.put("completion", it) }
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()
        assertEquals(repository.outcome, vm.state.value.result)
        var finished = false
        vm.finish { finished = true }
        advanceUntilIdle()
        assertTrue(finished)
        assertNull(vm.state.value.result)
        assertEquals(0, repository.restores)
    }
}

internal class BackupTestRepository : BackupRepository {
    val context = LedgerContext("00000000-0000-0000-0000-000000000900", "00000000-0000-0000-0000-000000000901")
    val outcome = RestoreResult("00000000-0000-0000-0000-000000000902", context.copy(storageGeneration = "00000000-0000-0000-0000-000000000903"), "2026-11-13T03:00:00Z", "2026-11-13T04:00:00Z", 3, false)
    var creates = 0
    var verifications = 0
    var restores = 0
    var acknowledgements = 0
    var committed: RestoreResult? = null
    var loseReply = false
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun create(context: LedgerContext, password: CharArray): BackupExport {
        creates++
        password.fill('\u0000')
        return BackupExport(outcome.id, context, 1, outcome.sourceCreatedAt, ByteArray(68))
    }
    override suspend fun verifyExport(context: LedgerContext, id: String, readBack: ByteArray, password: CharArray): BackupExportReceipt {
        verifications++
        password.fill('\u0000')
        return BackupExportReceipt(id, outcome.sourceCreatedAt, outcome.committedAt, 1)
    }
    override suspend fun inspect(context: LedgerContext, encrypted: ByteArray, password: CharArray): RestorePreview {
        password.fill('\u0000')
        return RestorePreview(outcome.id, context, 1, context, 1, outcome.sourceCreatedAt, mapOf("accounts" to 1), mapOf("accounts" to 1), 3, false, 0)
    }
    override suspend fun restore(context: LedgerContext, previewId: String, recovery: RecoveryCopyStore): RestoreResult {
        restores++
        gate?.await()
        committed = outcome
        if (loseReply) throw IOException("synthetic lost reply")
        return outcome
    }
    override suspend fun status() = BackupStatus("2026-11-12T03:00:00Z", committed)
    override suspend fun result(id: String) = committed?.takeIf { it.id == id }
    override suspend fun acknowledgeRestore(id: String) {
        require(committed?.id == id)
        acknowledgements++
        committed = null
    }
    override suspend fun notificationsReady(id: String): RestoreResult = checkNotNull(committed)
    override suspend fun evidence(context: LedgerContext) = emptyList<RestoredEvidenceSummary>()
    override fun discardPreview(id: String) = Unit
}
