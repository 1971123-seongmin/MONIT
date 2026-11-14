package com.monit.app.backup.probe

import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monit.app.backup.*
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.data.backup.*
import com.monit.core.model.*
import com.monit.feature.home.backup.BackupPhase
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 플랫폼·SAF·Room 경로를 사용한다. 오류 주입은 새 합성 UID의 전용 인스턴스에만 적용한다. */
@RunWith(AndroidJUnit4::class)
class BackupFaultInstrumentationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val documents get() = BackupDocuments(app, probeContexts)

    /** 공개 고정 벡터와 변조/비용 header 거부를 실제 provider로 확인하며 provider/cost를 바꾸지 않는다. */
    @Test fun platformCryptoVectors() = runBlocking {
        evidence("vectors") { pack, writer ->
            val count = BackupProbeSession.verifyPlatformVectors()
            assertTrue(count > 0)
            writer.append(JSONObject().put("vectors", count))
            BackupProbeSession.open(app, pack, 1).use { session ->
                val before = session.state()
                val bytes = session.encryptSource(BackupProbeSession.password())
                try {
                    for (header in listOf(false, true)) {
                        val invalid = bytes.copyOf()
                        if (header) ByteBuffer.wrap(invalid).putInt(12, 600_001)
                        else invalid[invalid.lastIndex] = (invalid.last().toInt() xor 1).toByte()
                        val password = BackupProbeSession.password()
                        session.record()
                        try {
                            val expected = if (header) BackupProblem.HEADER else BackupProblem.AUTHENTICATION
                            assertEquals(expected, assertFailsWith<BackupException> { session.repository.inspect(session.context, invalid, password) }.problem)
                            assertTrue(password.all { it == '\u0000' })
                            assertTrue(session.events().none { it.event == "PARSE" })
                            if (header) assertTrue(session.events().isEmpty())
                            assertEquals(before.semanticSha256, session.state().semanticSha256)
                            writer.append(JSONObject().put("case", expected.name).put("events", ProbeResultWriter.events(session.events())).put("activeUnchanged", true))
                        } finally {
                            invalid.fill(0)
                        }
                    }
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }

    /**
     * 목적: SAF 실제 bytes/오류를 따르고 문서의 size metadata를 신뢰하지 않는다.
     * 조건: 자체 DocumentsProvider의 정상/거부/pipe 실패/거짓 크기 문서만 사용한다.
     * 절차: 실제 write→close→새 read, 각 오류와 실제 상한+1 read를 실행한다.
     * 기대: 정상 bytes 동일, IO·LIMIT 분류, 검증 실패를 성공으로 기록하지 않는다.
     */
    @Test fun realSafBytesAndFailures() = runBlocking {
        evidence("saf") { _, writer ->
            val bytes = ByteArray(116) { (it % 127).toByte() }
            for (mode in listOf(ProbeDocumentMode.NORMAL, ProbeDocumentMode.FALSE_SIZE)) {
                val uri = document(mode)
                val readBack = documents.writeAndRead(uri, bytes)
                try {
                    assertContentEquals(bytes, readBack)
                } finally {
                    readBack.fill(0)
                }
                if (mode == ProbeDocumentMode.FALSE_SIZE) {
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_SIZE)
                    app.contentResolver.query(uri, projection, null, null, null)!!.use {
                        assertContentEquals(projection, it.columnNames)
                        assertTrue(it.moveToFirst())
                        assertEquals(Long.MAX_VALUE, it.getLong(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)))
                    }
                }
                writer.append(JSONObject().put("case", mode.name).put("status", "PASS"))
            }
            for (mode in listOf(ProbeDocumentMode.OPEN_DENIED, ProbeDocumentMode.REJECT_REREAD, ProbeDocumentMode.BROKEN_WRITE)) {
                val error = assertFailsWith<BackupException> { documents.writeAndRead(document(mode), bytes) }
                assertEquals(BackupProblem.IO, error.problem)
                writer.append(JSONObject().put("case", mode.name).put("problem", error.problem.name))
            }
            assertEquals(BackupProblem.IO, assertFailsWith<BackupException> { documents.read(document(ProbeDocumentMode.BROKEN_READ)) }.problem)
            val oversized = document(ProbeDocumentMode.FALSE_SIZE)
            app.contentResolver.openOutputStream(oversized, "wt")!!.use { output ->
                val block = ByteArray(8192)
                repeat(1024) { output.write(block) }
                output.write(ByteArray(69))
            }
            assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { documents.read(oversized) }.problem)
            writer.append(JSONObject().put("case", "ACTUAL_BYTES_LIMIT_PLUS_ONE").put("problem", "LIMIT"))
        }
    }

    /**
     * 목적: 재읽기 실패 후 실제 VM이 미검증 상태를 보이며 이전 검증 영수증을 보존한다.
     * 조건: 실제 Repository/BackupDocuments, 정상 사전 export, 재읽기 거부 provider.
     * 절차: Main에서 VM bind→내보내기→submit, 상태 방출과 Main 정리 완료를 기다린다.
     * 기대: EXPORTED_UNVERIFIED·실패 안내·노출 영수증 없음·기존 DB 영수증/A 유지·암호 소거.
     */
    @Test fun viewModelRetainsPriorReceiptWhenProviderRejectsReread() = runBlocking {
        evidence("viewmodel") { pack, writer ->
            BackupProbeSession.open(app, pack, 10).use { session ->
                val before = session.state()
                val exported = session.repository.create(session.context, BackupProbeSession.password())
                val receipt = try {
                    val read = documents.writeAndRead(document(), exported.encrypted)
                    try {
                        session.repository.verifyExport(session.context, exported.id, read, BackupProbeSession.password())
                    } finally {
                        read.fill(0)
                    }
                } finally {
                    exported.encrypted.fill(0)
                }
                val store = ViewModelStore()
                val gate = LocalDataWorkGate()
                val recovery = probeRecovery(app, session, "vm-unused")
                val notifications = object : BackupNotifications {
                    override suspend fun reconcile(result: RestoreResult): RestoreResult = error("Export must not schedule notifications")
                }
                val vm = withContext(Dispatchers.Main) {
                    BackupViewModel(session.repository, documents, recovery, notifications, gate).also {
                        store.put("probe", it)
                        it.bind(session.context)
                    }
                }
                try {
                    withTimeout(30_000) { vm.state.first { it.ui.lastBackup.startsWith("마지막 검증 성공:") } }
                    val password = BackupProbeSession.password()
                    val confirmation = password.copyOf()
                    val uri = document(ProbeDocumentMode.REJECT_REREAD)
                    withContext(Dispatchers.Main) {
                        assertTrue(vm.picking(true))
                        vm.selected(uri)
                        vm.submit(password, confirmation)
                    }
                    withTimeout(60_000) { vm.state.first { it.phase == BackupPhase.EXPORTED_UNVERIFIED } }
                    withContext(Dispatchers.Main) {
                        assertFalse(gate.held)
                        assertNull(vm.verifiedExportId())
                        assertNotNull(vm.state.value.ui.message)
                    }
                    assertTrue(password.all { it == '\u0000' } && confirmation.all { it == '\u0000' })
                    assertEquals(receipt.id, session.lastVerifiedExportId())
                    assertEquals(before.semanticSha256, session.state().semanticSha256)
                    writer.append(JSONObject().put("case", "EXPORTED_UNVERIFIED").put("priorReceiptPreserved", true).put("lateSuccess", false))
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                }
            }
        }
    }

    /**
     * 목적: 실제 provider read의 취소·descriptor 정리가 끝난 뒤만 작업이 종료한다.
     * 조건: 자체 reliable pipe read gate, 실제 IO worker, 유한 await/해제.
     * 절차: provider 진입 확인→부모 취소→finally writer 해제→join 후 장부 대사.
     * 기대: 취소 전파·정리 완료·read 결과 없음·A 불변이며 gate는 항상 해제한다.
     */
    @Test fun safCancellationCompletesCleanupWithoutLateBytes() = runBlocking {
        evidence("saf-cancel") { pack, writer ->
            BackupProbeSession.open(app, pack, 20).use { session ->
                val before = session.state()
                val uri = document(ProbeDocumentMode.STALL_READ)
                var completedRead: ByteArray? = null
                val job = launch(Dispatchers.Default) { completedRead = documents.read(uri) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { control(uri, "probe.await")!!.getBoolean("entered") })
                    job.cancel()
                } finally {
                    control(uri, "probe.release")
                    withTimeout(30_000) { job.cancelAndJoin() }
                    completedRead?.fill(0)
                }
                assertTrue(job.isCancelled && job.isCompleted)
                assertNull(completedRead)
                assertEquals(before.semanticSha256, session.state().semanticSha256)
                writer.append(JSONObject().put("case", "PROVIDER_CANCEL").put("cleanupCompleted", true).put("lateBytes", false))
            }
        }
    }

    /**
     * 목적: blocking KDF 중 취소 요청이 lock 해제나 새 요청 허용으로 오인되지 않는다.
     * 조건: 실제 Default 작업의 JCA 직전 bounded gate, 별도 실제 coroutine.
     * 절차: 진입 확인→취소→새 create 거부 확인→gate 해제/실제 join→재요청.
     * 기대: 이전 작업 완료 전 MAINTENANCE, 암호 소거·늦은 성공 없음·A 불변·종료 후 정상 재시도.
     */
    @Test fun kdfCancellationKeepsExclusionUntilActualExit() = runBlocking {
        evidence("kdf-cancel") { pack, writer ->
            BackupProbeSession.open(app, pack, 30).use { session ->
                val before = session.state()
                val gate = ProbeKdfGate()
                val password = BackupProbeSession.password()
                var late: BackupExport? = null
                session.record(gate = gate)
                val job = launch(Dispatchers.Default) { late = session.repository.create(session.context, password) }
                try {
                    assertTrue(withContext(Dispatchers.IO) { gate.entered.await(15, TimeUnit.SECONDS) }, "KDF gate not entered")
                    job.cancel()
                    assertFalse(job.isCompleted)
                    assertEquals(BackupProblem.MAINTENANCE, assertFailsWith<BackupException> { session.repository.create(session.context, BackupProbeSession.password()) }.problem)
                } finally {
                    gate.release.countDown()
                    withTimeout(30_000) { job.cancelAndJoin() }
                    late?.encrypted?.fill(0)
                }
                assertNull(late)
                assertTrue(password.all { it == '\u0000' })
                assertEquals(before.semanticSha256, session.state().semanticSha256)
                session.record()
                session.repository.create(session.context, BackupProbeSession.password()).encrypted.fill(0)
                writer.append(JSONObject().put("case", "KDF_CANCEL").put("exclusionUntilExit", true).put("lateSuccess", false))
            }
        }
    }

    /**
     * 목적: Keystore/공간/검증 사본 오류는 plaintext fallback 및 SQL 교체로 이어지지 않는다.
     * 조건: 실제 store의 per-instance checkpoint, 실제 자체 UID의 정상 key 정책 유지.
     * 절차: 각 단계 오류→실제 restore→A/완료 표식/transaction event 대사.
     * 기대: RECOVERY_COPY·A 동일·새 결과와 교체 시작0. 키 삭제·무효화·디스크 채우기는 하지 않는다.
     */
    @Test fun recoveryFailuresNeverBeginReplacement() = runBlocking {
        evidence("recovery-faults") { pack, writer ->
            for ((index, point) in RecoveryCopyCheckpoint.entries.withIndex()) {
                BackupProbeSession.open(app, pack, 40 + index).use { session ->
                    val before = session.state()
                    val bytes = session.encryptSource(BackupProbeSession.password())
                    try {
                        val preview = session.repository.inspect(session.context, bytes, BackupProbeSession.password())
                        val recovery = probeRecovery(app, session, "fault-${index}")
                        recovery.workProbe.failurePoint = point
                        session.record()
                        assertEquals(BackupProblem.RECOVERY_COPY, assertFailsWith<BackupException> { session.repository.restore(session.context, preview.id, recovery) }.problem)
                        assertTrue(recovery.workProbe.injected, "Expected recovery checkpoint was not reached")
                        assertNull(session.repository.result(preview.id))
                        assertTrue(session.events().none { it.event == "REPLACEMENT" })
                        assertEquals(before.semanticSha256, session.state().semanticSha256)
                        writer.append(JSONObject().put("case", point.name).put("beforeReplacement", true).put("events", ProbeResultWriter.events(session.events())))
                    } finally {
                        bytes.fill(0)
                    }
                }
            }
        }
    }

    /**
     * 목적: 실제 SQLite rollback과 commit 후 재시작의 완전한 A/B 경계를 확인한다.
     * 조건: 실제 Android Room/정상 Keystore 사본, 부분 SQL·직전 취소·직후 응답 유실.
     * 절차: 고정 checkpoint 오류→파일 DB 재열기→결과/내용 조회→동일 restore 재호출.
     * 기대: commit 전 A·새 결과0, commit 후 B·새 세대·영속 결과 동일·재교체0.
     */
    @Test fun actualCommitBoundariesSurviveDatabaseReopen() = runBlocking {
        evidence("commit-faults") { pack, writer ->
            for ((index, failure) in listOf(ProbeFailure.PARTIAL_SQL_FULL, ProbeFailure.BEFORE_COMMIT_CANCEL, ProbeFailure.AFTER_COMMIT_RESPONSE).withIndex()) {
                BackupProbeSession.open(app, pack, 50 + index).use { session ->
                    val before = session.state()
                    val bytes = session.encryptSource(BackupProbeSession.password())
                    val preview = try {
                        session.repository.inspect(session.context, bytes, BackupProbeSession.password())
                    } finally {
                        bytes.fill(0)
                    }
                    val recovery = probeRecovery(app, session, "commit-$index")
                    session.record(failure)
                    when (failure) {
                        ProbeFailure.PARTIAL_SQL_FULL -> assertFailsWith<SQLiteFullException> { session.repository.restore(session.context, preview.id, recovery) }
                        ProbeFailure.BEFORE_COMMIT_CANCEL -> assertFailsWith<CancellationException> { session.repository.restore(session.context, preview.id, recovery) }
                        ProbeFailure.AFTER_COMMIT_RESPONSE -> assertFailsWith<IOException> { session.repository.restore(session.context, preview.id, recovery) }
                        else -> error("Unsupported case")
                    }
                    val events = session.events()
                    session.close()
                    session.reopen(app).use { reopened ->
                        if (failure == ProbeFailure.AFTER_COMMIT_RESPONSE) {
                            val result = assertNotNull(reopened.repository.result(preview.id))
                            assertNotEquals(before.generation, result.context.storageGeneration)
                            assertEquals(pack.info.semanticSha256, reopened.state(result.context).semanticSha256)
                            reopened.record()
                            assertEquals(result, reopened.repository.restore(session.context, preview.id, recovery))
                            assertTrue(reopened.events().none { it.event == "REPLACEMENT" || it.event == "RECOVERY_COPY" })
                        } else {
                            assertNull(reopened.repository.result(preview.id))
                            assertEquals(before.semanticSha256, reopened.state().semanticSha256)
                        }
                    }
                    writer.append(JSONObject().put("case", failure.name).put("reopened", true).put("events", ProbeResultWriter.events(events)))
                }
            }
        }
    }

    private suspend fun evidence(name: String, action: suspend (ProbeFixturePack, ProbeResultWriter) -> Unit) {
        val pack = BackupProbeSession.generate(app, UUID.randomUUID().toString(), ProbeProfile.SMALL)
        val writer = ProbeResultWriter(app, pack, name)
        try {
            action(pack, writer)
            writer.finish(JSONObject().put("status", "PASS").put("wholeReleaseAccepted", false))
        } catch (error: Throwable) {
            writer.finish(JSONObject().put("status", "FAILED").put("errorClass", error.javaClass.simpleName).put("wholeReleaseAccepted", false))
            throw error
        }
    }

    private fun document(mode: ProbeDocumentMode = ProbeDocumentMode.NORMAL): Uri {
        val root = DocumentsContract.buildDocumentUri(ProbeDocumentsProvider.AUTHORITY, "root")
        val uri = checkNotNull(DocumentsContract.createDocument(app.contentResolver, root, "application/octet-stream", "synthetic-${UUID.randomUUID()}.monitbak"))
        control(uri, "probe.configure", Bundle().apply { putString("mode", mode.name) })
        return uri
    }

    private fun control(uri: Uri, method: String, extras: Bundle? = null): Bundle? = app.contentResolver.call(uri, method, DocumentsContract.getDocumentId(uri), extras)
}
