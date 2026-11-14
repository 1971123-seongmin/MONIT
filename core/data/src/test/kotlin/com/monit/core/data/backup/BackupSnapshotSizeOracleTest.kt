package com.monit.core.data.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.model.*
import java.lang.reflect.InvocationTargetException
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 검증 전 Room/과거 증빙의 JSON-native 값도 플랫폼 serializer와 같은 길이·거부 경계를 가진다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class, manifest = Config.NONE)
class BackupSnapshotSizeOracleTest {
    private fun id(number: Int) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 11, 13)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var database: LedgerDatabase

    @Before fun open() {
        app = RuntimeEnvironment.getApplication()
        database = createLedgerDatabase(app, "snapshot-size-oracle.db")
    }

    @After fun close() {
        database.close()
        app.deleteDatabase("snapshot-size-oracle.db")
    }

    private fun assertSerializerSize(value: JSONObject) {
        assertEquals(BackupJson.utf8ByteCount(value.toString()), BackupJsonSize.count(value))
    }

    /** 실제 writer 캡처와 전체 복원 후 archived evidence의 행·root를 원래 serializer와 대조한다. */
    @Test fun roomAndRestoredEvidenceMatchTheExistingSerializer() = runTest {
        val ledger = RoomLedgerRepository(database, clock, contexts)
        ledger.initialize(context)
        ledger.createAccount(context, id(110), CreateAccount(id(10), id(11), "합성 /\"\\\n가é😀", AccountNature.GENERAL, day.minusDays(1), "미확인\u0001"))
        ledger.confirmAnchor(context, id(120), ConfirmAnchor(id(10), id(11), 1, day.minusDays(1), 1000, ""))
        ledger.createTransaction(context, id(130), CreateTransaction(id(30), TransactionKind.INCOME, day, 300, creditAccountId = id(10), memo = "\t\b\u000C/\"\\가😀"))
        val adapter = BackupSnapshotAdapter(database, clock)
        val original = adapter.capture(context)
        val backup = RoomBackupRepository(database, clock, contexts)
        fun password() = "synthetic-backup-password-2026".toCharArray()
        val export = backup.create(context, password())
        val preview = backup.inspect(context, export.encrypted, password())
        val recovery = object : RecoveryCopyStore {
            override suspend fun protect(context: LedgerContext, payload: ByteArray) = RecoveryCopyProof(id(999), BackupJson.digest(payload), payload.size)
        }
        val result = backup.restore(context, preview.id, recovery)
        assertEquals(3, database.backupDao().evidence(result.context.ledgerId).size)
        val restored = adapter.capture(result.context)
        for (root in listOf(original, restored)) {
            assertSerializerSize(root)
            for (table in BackupLayout.finance) root.getJSONObject("collections").list(table).forEach(::assertSerializerSize)
            for (item in root.list("submissionsEvidence")) {
                assertSerializerSize(item)
                for (table in BackupLayout.evidence) if (!item.isNull(table)) assertSerializerSize(item.getJSONObject(table))
            }
        }
        BackupValidator(clock).validate(BackupJson.utf8(restored.toString()))
    }

    /** strict 파싱된 과거 JSON 숫자·null·escape도 검증 전 길이와 일치하며 비유한 수는 유입 전에 거부한다. */
    @Test fun parsedNativeValuesAndNonfiniteRejectionKeepThePlatformContract() = runTest {
        val root = BackupJson.parseText("""{"integer":-9223372036854775808,"decimal":1.25,"exponent":1e-9,"negativeZero":-0.0,"flag":true,"missing":null,"text":"/\\\"\t\u0001가😀","array":[null,false,9223372036854775807]}""")
        assertSerializerSize(root)
        assertTrue(root.isNull("missing"))
        assertSerializerSize(JSONObject().put("null", JSONObject.NULL).put("integer", 7).put("array", JSONArray().put(JSONObject.NULL)))
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<JSONException> { JSONObject().put("number", number) }
            assertFailsWith<JSONException> { JSONObject.numberToString(number) }
        }
        for (raw in listOf("{\"n\":NaN}", "{\"n\":Infinity}", "{\"n\":-Infinity}")) {
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.parseText(raw) }.problem)
        }
        assertFailsWith<IllegalStateException> { BackupJsonSize.count(JSONObject().put("notNative", Any())) }
    }

    // 제품 API를 넓히지 않고 실제 private budget의 limit/오류 순서를 시험한다.
    private fun rowBudget(): (JSONObject) -> Unit {
        val type = Class.forName("com.monit.core.data.backup.SnapshotBudget")
        val instance = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val add = type.getDeclaredMethod("add", JSONObject::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        return { row ->
            try {
                add.invoke(instance, row, 1)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }

    /** 행 크기 8MiB±1과 초과 뒤의 잘못된 Unicode는 기존처럼 전체 Unicode 검사 후 판정한다. */
    @Test fun rowByteBoundaryAndUnicodeFailureOrderRemainExact() {
        for (delta in -1..1) {
            val expected = BackupEnvelope.MAX_PAYLOAD + delta
            val row = JSONObject().put("value", "x".repeat(expected - "{\"value\":\"\"}".length))
            assertEquals(expected.toLong(), BackupJson.utf8ByteCount(row.toString()))
            assertSerializerSize(row)
            val add = rowBudget()
            if (delta <= 0) add(row) else assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { add(row) }.problem)
        }
        for (malformed in listOf("\uD800", "\uDC00", "\uDC00\uD800")) {
            val row = JSONObject().put("value", "x".repeat(BackupEnvelope.MAX_PAYLOAD) + malformed)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.utf8ByteCount(row.toString()) }.problem)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { rowBudget()(row) }.problem)
        }
    }

    /** 49,999/50,000건은 허용하고 50,001번째 행도 byte/Unicode 검사 뒤 record 제한을 적용한다. */
    @Test fun rowRecordBoundaryDoesNotMoveBeforeUnicodeValidation() {
        val add = rowBudget()
        repeat(49_999) { add(JSONObject()) }
        add(JSONObject())
        assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { add(JSONObject().put("value", "\uD800")) }.problem)
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { add(JSONObject()) }.problem)
    }

    /** root의 실제 manifest record 제한은 행 budget 밖인 전역 fence도 포함하고 50,001건을 거부한다. */
    @Test fun rootRecordBoundaryIncludesFences() = runTest {
        RoomLedgerRepository(database, clock, contexts).initialize(context)
        database.openHelper.writableDatabase.run {
            beginTransaction()
            try {
                compileStatement("INSERT INTO operation_fences(operationId) VALUES(?)").use { insert ->
                    repeat(49_999) { number ->
                        insert.bindString(1, id(number))
                        insert.executeInsert()
                    }
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        val adapter = BackupSnapshotAdapter(database, clock)
        assertEquals(49_999, adapter.capture(context).getJSONObject("manifest").getInt("recordTotal"))
        database.backupDao().fence(OperationFenceEntity(id(50_000)))
        assertEquals(50_000, adapter.capture(context).getJSONObject("manifest").getInt("recordTotal"))
        database.backupDao().fence(OperationFenceEntity(id(50_001)))
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { adapter.capture(context) }.problem)
    }
}
