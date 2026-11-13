package com.monit.core.data.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.model.BackupException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 계측 fixture의 유효성과 독립 예상값을 확인하며 host 시간을 실기기 성능으로 사용하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupProbeFixtureTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var database: LedgerDatabase
    private val name = "probe-fixture-" + UUID.randomUUID() + ".db"

    @Before fun open() {
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), name)
    }

    @After fun close() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    @Test fun smallProfileHasActualImportHistoryAndDistinctAAndB() = runTest {
        val (a, b) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.SMALL)
        assertEquals(1, b.getJSONObject("imports").getJSONArray("import_batches").length())
        assertEquals(1, b.getJSONObject("imports").getJSONArray("import_child_commands").length())
        assertEquals(1, b.getJSONObject("imports").getJSONArray("import_source_fences").length())
        assertTrue(b.getJSONObject("collections").getJSONArray("transaction_history").length() > 0)
        assertEquals(4, transactions(a).count { it.isNull("deletedAt") })
        assertEquals(3, transactions(b).count { it.isNull("deletedAt") })
        assertEquals(1, transactions(b).count { !it.isNull("deletedAt") })
        assertNotEquals(BackupProbeSession.semanticDigest(a), BackupProbeSession.semanticDigest(b))
    }

    @Test fun recordLimitCountsAllEvidenceAndFencesAndRejectsOneMore() = runTest {
        val (_, b) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.RECORD_LIMIT)
        assertEquals(50_000, b.getJSONObject("manifest").getInt("recordTotal"))
        assertEquals(4, transactions(b).size)
        b.getJSONArray("operationFences").put(BackupProbeFixtures.id(9_000_000))
        b.put("manifest", BackupSnapshotAdapter.manifest(b, b.getJSONObject("manifest").getString("submissionCoverage"), b.getJSONObject("manifest").getString("sourceMappings")))
        assertEquals(50_001, b.getJSONObject("manifest").getInt("recordTotal"))
        assertFailsWith<BackupException> { BackupValidator(clock).validate(BackupProbeFixtures.bytes(b)) }
    }

    @Test fun mixedProfileIsValidAtBothExactLimits() = runTest {
        val (a, b) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.MIXED_LIMIT)
        val bytes = BackupProbeFixtures.bytes(b)
        assertEquals(8_388_608, bytes.size)
        assertEquals(50_000, b.getJSONObject("manifest").getInt("recordTotal"))
        BackupValidator(clock).validate(bytes)
        BackupValidator(clock).validate(BackupProbeFixtures.bytes(a))
        assertEquals(201, transactions(b).count { it.isNull("deletedAt") })
        val originals = b.getJSONArray("originals").values().map { it as JSONObject }
        assertEquals(2 * 1024 * 1024L, originals.sumOf { it.getString("byteLength").toLong() })
        assertTrue(b.getJSONObject("imports").getJSONArray("import_rows").length() > 0)
        bytes.fill(0)
    }

    private fun transactions(root: JSONObject) = root.getJSONObject("collections").getJSONArray("transactions").values().map { it as JSONObject }
}
