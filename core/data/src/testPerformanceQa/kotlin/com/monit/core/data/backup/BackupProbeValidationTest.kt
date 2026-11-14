package com.monit.core.data.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.LedgerDatabase
import com.monit.core.data.createLedgerDatabase
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 단계 경계와 오류 계약을 검증한다. host 관측 비용이나 실기기 heap 통과를 판정하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupProbeValidationTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val phases = listOf("SCHEMA", "MANIFEST", "FIELD_KEYS", "FINANCE", "EVIDENCE", "IMPORT")
    private val name = "probe-validation.db"
    private lateinit var database: LedgerDatabase

    @Before fun open() {
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), name)
    }

    @After fun close() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    /** 유효 v3의 여섯 단계는 순서대로 닫히고 기존 PARSE/LOGICAL 경계와 결과를 보존한다. */
    @Test fun validV3KeepsOuterBoundariesAndSemanticResult() = runTest {
        val root = source()
        val probe = BackupWorkProbe().apply { configure() }
        val result = validate(root, probe)
        assertEquals(BackupProbeSession.semanticDigest(root), BackupProbeSession.semanticDigest(result.root))
        assertTrace(probe, phases)
    }

    /** 각 단계의 실제 불량 입력은 기존 오류 분류를 유지하고 실패 단계까지의 end만 기록한다. */
    @Test fun failuresCloseTheirOwnPhaseAndStopBeforeLaterPhases() = runTest {
        val original = source()
        val changes: List<Pair<String, (JSONObject) -> Unit>> = listOf(
            "SCHEMA" to { it.put("unknownField", true) },
            "MANIFEST" to { it.getJSONObject("manifest").put("recordTotal", 1) },
            "FIELD_KEYS" to { it.getJSONObject("collections").getJSONArray("accounts").getJSONObject(0).put("ledgerId", BackupProbeFixtures.id(999_999)) },
            "FINANCE" to { it.getJSONObject("collections").getJSONArray("transactions").getJSONObject(0).put("revision", "999") },
            "EVIDENCE" to {
                it.getJSONArray("submissionsEvidence").values().map { row -> row as JSONObject }
                    .first { row -> !row.isNull("operations") }.getJSONObject("operations").put("requestSha256", "0".repeat(64))
            },
            "IMPORT" to { it.getJSONObject("imports").getJSONArray("import_rows").getJSONObject(0).put("batchId", BackupProbeFixtures.id(999_999)) },
        )
        for ((phase, change) in changes) {
            val root = JSONObject(original.toString()).also(change)
            val problem = if (phase == "SCHEMA") BackupProblem.INVALID_PAYLOAD else BackupProblem.INVALID_RELATION
            assertFailure(root, problem, phases.take(phases.indexOf(phase) + 1))
        }
    }

    /**
     * 목적: 하위 단계에서 전달된 취소를 오류로 바꾸지 않고 열린 경계를 닫는다.
     * 조건: 정상 합성 v3와 FINANCE의 today 조회에서 같은 CancellationException을 던지는 Clock.
     * 절차: 실제 validator를 호출하고 예외 동일성·관측 순서를 확인한다. 실제 Job 경합 시험은 아니다.
     * 기대: 원래 취소 인스턴스 전파, FINANCE/LOGICAL end, 이후 EVIDENCE/IMPORT 미실행.
     */
    @Test fun cancellationClosesFinanceAndOuterLogicalWithoutReclassification() = runTest {
        val root = source()
        val cancelled = CancellationException("Synthetic validation cancellation")
        val cancellingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = throw cancelled
        }
        val probe = BackupWorkProbe().apply { configure() }
        assertSame(cancelled, assertFailsWith<CancellationException> { validate(root, probe, cancellingClock) })
        assertTrace(probe, phases.take(4))
    }

    /** v1 재귀의 두 PARSE/LOGICAL과 v2 원래 manifest/정규화 후 manifest가 모두 균형을 이룬다. */
    @Test fun v1RecursionRetainsNestedBoundariesAndBothManifests() = runTest {
        val root = legacyV1()
        val probe = BackupWorkProbe().apply { configure() }
        assertEquals(3, validate(root, probe).root.getInt("formatVersion"))
        val events = probe.snapshot()
        assertBalanced(events)
        assertEquals(2, events.count { it.event == "PARSE" && it.start })
        assertEquals(2, events.count { it.event == "LOGICAL_VALIDATION" && it.start })
        assertEquals(listOf("SCHEMA", "SCHEMA", "MANIFEST", "MANIFEST", "FIELD_KEYS", "FINANCE", "EVIDENCE", "IMPORT"), subphaseStarts(events))
    }

    /** v2 원래 manifest가 잘못되면 정규화로 덮어 통과하지 않고 첫 MANIFEST에서 거부한다. */
    @Test fun v2ChecksOriginalManifestBeforeUpgrade() = runTest {
        val v1 = legacyV1()
        val original = BackupProbeFixtures.bytes(v1)
        val converted = try {
            BackupV1Reader.convert(v1, original)
        } finally {
            original.fill(0)
        }
        val root = try {
            BackupJson.parse(converted)
        } finally {
            converted.fill(0)
        }
        val probe = BackupWorkProbe().apply { configure() }
        assertEquals(3, validate(root, probe).root.getInt("formatVersion"))
        assertTrace(probe, listOf("SCHEMA", "MANIFEST", "MANIFEST", "FIELD_KEYS", "FINANCE", "EVIDENCE", "IMPORT"))
        root.getJSONObject("manifest").put("recordTotal", 1)
        assertFailure(root, BackupProblem.INVALID_RELATION, listOf("SCHEMA", "MANIFEST"))
    }

    /** schema·source ID·manifest가 함께 불량이어도 기존 schema→context→manifest 첫 오류 순서를 유지한다. */
    @Test fun compoundInvalidInputsKeepFirstErrorOrder() = runTest {
        val root = source()
        root.getJSONObject("sourceLedger").put("id", BackupProbeFixtures.id(1) + "\n")
        root.getJSONObject("manifest").put("recordTotal", 1)
        root.put("unknownField", true)
        assertFailure(root, BackupProblem.INVALID_PAYLOAD, listOf("SCHEMA"))
        root.remove("unknownField")
        assertFailure(root, BackupProblem.INVALID_RELATION, listOf("SCHEMA"))
    }

    /** 50,000개 상한에도 관측은 항목마다 늘지 않으며 기존 enum 이름·ordinal과 4,096 event 용량을 유지한다. */
    @Test fun recordLimitKeepsObservationBoundedAndExistingEventCodesStable() = runTest {
        val existing = listOf("KDF_ENCRYPT", "KDF_DECRYPT", "GCM_ENCRYPT", "GCM_DECRYPT", "PARSE", "LOGICAL_VALIDATION", "RECOVERY_COPY", "REPLACEMENT", "REPLACEMENT_BODY") + phases
        assertEquals(existing, BackupProbeEvent.entries.take(existing.size).map { it.name })
        assertEquals(listOf("MERGED_LIMITS", "RESTORE_PLAN"), BackupProbeEvent.entries.drop(existing.size).map { it.name })
        assertEquals(4096, BackupWorkProbe.CAPACITY)
        val root = source()
        val missing = 50_000 - root.getJSONObject("manifest").getInt("recordTotal")
        repeat(missing) { root.getJSONArray("operationFences").put(BackupProbeFixtures.id(1_000_000 + it)) }
        val manifest = root.getJSONObject("manifest")
        root.put("manifest", BackupSnapshotAdapter.manifest(root, manifest.text("submissionCoverage"), manifest.text("sourceMappings")))
        val probe = BackupWorkProbe().apply { configure() }
        assertEquals(50_000, validate(root, probe).root.getJSONObject("manifest").getInt("recordTotal"))
        assertTrace(probe, phases)
        assertEquals(16, probe.snapshot().size)
    }

    private suspend fun source(): JSONObject = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.SMALL).second

    private fun legacyV1(): JSONObject = JSONObject(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.reader().readText() })

    private suspend fun validate(root: JSONObject, probe: BackupWorkProbe, validationClock: Clock = clock): ValidatedBackup {
        val bytes = BackupProbeFixtures.bytes(root)
        try {
            return BackupValidator(validationClock, probe).validate(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun assertFailure(root: JSONObject, problem: BackupProblem, expectedPhases: List<String>) {
        val probe = BackupWorkProbe().apply { configure() }
        assertEquals(problem, assertFailsWith<BackupException> { validate(root, probe) }.problem, expectedPhases.last())
        assertTrace(probe, expectedPhases)
    }

    private fun assertTrace(probe: BackupWorkProbe, expectedPhases: List<String>) {
        val events = probe.snapshot()
        val expected = listOf("PARSE" to true, "PARSE" to false, "LOGICAL_VALIDATION" to true) +
            expectedPhases.flatMap { listOf(it to true, it to false) } + ("LOGICAL_VALIDATION" to false)
        assertEquals(expected, events.map { it.event to it.start })
        assertBalanced(events)
    }

    private fun subphaseStarts(events: List<ProbeRecord>): List<String> = events.filter { it.start && it.event in phases }.map { it.event }

    private fun assertBalanced(events: List<ProbeRecord>) {
        val open = mutableListOf<String>()
        for (event in events) {
            if (event.start) open.add(event.event) else assertEquals(event.event, open.removeAt(open.lastIndex))
        }
        assertTrue(open.isEmpty())
        assertTrue(events.size < BackupWorkProbe.CAPACITY)
    }
}
