package com.monit.core.data.backup

import android.app.Application
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.model.*
import java.io.IOException
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactorySpi
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

/** 실제 Room/암호 경로의 관찰·오류 주입 회귀. 사본 대역은 실제 Keystore 성공 증거가 아니다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupProbeBoundaryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val name = "pb.db"
    private val activeName = "pba.db"
    private lateinit var database: LedgerDatabase
    private lateinit var backup: RoomBackupRepository
    private lateinit var context: LedgerContext
    private lateinit var encrypted: ByteArray
    private lateinit var source: JSONObject
    private var recoveryCount = 0
    private val recovery = object : RecoveryCopyStore {
        override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof {
            recoveryCount++
            return RecoveryCopyProof(UUID.randomUUID().toString(), BackupJson.digest(payload), payload.size)
        }
    }

    @Before fun open() {
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), name)
    }

    @After fun close() {
        database.close()
        if (::encrypted.isInitialized) encrypted.fill(0)
        RuntimeEnvironment.getApplication().deleteDatabase(name)
        RuntimeEnvironment.getApplication().deleteDatabase(activeName)
    }

    private suspend fun prepare(profile: ProbeProfile = ProbeProfile.SMALL) {
        val (a, b) = BackupProbeFixtures(database, clock, contexts).create(profile)
        source = b
        database.close()
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), activeName)
        val raw = BackupProbeFixtures.bytes(a)
        val valid = try {
            BackupValidator(clock).validate(raw)
        } finally {
            raw.fill(0)
        }
        context = LedgerContext(valid.context.ledgerId, UUID.randomUUID().toString())
        val outcome = RestoreResult(UUID.randomUUID().toString(), context, valid.createdAt.toString(), clock.instant().toString(), valid.root.getJSONObject("manifest").getInt("recordTotal"), false)
        val plan = BackupRestorePlan(valid, outcome, emptySet())
        try {
            database.withTransaction { plan.apply(database) }
        } finally {
            plan.clear()
        }
        backup = RoomBackupRepository(database, clock, contexts)
        val plain = BackupProbeFixtures.bytes(b)
        encrypted = try {
            BackupEnvelope.encrypt(plain, BackupProbeFixtures.password())
        } finally {
            plain.fill(0)
        }
    }

    private suspend fun digest(value: LedgerContext = context): String = BackupProbeSession.semanticDigest(BackupSnapshotAdapter(database, clock).capture(value))

    /**
     * 목적: 부분 교체 뒤 저장 공간 오류도 완전한 A로 rollback한다.
     * 조건: 검증된 A/B와 실제 Room transaction의 accounts 삽입 뒤 SQLiteFullException.
     * 절차: inspect→원래 digest 저장→checkpoint 오류→같은 context로 조회.
     * 기대: A digest/세대·기존 완료 표식 유지, 새 복원 결과0·사본 호출1.
     */
    @Test fun partialSqlFailureLeavesAllOfAAndNoNewReceipt() = runTest {
        prepare()
        val preview = backup.inspect(context, encrypted, BackupProbeFixtures.password())
        val before = digest()
        backup.workProbe.configure(BackupCheckpoint.AFTER_PARTIAL_REPLACE) { throw SQLiteFullException("Synthetic") }
        assertFailsWith<SQLiteFullException> { backup.restore(context, preview.id, recovery) }
        assertEquals(before, digest())
        assertNull(backup.result(preview.id))
        assertEquals(1, recoveryCount)
        assertEquals(1, backup.workProbe.snapshot().count { it.event == "REPLACEMENT" && it.start })
    }

    /**
     * 목적: commit 직전 취소가 완료 표식까지 포함해 rollback한다.
     * 조건: 금융·근거·상태 삽입을 마친 같은 transaction에 독립 CancellationException.
     * 절차: inspect→마지막 checkpoint 취소→새 result와 A 대사.
     * 기대: 취소 전파·A 동일·새 표식0이며 transaction begin/end 관찰도 닫힌다.
     */
    @Test fun cancellationBeforeCommitPreservesAAndPropagates() = runTest {
        prepare()
        val preview = backup.inspect(context, encrypted, BackupProbeFixtures.password())
        val before = digest()
        backup.workProbe.configure(BackupCheckpoint.BEFORE_COMMIT) { throw CancellationException("Synthetic") }
        assertFailsWith<CancellationException> { backup.restore(context, preview.id, recovery) }
        assertEquals(before, digest())
        assertNull(backup.result(preview.id))
        val events = backup.workProbe.snapshot().filter { it.event == "REPLACEMENT" }
        assertEquals(listOf(true, false), events.map { it.start })
    }

    /**
     * 목적: commit 후 응답 유실을 같은 결과 조회로 복구하고 재교체하지 않는다.
     * 조건: 성공 commit/메모리 정리 뒤 IOException, 실제 파일 DB 재열기.
     * 절차: restore 오류→DB 재열기→원래 previewId 조회→같은 요청 재호출.
     * 기대: 완전한 B·새 세대·원래 결과 동일·추가 사본/교체0.
     */
    @Test fun lostResponseAfterCommitRecoversPersistedBWithoutRepeating() = runTest {
        prepare()
        val preview = backup.inspect(context, encrypted, BackupProbeFixtures.password())
        backup.workProbe.configure(BackupCheckpoint.AFTER_COMMIT) { throw IOException("Synthetic") }
        assertFailsWith<IOException> { backup.restore(context, preview.id, recovery) }
        database.close()
        database = createLedgerDatabase(RuntimeEnvironment.getApplication(), activeName)
        backup = RoomBackupRepository(database, clock, contexts)
        val result = assertNotNull(backup.result(preview.id))
        assertNotEquals(context.storageGeneration, result.context.storageGeneration)
        assertEquals(BackupProbeSession.semanticDigest(source), digest(result.context))
        backup.workProbe.configure()
        assertEquals(result, backup.restore(context, preview.id, recovery))
        assertEquals(1, recoveryCount)
        assertTrue(backup.workProbe.snapshot().none { it.event == "REPLACEMENT" })
    }

    @Test fun invalidHeaderNeverStartsKdfOrParsingAndWipesCallerPassword() = runTest {
        prepare()
        val invalid = encrypted.copyOf().also { ByteBuffer.wrap(it).putInt(12, 600_001) }
        val password = BackupProbeFixtures.password()
        val before = digest()
        backup.workProbe.configure()
        assertEquals(BackupProblem.HEADER, assertFailsWith<BackupException> { backup.inspect(context, invalid, password) }.problem)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(backup.workProbe.snapshot().isEmpty())
        assertEquals(before, digest())
    }

    @Test fun injectedKdfProviderFailureKeepsExceptionAndCleanup() = runTest {
        prepare()
        val password = BackupProbeFixtures.password()
        backup.workProbe.configure(BackupCheckpoint.BEFORE_KDF) { throw NoSuchAlgorithmException("Synthetic") }
        assertEquals(BackupProblem.CRYPTO_UNAVAILABLE, assertFailsWith<BackupException> { backup.inspect(context, encrypted, password) }.problem)
        assertTrue(password.all { it == '\u0000' })
        assertEquals(listOf(true, false), backup.workProbe.snapshot().map { it.start })
        assertTrue(backup.workProbe.snapshot().all { it.event == "KDF_DECRYPT" })
    }

    /**
     * 목적: 입력 자체가 유효50,000개여도 보존해야 할 이전 fence가 더해지면 무쓰기 거부한다.
     * 조건: 실제 record-limit source와 source에 없는 A의 역사 UUID1개.
     * 절차: preview→검증 사본→정확 합집합 검사 후 A/결과/교체 관찰을 대조한다.
     * 기대: LIMIT·A 동일·새 결과0·교체 시작0이며 이전 fence를 버려 통과하지 않는다.
     */
    @Test fun mergedRecordLimitStillRejectsAdditionalHistoricalFence() = runTest {
        prepare(ProbeProfile.RECORD_LIMIT)
        assertEquals(50_000, source.getJSONObject("manifest").getInt("recordTotal"))
        mergedLimitLeavesAUnchanged()
    }

    /**
     * 목적: 합집합 건수≤50,000이어도 전체 UTF-8이8MiB를 넘으면 교체 전에 거부한다.
     * 조건: 유효8MiB/49,999 source와 source 밖의 역사 fence1개.
     * 절차: 불필요한 합성 fence1개를 제거하고 허용된 파일 이름으로 byte 상한을 맞춰 재검증한다.
     * 기대: 실제 source는 유효하지만 합집합 byte LIMIT·A 동일·교체/결과0이다.
     */
    @Test fun mergedUtf8LimitStillRejectsWithinRecordLimit() = runTest {
        prepare(ProbeProfile.MIXED_LIMIT)
        val fences = source.getJSONArray("operationFences")
        val spare = BackupProbeFixtures.id(1_000_000)
        assertTrue(source.list("submissionsEvidence").none { it.text("operationId") == spare })
        fences.remove((0 until fences.length()).single { fences.getString(it) == spare })
        val manifest = source.getJSONObject("manifest")
        source.put("manifest", BackupSnapshotAdapter.manifest(source, manifest.text("submissionCoverage"), manifest.text("sourceMappings")))
        val raw = BackupProbeFixtures.bytes(source)
        val padding = BackupEnvelope.MAX_PAYLOAD - raw.size
        raw.fill(0)
        assertTrue(padding in 1..100)
        val file = source.getJSONArray("originals").values().map { it as JSONObject }.single { it.getString("id") == BackupProbeFixtures.id(9990) }
        file.put("fileName", file.getString("fileName") + "p".repeat(padding))
        val plain = BackupProbeFixtures.bytes(source)
        val password = BackupProbeFixtures.password()
        try {
            assertEquals(BackupEnvelope.MAX_PAYLOAD, plain.size)
            assertEquals(49_999, BackupValidator(clock).validate(plain).root.getJSONObject("manifest").getInt("recordTotal"))
            encrypted.fill(0)
            encrypted = BackupEnvelope.encrypt(plain, password)
        } finally {
            password.fill('\u0000')
            plain.fill(0)
        }
        mergedLimitLeavesAUnchanged()
    }

    private suspend fun mergedLimitLeavesAUnchanged() {
        database.withTransaction { database.backupDao().fence(OperationFenceEntity(UUID.randomUUID().toString())) }
        val preview = backup.inspect(context, encrypted, BackupProbeFixtures.password())
        val before = digest()
        backup.workProbe.configure()
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { backup.restore(context, preview.id, recovery) }.problem)
        assertEquals(before, digest())
        assertNull(backup.result(preview.id))
        assertEquals(1, recoveryCount)
        assertEquals(listOf(true, false), backup.workProbe.snapshot().filter { it.event == "MERGED_LIMITS" }.map { it.start })
        assertTrue(backup.workProbe.snapshot().none { it.event == "RESTORE_PLAN" })
        assertTrue(backup.workProbe.snapshot().none { it.event == "REPLACEMENT" })
    }

    /**
     * 목적: 관찰용 provider 조회가 플랫폼의 정상 JCA failover를 고정하지 않는다.
     * 조건: 이 host JVM에만 잘못된 spec을 거부하는 첫 provider를 임시 등록한다.
     * 절차: 실제 envelope encrypt→실제 선택 provider 확인→finally 자체 provider 제거→decrypt.
     * 기대: 첫 provider 호출1 뒤 정상 대체 provider로 암호화 성공·원문 동일. 전역 목록은 복원한다.
     */
    @Test fun providerObservationPreservesJcaFailover() {
        val provider = object : Provider("MonitSynthetic72Fallback", 1.0, "Public synthetic host-only test") {
            init {
                put("SecretKeyFactory.PBKDF2WithHmacSHA256", RejectingPbkdf2::class.java.name)
            }
        }
        RejectingPbkdf2.calls.set(0)
        val probe = BackupWorkProbe().apply { configure() }
        val plain = "public-synthetic-failover".toByteArray()
        val password = BackupProbeFixtures.password()
        var encrypted: ByteArray? = null
        try {
            assertEquals(1, Security.insertProviderAt(provider, 1))
            encrypted = BackupEnvelope.encrypt(plain, password, probe)
            assertEquals(1, RejectingPbkdf2.calls.get())
            assertNotEquals(provider.name, probe.providerSnapshot()["KDF_ENCRYPT"])
        } finally {
            Security.removeProvider(provider.name)
            password.fill('\u0000')
        }
        try {
            val checkPassword = BackupProbeFixtures.password()
            val opened = try {
                BackupEnvelope.decrypt(assertNotNull(encrypted), checkPassword)
            } finally {
                checkPassword.fill('\u0000')
            }
            try {
                assertContentEquals(plain, opened)
            } finally {
                opened.fill(0)
            }
        } finally {
            encrypted?.fill(0)
            plain.fill(0)
        }
    }

    class RejectingPbkdf2 : SecretKeyFactorySpi() {
        override fun engineGenerateSecret(keySpec: KeySpec): SecretKey {
            calls.incrementAndGet()
            throw InvalidKeySpecException("Synthetic first provider refusal")
        }

        override fun engineGetKeySpec(key: SecretKey, keySpec: Class<*>): KeySpec = throw InvalidKeySpecException("Synthetic")
        override fun engineTranslateKey(key: SecretKey): SecretKey = throw InvalidKeyException("Synthetic")

        companion object {
            val calls = AtomicInteger()
        }
    }
}
