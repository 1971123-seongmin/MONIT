package com.monit.app.backup.probe

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monit.app.backup.BackupDocuments
import com.monit.core.data.backup.*
import com.monit.core.model.BackupStage
import com.monit.core.model.RestorePreview
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 device JCA/Room을 측정한다. smoke·heap 표본을 20개 timing 표본에 섞지 않는다. */
@RunWith(AndroidJUnit4::class)
class BackupPerformanceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 목적: 동일 유효 8MiB/50000 mixed 입력의 실제 KDF·인증·검증 시간을 수집한다.
     * 조건: 새 UID/fixture, 실제 Default dispatcher, 2 warmup 후 20개 또는 명시 smoke1.
     * 절차: actual encrypt→같은 bytes의 Repository.inspect→명시 preview 해제, 별도 heap1.
     * 기대: 실패/미실행도 raw에 남고 완전 표본만 p95 판정, 금융 변경0·UI KDF0.
     */
    @Test fun cryptoMixedLimitRawTwentySamples() = runBlocking {
        val pack = BackupProbeSession.generate(app, UUID.randomUUID().toString(), ProbeProfile.MIXED_LIMIT)
        val writer = ProbeResultWriter(app, pack, "crypto")
        BackupProbeSession.open(app, pack, 0).use { session ->
            val before = session.state()
            assertEquals(pack.info.expectedIncome + 1, before.income)
            writer.append(JSONObject().put("kind", "observerOverhead").put("pairEstimateNanos", session.observerPairOverheadNanos()).put("subtractedFromSamples", false))
            val rows = mutableListOf<JSONObject>()
            var failure: Throwable? = null
            repeat(2) { warmup ->
                if (failure == null) {
                    try {
                        writer.append(cryptoRound(session).put("kind", "warmup").put("sample", warmup))
                    } catch (error: Throwable) {
                        failure = error
                        writer.append(failed(error).put("kind", "warmup").put("sample", warmup))
                    }
                }
            }
            repeat(writer.count) { index ->
                val row = if (failure == null) {
                    try {
                        cryptoRound(session).put("status", "VALID")
                    } catch (error: Throwable) {
                        failure = error
                        JSONObject().put("status", "FAILED").put("errorClass", error.javaClass.simpleName).put("events", ProbeResultWriter.events(session.events()))
                    }
                } else JSONObject().put("status", "NOT_RUN")
                rows += row
                writer.append(row.put("kind", "timing").put("sample", index))
            }
            val valid = rows.filter { it.optString("status") == "VALID" }
            val summary = JSONObject().put("suite", "crypto").put("requested", writer.count).put("valid", valid.size)
                .put("wholeReleaseAccepted", false).put("heapGate", "NOT_PROVEN_BY_SAMPLING")
            if (failure == null && !writer.smoke) {
                val encryption = ProbeStatistics.p95(valid.map { it.getLong("kdfEncryptNanos") })
                val decryption = ProbeStatistics.p95(valid.map { it.getLong("kdfDecryptNanos") })
                summary.put("kdfEncryptP95Nanos", encryption).put("kdfDecryptP95Nanos", decryption)
                summary.put("timingTargetsMet", encryption <= 3_000_000_000L && decryption <= 3_000_000_000L && valid.all { it.getLong("t4Nanos") <= 10_000_000_000L && it.getLong("b10Nanos") <= 10_000_000_000L })
            }
            if (failure == null) {
                val sampler = ProbeHeapSampler()
                val operation = try {
                    cryptoRound(session, sampler)
                } catch (error: Throwable) {
                    failure = error
                    failed(error)
                } finally {
                    sampler.close()
                }
                val heap = sampler.report()
                writer.append(heap.put("kind", "heapOnly").put("timingP95Eligible", false).put("operation", operation))
                summary.put("heapObservedOverLimit", heap.getLong("combinedObservedDeltaBytes") > 64 * 1024 * 1024L)
            }
            assertEquals(before.semanticSha256, session.state().semanticSha256)
            writer.finish(summary.put("status", status(summary, failure, writer.smoke)))
            assertNull(failure, "Timing samples are incomplete; see private raw artifact")
            if (!writer.smoke) assertTrue(summary.getBoolean("timingTargetsMet"), "Measured time target exceeded")
            assertFalse(summary.optBoolean("heapObservedOverLimit"), "Observed heap increase exceeded 64MiB")
        }
    }

    /**
     * 목적: 실제 검증 사본→단일 Room 교체의 transaction p95를 측정한다.
     * 조건: 매 sample 새 A DB, 동일 B, 새 UID의 정상 AndroidKeyStore 사본 경로.
     * 절차: warmup1→실제20회, 각 결과/새세대/B digest 확인, 별도 전체 read/inspect/restore heap1.
     * 기대: 20개를 모두 남기고 transaction p95≤10초, key hardware 여부는 관찰만 한다.
     */
    @Test fun restoreMixedLimitRawTwentySamples() = runBlocking {
        val pack = BackupProbeSession.generate(app, UUID.randomUUID().toString(), ProbeProfile.MIXED_LIMIT)
        val writer = ProbeResultWriter(app, pack, "restore")
        val rows = mutableListOf<JSONObject>()
        var failure: Throwable? = null
        try {
            BackupProbeSession.open(app, pack, 900).use { warmup ->
                writer.append(restoreRound(warmup, "warmup-900").put("kind", "warmup"))
            }
        } catch (error: Throwable) {
            failure = error
            writer.append(failed(error).put("kind", "warmup"))
        }
        repeat(writer.count) { index ->
            val row = if (failure == null) {
                try {
                    BackupProbeSession.open(app, pack, index).use { restoreRound(it, "timing-$index") }.put("status", "VALID")
                } catch (error: Throwable) {
                    failure = error
                    JSONObject().put("status", "FAILED").put("errorClass", error.javaClass.simpleName)
                }
            } else JSONObject().put("status", "NOT_RUN")
            rows += row
            writer.append(row.put("kind", "timing").put("sample", index))
        }
        val valid = rows.filter { it.optString("status") == "VALID" }
        val summary = JSONObject().put("suite", "restore").put("requested", writer.count).put("valid", valid.size)
            .put("wholeReleaseAccepted", false).put("heapGate", "NOT_PROVEN_BY_SAMPLING")
        if (failure == null && !writer.smoke) {
            val percentile = ProbeStatistics.p95(valid.map { it.getLong("transactionNanos") })
            summary.put("transactionP95Nanos", percentile).put("timingTargetsMet", percentile <= 10_000_000_000L)
        }
        if (failure == null) {
            BackupProbeSession.open(app, pack, 901).use { session ->
                val sampler = ProbeHeapSampler()
                val operation = try {
                    restoreRound(session, "heap-901", sampler)
                } catch (error: Throwable) {
                    failure = error
                    failed(error)
                } finally {
                    sampler.close()
                }
                val heap = sampler.report()
                writer.append(heap.put("kind", "heapOnly").put("timingP95Eligible", false).put("operation", operation))
                summary.put("heapObservedOverLimit", heap.getLong("combinedObservedDeltaBytes") > 64 * 1024 * 1024L)
            }
        }
        summary.put("keyObservation", probeKeyObservation(app))
        writer.finish(summary.put("status", status(summary, failure, writer.smoke)))
        assertNull(failure, "Timing samples are incomplete; see private raw artifact")
        if (!writer.smoke) assertTrue(summary.getBoolean("timingTargetsMet"), "Measured transaction target exceeded")
        assertFalse(summary.optBoolean("heapObservedOverLimit"), "Observed heap increase exceeded 64MiB")
    }

    private suspend fun cryptoRound(session: BackupProbeSession, sampler: ProbeHeapSampler? = null): JSONObject {
        session.record()
        val stages = LongArray(BackupStage.entries.size)
        val apiStart = SystemClock.elapsedRealtimeNanos()
        var encrypted: ByteArray? = null
        var inspectStart = 0L
        var apiEnd = 0L
        sampler?.start()
        val preview = try {
            encrypted = session.encryptSource(BackupProbeSession.password())
            inspectStart = SystemClock.elapsedRealtimeNanos()
            val preview = session.repository.inspect(session.context, encrypted, BackupProbeSession.password()) { stages[it.ordinal] = SystemClock.elapsedRealtimeNanos() }
            apiEnd = SystemClock.elapsedRealtimeNanos()
            session.repository.discardPreview(preview.id)
            preview
        } finally {
            encrypted?.fill(0)
            sampler?.close()
        }
        assertEquals(session.pack.info.recordTotal, preview.recordTotal)
        val events = session.events()
        assertTrue(events.none { it.mainThread }, "Backup CPU/transaction event observed on main")
        val validationEnd = events.single { it.event == "LOGICAL_VALIDATION" && !it.start }.nanos
        val authStart = events.single { it.event == "GCM_DECRYPT" && it.start }.nanos
        return JSONObject().put("apiStartNanos", apiStart).put("apiEndNanos", apiEnd).put("inspectStartNanos", inspectStart)
            .put("encryptStartNanos", session.encryptionStartedNanos).put("encryptEndNanos", session.encryptionEndedNanos)
            .put("t4Nanos", validationEnd - session.encryptionStartedNanos).put("b10Nanos", validationEnd - stages[BackupStage.CRYPTO.ordinal])
            .put("authenticationAndValidationWithoutKdfNanos", validationEnd - authStart)
            .put("kdfEncryptNanos", ProbeResultWriter.duration(events, "KDF_ENCRYPT")).put("kdfDecryptNanos", ProbeResultWriter.duration(events, "KDF_DECRYPT"))
            .put("gcmEncryptNanos", ProbeResultWriter.duration(events, "GCM_ENCRYPT")).put("gcmDecryptNanos", ProbeResultWriter.duration(events, "GCM_DECRYPT"))
            .put("parseNanos", ProbeResultWriter.duration(events, "PARSE")).put("logicalValidationNanos", ProbeResultWriter.duration(events, "LOGICAL_VALIDATION"))
            .put("events", ProbeResultWriter.events(events)).put("providers", JSONObject(session.providers())).put("heapSamplerEnabled", sampler != null)
    }

    private suspend fun restoreRound(session: BackupProbeSession, label: String, sampler: ProbeHeapSampler? = null): JSONObject {
        val recovery = probeRecovery(app, session, label)
        val document = prepareRestoreDocument(app, session, label)
        val documents = BackupDocuments(app, probeContexts)
        var apiStart = 0L
        var apiEnd = 0L
        var previewId = ""
        session.record()
        sampler?.start()
        val inputReadStart = SystemClock.elapsedRealtimeNanos()
        var inspectEnd = 0L
        val result = try {
            val preview = inspectRestoreDocument(session, documents, document)
            inspectEnd = SystemClock.elapsedRealtimeNanos()
            previewId = preview.id
            apiStart = SystemClock.elapsedRealtimeNanos()
            val result = session.repository.restore(session.context, preview.id, recovery)
            apiEnd = SystemClock.elapsedRealtimeNanos()
            result
        } finally {
            sampler?.close()
        }
        val events = session.events()
        assertTrue(events.none { it.mainThread })
        for (event in listOf("KDF_DECRYPT", "PARSE", "LOGICAL_VALIDATION")) {
            assertTrue(events.any { it.event == event && it.start && it.nanos in inputReadStart..inspectEnd }, "Missing inspect start: $event")
            assertTrue(events.any { it.event == event && !it.start && it.nanos in inputReadStart..inspectEnd }, "Missing inspect end: $event")
        }
        assertNotEquals(session.context.storageGeneration, result.context.storageGeneration)
        val after = session.state(result.context)
        assertEquals(session.pack.info.semanticSha256, after.semanticSha256)
        assertEquals(session.pack.info.recordTotal, after.recordTotal)
        assertEquals(session.pack.info.expectedIncome, after.income)
        assertEquals(session.pack.info.expectedExpense, after.expense)
        assertEquals(session.pack.info.expectedActive, after.active)
        assertEquals(session.pack.info.expectedDeleted, after.deleted)
        assertEquals(result, session.repository.result(previewId))
        return JSONObject().put("apiStartNanos", apiStart).put("apiEndNanos", apiEnd)
            .put("inputReadStartNanos", inputReadStart).put("inspectEndNanos", inspectEnd)
            .put("preparedEncryptedFileOutsideHeap", true).put("inputReadAndInspectInsideHeap", sampler != null)
            .put("restoreApiNanos", apiEnd - apiStart).put("transactionNanos", ProbeResultWriter.duration(events, "REPLACEMENT"))
            .put("transactionBodyNanos", ProbeResultWriter.duration(events, "REPLACEMENT_BODY")).put("recoveryCopyNanos", ProbeResultWriter.duration(events, "RECOVERY_COPY"))
            .put("events", ProbeResultWriter.events(events)).put("afterSemanticSha256", after.semanticSha256).put("actualKeystoreRecovery", true).put("heapSamplerEnabled", sampler != null)
    }

    /** 파일 읽기·검증은 측정 안에 두고 암호문을 복원 대기/transaction까지 보관하지 않는다. */
    private suspend fun inspectRestoreDocument(session: BackupProbeSession, documents: BackupDocuments, uri: Uri): RestorePreview {
        val encrypted = documents.read(uri)
        return try {
            session.repository.inspect(session.context, encrypted, BackupProbeSession.password())
        } finally {
            encrypted.fill(0)
        }
    }

    private fun failed(error: Throwable) = JSONObject().put("status", "FAILED").put("errorClass", error.javaClass.simpleName)

    private fun status(summary: JSONObject, failure: Throwable?, smoke: Boolean): String = when {
        failure != null -> "INCOMPLETE"
        summary.optBoolean("heapObservedOverLimit") -> "OBSERVED_HEAP_LIMIT_EXCEEDED"
        smoke -> "SMOKE_ONLY"
        !summary.getBoolean("timingTargetsMet") -> "TIMING_TARGET_EXCEEDED"
        else -> "TIMING_RECORDED_HEAP_EVIDENCE_PENDING"
    }
}
