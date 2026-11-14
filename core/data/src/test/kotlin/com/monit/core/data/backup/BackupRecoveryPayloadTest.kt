package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupRecoveryPayloadTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)

    /** 검증된 root의 최소 값만 추출하고 같은 plain 소유권을 사본 단계에 넘긴다. */
    @Test fun validatedRootProvidesFencesWithoutCopyingPlaintext() = runTest {
        val original = checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.readBytes() }
        val validated = BackupValidator(clock).validate(original)
        val plain = BackupJson.utf8(validated.root.toString())
        val result = prepareBackupRecoveryPayload(plain) {
            assertSame(plain, it)
            BackupValidator(clock).validate(it)
        }
        assertSame(plain, result.plain)
        assertEquals(validated.root.getJSONArray("operationFences").values(), result.operationFences)
        assertEquals(validated.root.list("submissionsEvidence").map { it.text("operationId") }, result.submissionOperations)
        assertEquals(validated.root.getJSONObject("imports").list("import_source_fences").size, result.importFences.size)
        assertTrue(plain.any { it != 0.toByte() })
        result.plain.fill(0)
        original.fill(0)
    }

    /** 실제 strict validator 오류와 metadata 추출 오류 모두 handed-off 전 plain을 소거한다. */
    @Test fun validationAndExtractionFailuresWipePlaintext() = runTest {
        val invalid = "invalid-json".toByteArray()
        assertFailsWith<BackupException> { prepareBackupRecoveryPayload(invalid) { BackupValidator(clock).validate(it) } }
        assertTrue(invalid.all { it == 0.toByte() })
        val plain = ByteArray(17) { 71 }
        val failure = IllegalStateException("Synthetic validation failure")
        assertSame(failure, assertFailsWith<IllegalStateException> { prepareBackupRecoveryPayload(plain) { throw failure } })
        assertTrue(plain.all { it == 0.toByte() })
        val raw = checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.readBytes() }
        val validated = BackupValidator(clock).validate(raw)
        val extraction = ByteArray(19) { 71 }
        assertFailsWith<JSONException> { prepareBackupRecoveryPayload(extraction) { validated.copy(root = JSONObject()) } }
        assertTrue(extraction.all { it == 0.toByte() })
    }

    /**
     * 목적: 실제 Job 취소가 검증 중단점에서 소유 plain을 소거하고 handoff를 막는다.
     * 조건: 같은 runTest scheduler와 완료되지 않은 검증 gate.
     * 절차: 검증 진입을 기다린 뒤 cancelAndJoin으로 finally 완료까지 확인한다.
     * 기대: 반환 payload 없음·입력 전부0. 실제 blocking provider의 즉시 취소 증거는 아니다.
     */
    @Test fun cancellationAtValidationSuspensionWipesBeforeJobFinishes() = runTest {
        val plain = ByteArray(31) { 73 }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ValidatedBackup>()
        var result: BackupRecoveryPayload? = null
        val job = launch {
            result = prepareBackupRecoveryPayload(plain) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertNull(result)
        assertTrue(plain.all { it == 0.toByte() })
    }
}
