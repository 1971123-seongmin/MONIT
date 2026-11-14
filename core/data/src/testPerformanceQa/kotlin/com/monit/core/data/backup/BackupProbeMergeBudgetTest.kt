package com.monit.core.data.backup

import android.app.Application
import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.LedgerDatabase
import com.monit.core.data.createLedgerDatabase
import com.monit.core.model.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupProbeMergeBudgetTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val context = LedgerContext(BackupProbeFixtures.id(1), BackupProbeFixtures.id(2))
    private val name = "probe-merge-budget.db"
    private lateinit var database: LedgerDatabase

    @Before fun open() {
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), name)
    }

    @After fun close() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    /** 유효한 B로 최종 union8MiB±1을 만들고 기존 serializer·새 판정·원본 불변을 대조한다. */
    @Test fun validInputUnionMatchesExactByteBoundaryAndOldOracle() = runTest {
        val (_, full) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.MIXED_LIMIT)
        val spare = BackupProbeFixtures.id(1_000_000)
        assertTrue(full.list("submissionsEvidence").none { it.text("operationId") == spare })
        val fences = full.getJSONArray("operationFences")
        fences.remove((0 until fences.length()).single { fences.getString(it) == spare })
        refreshManifest(full)
        assertEquals(49_999, full.getJSONObject("manifest").getInt("recordTotal"))
        assertEquals(BackupEnvelope.MAX_PAYLOAD - 39, BackupProbeFixtures.bytes(full).size)
        val previous = setOf(BackupProbeFixtures.id(9_000_000))
        for (delta in -1..1) {
            val root = JSONObject(full.toString())
            val file = root.list("originals").single { it.text("id") == BackupProbeFixtures.id(9990) }
            file.put(
                "fileName",
                when (delta) {
                    -1 -> file.text("fileName").dropLast(1)
                    1 -> file.text("fileName") + "p"
                    else -> file.text("fileName")
                },
            )
            val plain = BackupProbeFixtures.bytes(root)
            val source = try {
                BackupValidator(clock).validate(plain)
            } finally {
                plain.fill(0)
            }
            val before = source.root.toString()
            val imports = source.root.getJSONObject("imports").list("import_source_fences")
            val old = JSONObject(before)
            old.put("operationFences", JSONArray((previous + old.getJSONArray("operationFences").values().map { it as String }).sorted()))
            refreshManifest(old)
            assertEquals((BackupEnvelope.MAX_PAYLOAD + delta).toLong(), BackupJson.utf8ByteCount(old.toString()))
            val result = runCatching { BackupMergeBudget.requireWithinLimits(source, previous, imports) }
            assertEquals(delta <= 0, result.isSuccess)
            result.exceptionOrNull()?.let { assertEquals(BackupProblem.LIMIT, (it as BackupException).problem) }
            assertEquals(before, source.root.toString())
        }
    }

    /** 실제 Room 복원의 A+B fence와 source cache 불변·원래 A hash·고정 관측 경계를 확인한다. */
    @Test fun successfulApplyMatchesCheckedUnionAndKeepsSourceUnchanged() = runTest {
        val (_, incoming) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.SMALL)
        val historical = BackupProbeFixtures.id(9_000_001)
        database.withTransaction { database.backupDao().fence(OperationFenceEntity(historical)) }
        val expectedA = BackupJson.digest(BackupProbeFixtures.bytes(BackupSnapshotAdapter(database, clock).capture(context)))
        val repository = RoomBackupRepository(database, clock, contexts)
        val encrypted = BackupEnvelope.encrypt(BackupProbeFixtures.bytes(incoming), BackupProbeFixtures.password())
        repository.workProbe.configure()
        val preview = repository.inspect(context, encrypted, BackupProbeFixtures.password())
        val cache = RoomBackupRepository::class.java.getDeclaredField("inspected").apply { isAccessible = true }.get(repository) as Pair<*, *>
        val cachedRoot = (cache.second as ValidatedBackup).root
        val before = cachedRoot.toString()
        var borrowed: ByteArray? = null
        val recovery = object : RecoveryCopyStore {
            override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof {
                borrowed = payload
                assertEquals(expectedA, BackupJson.digest(payload))
                return RecoveryCopyProof(UUID.randomUUID().toString(), BackupJson.digest(payload), payload.size)
            }
        }
        val result = repository.restore(context, preview.id, recovery)
        assertEquals(before, cachedRoot.toString())
        assertTrue(checkNotNull(borrowed).all { it == 0.toByte() })
        val expected = incoming.getJSONArray("operationFences").values().map { it as String }.toSet() + historical
        val actual = database.openHelper.readableDatabase.query("SELECT operationId FROM operation_fences").use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(expected, actual)
        assertEquals(incoming.getJSONObject("imports").list("import_source_fences").size, BackupSnapshotAdapter(database, clock).capture(result.context).getJSONObject("imports").list("import_source_fences").size)
        for (phase in listOf("MERGED_LIMITS", "RESTORE_PLAN")) assertEquals(listOf(true, false), repository.workProbe.snapshot().filter { it.event == phase }.map { it.start })
        assertTrue(repository.workProbe.snapshot().size < BackupWorkProbe.CAPACITY)
        encrypted.fill(0)
    }

    /** proof hash 불일치 시 A plain을 소거하고 union/교체를 시작하지 않는다. */
    @Test fun badProofWipesCurrentPlainAndNeverStartsMergedOrReplacement() = runTest {
        val (_, incoming) = BackupProbeFixtures(database, clock, contexts).create(ProbeProfile.SMALL)
        val repository = RoomBackupRepository(database, clock, contexts)
        val before = BackupProbeSession.semanticDigest(BackupSnapshotAdapter(database, clock).capture(context))
        val preview = repository.inspect(context, BackupEnvelope.encrypt(BackupProbeFixtures.bytes(incoming), BackupProbeFixtures.password()), BackupProbeFixtures.password())
        repository.workProbe.configure()
        var borrowed: ByteArray? = null
        val recovery = object : RecoveryCopyStore {
            override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof {
                borrowed = payload
                return RecoveryCopyProof(UUID.randomUUID().toString(), "0".repeat(64), payload.size)
            }
        }
        assertEquals(BackupProblem.RECOVERY_COPY, assertFailsWith<BackupException> { repository.restore(context, preview.id, recovery) }.problem)
        assertTrue(checkNotNull(borrowed).all { it == 0.toByte() })
        assertEquals(before, BackupProbeSession.semanticDigest(BackupSnapshotAdapter(database, clock).capture(context)))
        assertTrue(repository.workProbe.snapshot().none { it.event in setOf("MERGED_LIMITS", "RESTORE_PLAN", "REPLACEMENT") })
    }

    private fun refreshManifest(root: JSONObject) {
        root.put("manifest", BackupSnapshotAdapter.manifest(root, root.getJSONObject("manifest").text("submissionCoverage"), root.getJSONObject("imports").text("coverage")))
    }
}
