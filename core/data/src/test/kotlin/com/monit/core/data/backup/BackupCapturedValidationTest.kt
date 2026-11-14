package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 내부 캡처도 기존 bytes 경로의 판정·오류 우선순위와 같아야 하며 wire 검사를 생략하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class, manifest = Config.NONE)
class BackupCapturedValidationTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private suspend fun minimal(): JSONObject = BackupValidator(clock).validate(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.readBytes() }).root
    private suspend fun candidate(root: JSONObject, validator: BackupValidator) = validator.validate(BackupCapturedSnapshot.capture(root))
    private data class Outcome(val json: String? = null, val failure: String? = null)

    private suspend fun outcome(block: suspend () -> ValidatedBackup): Outcome = try {
        Outcome(json = block().root.toString())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome(failure = "${error.javaClass.name}:${(error as? BackupException)?.problem}:${error.cause?.javaClass?.name}")
    }

    private suspend fun equivalent(root: JSONObject, problem: BackupProblem? = null): Outcome {
        val before: String? = root.toString()
        val expected = outcome { BackupValidator(clock).validate(BackupJson.utf8(root.toString())) }
        if (problem != null) assertTrue(expected.failure?.contains(":$problem:") == true, expected.toString())
        assertEquals(expected, outcome { candidate(root, BackupValidator(clock)) })
        assertEquals(before, root.toString())
        return expected
    }

    /** 실제 캡처의 정수와 parser 정규화가 필요한 Long/Double/Decimal·array null도 이전 판정을 따른다. */
    @Test fun nativeAndNormalizedValuesKeepTheBytesDecision() = runTest {
        assertNotNull(equivalent(minimal()).json)
        for (version in listOf<Any>(3L, 3.0, BigDecimal("3.0"))) assertNotNull(equivalent(minimal().put("formatVersion", version)).json)
        for (value in listOf<Any>(JSONObject.NULL, JSONArray().put(null as Any?), JSONArray().put(Double.NaN as Any), Any())) equivalent(minimal().put("unexpected", value))
        val legacy = JSONObject(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.reader().readText() })
        equivalent(legacy)
    }

    /** schema/manifest/원문 hash·관계 오류와 두 오류가 겹친 입력도 첫 bytes 판정을 유지한다. */
    @Test fun schemaManifestAndRelationFailuresKeepTheirOrder() = runTest {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("formatVersion", 4).put("unexpected", true) },
            { it.put("unexpected", true) },
            { it.getJSONObject("manifest").put("recordTotal", 99) },
            { it.getJSONObject("sourceLedger").put("revision", "01") },
            { it.getJSONObject("sourceLedger").put("id", "INVALID") },
            { it.getJSONArray("originals").getJSONObject(0).put("sha256", "0".repeat(64)) },
            { it.getJSONObject("collections").getJSONArray("transactions").put(JSONObject().put("amount", "-1")) },
        )
        for (change in changes) equivalent(minimal().also(change))
    }

    /** 모든 escape/Unicode와 잘못된 surrogate, 깊이 초과는 schema보다 앞선 원래 strict 경계에서 판정한다. */
    @Test fun strictUnicodeAndDepthAreStillCheckedBeforeSchema() = runTest {
        for (text in listOf("/\\\"\b\u000C\n\r\t\u0001가é😀", "\uD800", "\uDC00", "\uDC00\uD800")) equivalent(minimal().put("unexpected", text), BackupProblem.INVALID_PAYLOAD)
        var nested = JSONObject()
        repeat(18) { nested = JSONObject().put("nested", nested) }
        equivalent(minimal().put("unexpected", nested), BackupProblem.LIMIT)
        equivalent(minimal().put("unexpected", "x".repeat(BackupEnvelope.MAX_PAYLOAD) + "\uD800"), BackupProblem.INVALID_PAYLOAD)
    }

    /** 8MiB±1의 parse→schema 우선순위와 manifest50k±1은 실제 bytes oracle과 일치한다. */
    @Test fun byteAndRecordLimitsUseTheExistingOrder() = runTest {
        for (delta in -1..1) {
            val root = minimal().put("unexpected", "")
            val length = BackupJson.utf8ByteCount(root.toString()).toInt()
            root.put("unexpected", "x".repeat(BackupEnvelope.MAX_PAYLOAD + delta - length))
            equivalent(root, if (delta > 0) BackupProblem.LIMIT else BackupProblem.INVALID_PAYLOAD)
        }
        for (count in listOf(49_999, 50_000, 50_001)) equivalent(minimal().also { it.getJSONObject("manifest").put("recordTotal", count) })
    }

    /** 논리 검증의 Clock 취소는 동일 CancellationException 그대로 전달하며 오류로 변환하지 않는다. */
    @Test fun logicalCancellationKeepsTheOriginalException() = runTest {
        val root = minimal()
        val cancellation = CancellationException("Synthetic captured cancellation")
        val cancelling = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = throw cancellation
        }
        assertSame(cancellation, assertFailsWith<CancellationException> { BackupValidator(cancelling).validate(BackupJson.utf8(root.toString())) })
        assertSame(cancellation, assertFailsWith<CancellationException> { candidate(root, BackupValidator(cancelling)) })
    }

    /** canonical v3는 같은 tree를 재사용하고 실제 사본 plain은 기존 UTF-8 bytes와 동일하다. */
    @Test fun canonicalSnapshotReusesTheTreeAndKeepsExactPlaintext() = runTest {
        val root = minimal()
        val snapshot = BackupCapturedSnapshot.capture(root)
        assertSame(root, BackupValidator(clock).validate(snapshot).root)
        val result = prepareBackupRecoveryPayload(snapshot) { BackupValidator(clock).validate(it) }
        assertContentEquals(BackupJson.utf8(root.toString()), result.plain)
        result.plain.fill(0)
    }
}
