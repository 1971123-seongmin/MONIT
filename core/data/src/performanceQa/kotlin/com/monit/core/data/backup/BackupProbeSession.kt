package com.monit.core.data.backup

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.os.SystemClock
import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.data.importing.canonicalImport
import com.monit.core.data.importing.importObject
import com.monit.core.model.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ProbeFailure { NONE, PARTIAL_SQL_FULL, BEFORE_COMMIT_CANCEL, AFTER_COMMIT_RESPONSE }

/** 실제 blocking JCA 앞의 bounded 시험 gate. 성능 표본에 사용하지 않는다. */
class ProbeKdfGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    internal fun await() {
        entered.countDown()
        check(release.await(20, TimeUnit.SECONDS)) { "Synthetic KDF gate was not released" }
    }
}

/** 해당 APK/자체 DB 이름만 허용하는 계측 전용 진입점. 일반 변형에는 이 클래스가 없다. */
class BackupProbeSession private constructor(
    private val database: LedgerDatabase,
    val databaseFile: File,
    val context: LedgerContext,
    val pack: ProbeFixturePack,
) : Closeable {
    private val ledger = RoomLedgerRepository(database, CLOCK, CONTEXTS)
    private val implementation = RoomBackupRepository(database, CLOCK, CONTEXTS)
    val repository: BackupRepository get() = implementation
    var encryptionStartedNanos: Long = 0
        private set
    var encryptionEndedNanos: Long = 0
        private set

    fun record(failure: ProbeFailure = ProbeFailure.NONE, gate: ProbeKdfGate? = null) {
        encryptionStartedNanos = 0
        encryptionEndedNanos = 0
        val point = when {
            gate != null -> BackupCheckpoint.BEFORE_KDF
            failure == ProbeFailure.PARTIAL_SQL_FULL -> BackupCheckpoint.AFTER_PARTIAL_REPLACE
            failure == ProbeFailure.BEFORE_COMMIT_CANCEL -> BackupCheckpoint.BEFORE_COMMIT
            failure == ProbeFailure.AFTER_COMMIT_RESPONSE -> BackupCheckpoint.AFTER_COMMIT
            else -> null
        }
        implementation.workProbe.configure(point) {
            when {
                gate != null -> gate.await()
                failure == ProbeFailure.PARTIAL_SQL_FULL -> throw SQLiteFullException("Synthetic transaction failure")
                failure == ProbeFailure.BEFORE_COMMIT_CANCEL -> throw CancellationException("Synthetic pre-commit cancellation")
                failure == ProbeFailure.AFTER_COMMIT_RESPONSE -> throw IOException("Synthetic post-commit response loss")
            }
        }
    }

    fun events(): List<ProbeRecord> = implementation.workProbe.snapshot()
    fun providers(): Map<String, String> = implementation.workProbe.providerSnapshot()

    fun observerPairOverheadNanos(): Long {
        implementation.workProbe.configure()
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(1000) {
            implementation.workProbe.start(BackupProbeEvent.PARSE)
            implementation.workProbe.end(BackupProbeEvent.PARSE)
        }
        return (SystemClock.elapsedRealtimeNanos() - start) / 1000
    }

    suspend fun lastVerifiedExportId(): String? = database.withTransaction { database.backupDao().status()?.lastBackupExportId }

    /** JCA 본체를 그대로 호출한다. 읽기 준비와 암호 구간을 event로 구별하고 암호를 지운다. */
    suspend fun encryptSource(password: CharArray): ByteArray {
        var plain: ByteArray? = null
        return try {
            val input = withContext(CONTEXTS.io) {
                check(pack.source.length() in 1L..BackupEnvelope.MAX_PAYLOAD.toLong())
                pack.source.inputStream().use { BackupEnvelope.readBounded(it) }
            }.also { plain = it }
            withContext(CONTEXTS.default) {
                check(BackupJson.digest(input) == pack.info.sourceSha256)
                encryptionStartedNanos = SystemClock.elapsedRealtimeNanos()
                try {
                    BackupEnvelope.encrypt(input, password, implementation.workProbe)
                } finally {
                    encryptionEndedNanos = SystemClock.elapsedRealtimeNanos()
                }
            }
        } finally {
            plain?.fill(0)
            password.fill('\u0000')
        }
    }

    suspend fun state(value: LedgerContext = context): ProbeState = withContext(CONTEXTS.default) {
        val root = BackupSnapshotAdapter(database, CLOCK).capture(value)
        val snapshot = ledger.snapshot(value, DAY)
        val transactions = root.getJSONObject("collections").getJSONArray("transactions").values().map { it as JSONObject }
        ProbeState(semanticDigest(root), root.getJSONObject("manifest").getInt("recordTotal"), transactions.count { it.isNull("deletedAt") }, transactions.count { !it.isNull("deletedAt") }, snapshot.income, snapshot.expense, value.storageGeneration)
    }

    override fun close() = database.close()

    /** 새 DB를 만들거나 A를 다시 주입하지 않고 같은 자체 파일의 영속 결과를 재조회한다. */
    fun reopen(app: Context): BackupProbeSession {
        guard(app)
        check(databaseFile.name.startsWith("backup-perf-72-") && databaseFile.name.endsWith(".db"))
        check(databaseFile.isFile && app.getDatabasePath(databaseFile.name).canonicalFile == databaseFile.canonicalFile)
        return BackupProbeSession(createLedgerDatabase(app, databaseFile.name), databaseFile, context, pack)
    }

    companion object {
        const val APPLICATION_ID = "com.monit.app.qa.performance"
        private val CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
        private val DAY = LocalDate.of(2026, 11, 14)
        private val CONTEXTS = object : CoroutineContextProvider {
            override val main = Dispatchers.Main
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }

        suspend fun generate(app: Context, runId: String, profile: ProbeProfile): ProbeFixturePack = withContext(CONTEXTS.default) {
            guard(app)
            UUID.fromString(runId)
            val directory = File(app.filesDir, "backup-probe-72/$runId")
            check(!directory.exists() && directory.mkdirs()) { "Run evidence already exists" }
            val name = "backup-perf-72-$runId-builder.db"
            check(app.getDatabasePath(name).canonicalFile.parentFile == File(app.applicationInfo.dataDir, "databases").canonicalFile)
            check(!app.getDatabasePath(name).exists())
            val database = createLedgerDatabase(app, name)
            try {
                val (current, source) = BackupProbeFixtures(database, CLOCK, CONTEXTS).create(profile)
                val a = File(directory, "current-A.public-synthetic.json")
                val b = File(directory, "source-B.public-synthetic.json")
                withContext(CONTEXTS.io) {
                    a.writeBytes(BackupProbeFixtures.bytes(current))
                    b.writeBytes(BackupProbeFixtures.bytes(source))
                }
                val sourceBytes = BackupProbeFixtures.bytes(source)
                val counts = source.getJSONObject("manifest").getJSONObject("recordCounts")
                val income = if (profile == ProbeProfile.MIXED_LIMIT) 200L else 2L
                val info = ProbeFixtureInfo(profile.name, sourceBytes.size, source.getJSONObject("manifest").getInt("recordTotal"), counts.keys().asSequence().associateWith(counts::getInt), BackupJson.digest(sourceBytes), income, 5000, income.toInt() + 1, 1, semanticDigest(source))
                sourceBytes.fill(0)
                ProbeFixturePack(directory, a, b, info)
            } finally {
                database.close()
            }
        }

        suspend fun open(app: Context, pack: ProbeFixturePack, sample: Int, sourceState: Boolean = false): BackupProbeSession = withContext(CONTEXTS.default) {
            guard(app)
            require(sample in 0..999)
            val root = File(app.filesDir, "backup-probe-72").canonicalFile
            check(pack.directory.canonicalFile.parentFile == root)
            check(pack.current.canonicalFile.parentFile == pack.directory.canonicalFile && pack.source.canonicalFile.parentFile == pack.directory.canonicalFile)
            val runId = pack.directory.name.also(UUID::fromString)
            val name = "backup-perf-72-$runId-$sample.db"
            val file = app.getDatabasePath(name).canonicalFile
            check(file.parentFile == File(app.applicationInfo.dataDir, "databases").canonicalFile)
            check(!file.exists()) { "Sample database already exists" }
            val database = createLedgerDatabase(app, name)
            try {
                val raw = withContext(CONTEXTS.io) {
                    val input = if (sourceState) pack.source else pack.current
                    check(input.length() in 1L..BackupEnvelope.MAX_PAYLOAD.toLong())
                    input.inputStream().use(BackupEnvelope::readBounded)
                }
                val validated = try {
                    BackupValidator(CLOCK).validate(raw)
                } finally {
                    raw.fill(0)
                }
                val value = RestoreResult(UUID.randomUUID().toString(), LedgerContext(validated.context.ledgerId, UUID.randomUUID().toString()), validated.createdAt.toString(), CLOCK.instant().toString(), validated.root.getJSONObject("manifest").getInt("recordTotal"), false)
                val plan = BackupRestorePlan(validated, value, emptySet())
                try {
                    database.withTransaction { plan.apply(database) }
                } finally {
                    plan.clear()
                }
                BackupProbeSession(database, file, value.context, pack).also { it.repository.acknowledgeRestore(value.id) }
            } catch (error: Throwable) {
                database.close()
                throw error
            }
        }

        fun password(): CharArray = BackupProbeFixtures.password()

        /** 실제 API의 serializer와 길이를 대조하고 합성 case 개수만 반환한다. heap 표본에는 호출하지 않는다. */
        fun verifyPlatformJsonSizes(): Int {
            val values = mutableListOf<Any?>(null, JSONObject.NULL, true, false, 0, -0.0, Long.MIN_VALUE, Long.MAX_VALUE, 1.0e-9, Double.MAX_VALUE)
            for (code in 0..127) values += code.toChar().toString()
            values.addAll(listOf("", "가é", "\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF", "\u2028\u2029", "\uD800\uDC00\uDBFF\uDFFF", "</\\\"\n"))
            for (value in values) {
                val root = JSONObject().put("key/\\\n", value ?: JSONObject.NULL).put("array", JSONArray().put(value))
                check(BackupJsonSize.count(root) == BackupJson.utf8ByteCount(root.toString()))
            }
            var nested = JSONObject().put("value", "합성/\\\uD83D\uDE00")
            repeat(14) { nested = JSONObject().put("nested", nested) }
            check(BackupJsonSize.count(nested) == BackupJson.utf8ByteCount(nested.toString()))
            for (invalid in listOf("\uD800", "\uDC00")) {
                check((runCatching { BackupJsonSize.count(invalid) }.exceptionOrNull() as? BackupException)?.problem == BackupProblem.INVALID_PAYLOAD)
                check((runCatching { BackupJson.utf8ByteCount(JSONObject.quote(invalid)) }.exceptionOrNull() as? BackupException)?.problem == BackupProblem.INVALID_PAYLOAD)
            }
            return values.size + 3
        }

        /** 공개 OpenSSL vector와 실제 플랫폼 원문을 비교한다. 비밀번호/키/원문은 반환하지 않는다. */
        fun verifyPlatformVectors(): Int {
            val input = checkNotNull(BackupProbeSession::class.java.getResourceAsStream("/backup/crypto-vectors.json"))
            val vectors = input.use { JSONObject(it.reader(Charsets.UTF_8).readText()).getJSONArray("vectors") }
            for (value in vectors.values().map { it as JSONObject }) {
                val password = value.getString("password").toCharArray()
                val payload = hex(value.getString("payloadHex"))
                val expected = hex(value.getString("fileHex"))
                try {
                    check(BackupEnvelope.seal(payload, password, hex(value.getString("saltHex")), hex(value.getString("nonceHex"))).contentEquals(expected)) { "Platform encryption vector mismatch" }
                    val opened = BackupEnvelope.decrypt(expected, password)
                    try {
                        check(opened.contentEquals(payload)) { "Platform decryption vector mismatch" }
                    } finally {
                        opened.fill(0)
                    }
                } finally {
                    password.fill('\u0000')
                    payload.fill(0)
                    expected.fill(0)
                }
            }
            return vectors.length()
        }

        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** restore의 명시된 새 세대/증거 전용 표시만 정규화하고 금융·원문·보호 이력은 전부 대조한다. */
        internal fun semanticDigest(root: JSONObject): String {
            val imports = JSONObject(root.getJSONObject("imports").toString())
            for (batch in imports.getJSONArray("import_batches").values().map { it as JSONObject }) batch.put("evidenceOnly", true).put("acknowledged", true)
            return BackupJson.digest(
                canonicalImport(
                    importObject(
                        "collections" to root.getJSONObject("collections"),
                        "evidence" to root.getJSONArray("submissionsEvidence"),
                        "operationFences" to root.getJSONArray("operationFences"),
                        "originals" to root.getJSONArray("originals"),
                        "imports" to imports,
                        "reminderPreferences" to root.getJSONObject("reminderPreferences"),
                    ),
                ),
            )
        }

        private fun guard(app: Context) {
            require(app.packageName == APPLICATION_ID) { "Synthetic instrumentation package required" }
            require(app.filesDir.canonicalFile.isDirectory)
            require(app.filesDir.canonicalFile.parentFile == File(app.applicationInfo.dataDir).canonicalFile)
        }
    }
}
