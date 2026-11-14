package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class BackupPredicateReuseTest {
    private val uuid = "abcdef01-abcd-4abc-8abc-abcdef012345"
    private fun minimal(): JSONObject = JSONObject(checkNotNull(javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")).use { it.reader().readText() })

    /** 같은 패턴의 여러 필드/호출에서 find 위치를 초기화하고 잘못된 ASCII·Unicode UUID를 거부한다. */
    @Test fun schemaMatcherResetsForEveryValueAndCall() {
        val schema = BackupSchema.bundled(1)
        repeat(3) {
            schema.validate(minimal().also { it.getJSONObject("ledger").put("id", uuid).put("storageGeneration", uuid) })
            for (invalid in listOf(uuid.uppercase(), "ａ" + uuid.drop(1), uuid.dropLast(1), uuid + "x")) {
                val value = minimal().also { it.getJSONObject("ledger").put("storageGeneration", invalid) }
                assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { schema.validate(value) }.problem)
                schema.validate(minimal())
            }
        }
    }

    /**
     * 목적: 기존 containsMatchIn의 $ 앞 개행 의미와 뒤의 엄격한 domain 검사를 함께 보존한다.
     * 조건: 고정 UUID $ pattern과 개행이 붙은 source ID를 가진 합성 v3 입력.
     * 절차: schema find 판정 뒤 전체 validator를 호출한다.
     * 기대: schema 단계의 기존 언어는 동일하고 전체 복원 검증은 잘못된 UUID를 여전히 거부한다.
     */
    @Test fun schemaFindMeaningAndStrictDomainRejectionAreBothPreserved() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneOffset.UTC)
        val value = BackupValidator(clock).validate(BackupJson.utf8(minimal().toString())).root
        value.getJSONObject("sourceLedger").put("id", uuid + "\n")
        BackupSchema.bundled(3).validate(value)
        assertFailsWith<BackupException> { BackupValidator(clock).validate(BackupJson.utf8(value.toString())) }
    }

    /**
     * 목적: 같은 schema의 동시 검증 호출이 mutable Matcher를 공유하지 않는다.
     * 조건: 같은 immutable schema와 두 실제 worker, 각각 독립 JSON 입력.
     * 절차: bounded gate에서 함께 시작해 정상/불량 입력을 번갈아 검사하고 모든 worker를 join한다.
     * 기대: 정상 거부/불량 수용이 없고 executor가 반드시 종료한다. 기기 메모리 성능의 대역은 아니다.
     */
    @Test fun concurrentCallsKeepIndependentMatchState() {
        val schema = BackupSchema.bundled(1)
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..1).map { worker ->
                pool.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    repeat(100) { index ->
                        val value = minimal()
                        value.getJSONObject("ledger").put("id", if (worker == index % 2) uuid else uuid.uppercase())
                        if (worker == index % 2) schema.validate(value)
                        else assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { schema.validate(value) }.problem)
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    /** JSON 숫자의 부호·소수·지수는 유지하고 불완전/비표준·Unicode 숫자를 계속 거부한다. */
    @Test fun strictJsonNumbersKeepTheirAcceptedLanguage() = runTest {
        for (valid in listOf("0", "-0", "1", "-1", "1.25", "1e+2", "1E-3")) {
            assertEquals(1, BackupJson.parseText("{\"numbers\":[$valid]}").getJSONArray("numbers").length())
        }
        for (invalid in listOf("+1", "01", "-01", "1.", "1e", "NaN", "Infinity", "１")) {
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.parseText("{\"number\":$invalid}") }.problem)
        }
    }

    /** 금융 정수 문자열의 full match·Long 경계 및 기존 오류 종류를 보존한다. */
    @Test fun exactLongKeepsFullMatchAndRangeErrors() {
        for (value in listOf(0L, -1L, 1L, Long.MIN_VALUE, Long.MAX_VALUE)) assertEquals(value, JSONObject().put("n", value.toString()).exactLong("n"))
        for (value in listOf("-0", "+1", "01", "-01", "1.0", "1e3", "1\n", "\n1", " 1", "１")) {
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { JSONObject().put("n", value).exactLong("n") }.problem)
        }
        assertFailsWith<NumberFormatException> { JSONObject().put("n", "9223372036854775808").exactLong("n") }
    }
}
