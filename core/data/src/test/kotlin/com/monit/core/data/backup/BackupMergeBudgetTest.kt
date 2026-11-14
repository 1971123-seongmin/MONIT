package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class, manifest = Config.NONE)
class BackupMergeBudgetTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private fun id(number: Int) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

    private suspend fun source(): ValidatedBackup = BackupValidator(clock).validate(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.readBytes() })
    private fun manifest(root: JSONObject) {
        root.put("manifest", BackupSnapshotAdapter.manifest(root, root.getJSONObject("manifest").text("submissionCoverage"), root.getJSONObject("imports").text("coverage")))
    }

    /** 기존 clone/serialize 기준을 별도로 실행해 신규 판정과 source 불변을 대조한다. */
    private fun assertOldDecision(source: ValidatedBackup, previous: Set<String>, imports: List<JSONObject> = emptyList()) {
        val before = source.root.toString()
        val old = JSONObject(before)
        val operations = (previous + old.getJSONArray("operationFences").values().map { it as String }).sorted()
        old.put("operationFences", JSONArray(operations))
        old.getJSONObject("imports").put("import_source_fences", JSONArray(imports))
        manifest(old)
        val expected = old.getJSONObject("manifest").getLong("recordTotal") <= 50_000 && BackupJson.utf8ByteCount(old.toString()) <= BackupEnvelope.MAX_PAYLOAD
        val result = runCatching { BackupMergeBudget.requireWithinLimits(source, previous, imports) }
        assertEquals(expected, result.isSuccess)
        result.exceptionOrNull()?.let { assertEquals(BackupProblem.LIMIT, (it as BackupException).problem) }
        assertEquals(before, source.root.toString())
    }

    /** old/new manifest 건수의 여러 자리수와 겹치는/새 UUID가 기존 표현 길이/판정을 유지한다. */
    @Test fun manifestDigitChangesAndCoverageMatchPreviousSerializer() = runTest {
        for (count in listOf(0, 1, 9, 10, 99, 100, 999, 1000)) {
            val source = source()
            source.root.put("operationFences", JSONArray((0 until count).map(::id)))
            source.root.getJSONArray("originals").getJSONObject(0).put("fileName", "합성/\\\n\uD83D\uDE00")
            manifest(source.root)
            assertOldDecision(source, setOf(id(0), id(900_000)))
        }
    }

    /** 최종49,999/50,000은 허용하고50,001은 LIMIT이며 이전 UUID를 버리지 않는다. */
    @Test fun finalRecordBoundaryIncludesPreviousHistoricalOperations() = runTest {
        val source = source()
        val nonFences = source.root.getJSONObject("manifest").getInt("recordTotal") - source.root.getJSONArray("operationFences").length()
        val count = 49_999 - nonFences
        source.root.put("operationFences", JSONArray((0 until count).map(::id)))
        manifest(source.root)
        assertOldDecision(source, emptySet())
        assertOldDecision(source, setOf(id(900_001)))
        assertOldDecision(source, setOf(id(900_001), id(900_002)))
    }

    /** 실제 decoder/hash가 성공하는 개별 원문도 합계2MiB+1이면 기존 분류로 거부한다. */
    @Test fun totalOriginalLimitIsStillCheckedAfterIndividualHashes() = runTest {
        val source = source()
        val root = source.root
        val originalSize = root.list("originals").sumOf { it.exactLong("byteLength") }.toInt()
        val bytes = ByteArray(2 * 1024 * 1024 - originalSize) { 79 }
        val extra = JSONObject().put("id", id(900_003)).put("fileName", "synthetic-original").put("sourceNamespace", "SYNTHETIC")
            .put("byteLength", bytes.size.toString()).put("sha256", BackupJson.digest(bytes)).put("bytesBase64", Base64.getEncoder().encodeToString(bytes))
        root.getJSONArray("originals").put(extra)
        manifest(root)
        BackupValidator(clock).validate(BackupJson.utf8(root.toString()))
        val overflow = bytes + byteArrayOf(79)
        extra.put("byteLength", overflow.size.toString()).put("sha256", BackupJson.digest(overflow)).put("bytesBase64", Base64.getEncoder().encodeToString(overflow))
        manifest(root)
        assertEquals(BackupProblem.INVALID_RELATION, assertFailsWith<BackupException> { BackupValidator(clock).validate(BackupJson.utf8(root.toString())) }.problem)
    }
}
