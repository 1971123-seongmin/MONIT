package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.math.BigDecimal
import java.util.Random
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API26/28의 실제 JSONObject 표현을 oracle로 사용하며 새 출력 형식은 정의하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class, manifest = Config.NONE)
class BackupJsonSizeTest {
    /** quote·escape·UTF-8의 독립 기대값으로 단순 길이 계산의 기준을 확인한다. */
    @Test fun scalarLengthsIncludeExactEscapesAndUnicodeWidths() {
        for ((value, expected) in listOf("" to 2L, "/" to 4L, "\\" to 4L, "\n" to 4L, "\u0000" to 8L, "가" to 5L, "\uD83D\uDE00" to 6L)) {
            assertEquals(expected, BackupJsonSize.count(value))
        }
        assertEquals(4L, BackupJsonSize.count(null))
        assertEquals(4L, BackupJsonSize.count(JSONObject.NULL))
        assertEquals(4L, BackupJsonSize.count(true))
        assertEquals(5L, BackupJsonSize.count(false))
    }

    /** 모든 유효 BMP 문자와 control/slash를 해당 API의 quote 결과와 대조한다. */
    @Test fun everyValidBmpCharacterMatchesPlatformQuote() {
        for (code in 0..0xFFFF) {
            if (code in 0xD800..0xDFFF) continue
            val value = code.toChar().toString()
            assertEquals(JSONObject.quote(value).toByteArray(Charsets.UTF_8).size.toLong(), BackupJsonSize.count(value), "code=$code")
        }
    }

    /** 고정 비BMP·여러 escape·깊이16·숫자·null/빈 배열을 기존 전체 serializer와 비교한다. */
    @Test fun nestedAndSupplementaryValuesMatchExistingSerializer() {
        val random = Random(72)
        val text = buildString {
            append("\"\\/\b\u000C\n\r\t\u0001가é")
            repeat(1024) { append(String(Character.toChars(0x10000 + random.nextInt(0x100000)))) }
        }
        val numbers = JSONArray().put(0).put(-0.0).put(Long.MIN_VALUE).put(Long.MAX_VALUE).put(1.0e-9).put(Double.MAX_VALUE).put(BigDecimal("123.4500"))
        var value: Any = JSONObject().put("text/\n", text).put("numbers", numbers).put("null", JSONObject.NULL).put("empty", JSONArray())
        repeat(14) { value = if (it % 2 == 0) JSONArray().put(value) else JSONObject().put("nested", value) }
        assertEquals(value.toString().toByteArray(Charsets.UTF_8).size.toLong(), BackupJsonSize.count(value))
    }

    /** 단독 surrogate는 기존 엄격 UTF-8 검사와 마찬가지로 대체하지 않고 거부한다. */
    @Test fun malformedSurrogatesFailForValuesAndKeys() {
        for (text in listOf("\uD800", "\uDC00", "x\uD800y", "\uDC00\uD800")) {
            val value = JSONObject().put("value", text)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.utf8ByteCount(value.toString()) }.problem)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJsonSize.count(value) }.problem)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJsonSize.count(JSONObject().put(text, 1)) }.problem)
        }
    }

    /** 두 부모의 세 경로만 대체하고 원본/공유 sibling을 바꾸지 않으며 manifest 자리수를 포함한다. */
    @Test fun readOnlyReplacementsMatchDeepCloneOracle() {
        val root = JSONObject().put("operationFences", JSONArray().put("old"))
            .put("imports", JSONObject().put("import_source_fences", JSONArray()).put("sibling", "\\/가"))
            .put("manifest", JSONObject().put("recordTotal", 9)).put("shared", JSONArray().put(JSONObject().put("value", true)))
        val before = root.toString()
        val operations = JSONArray().put("old").put("new")
        val importRows = JSONArray().put(JSONObject().put("identity", "x"))
        val manifest = JSONObject().put("recordTotal", 100).put("coverage", "RECORDED")
        val old = JSONObject(before).put("operationFences", operations).put("manifest", manifest)
        old.getJSONObject("imports").put("import_source_fences", importRows)
        val imports = root.getJSONObject("imports")
        val actual = BackupJsonSize.count(root) { owner, key ->
            when {
                owner === root && key == "operationFences" -> operations
                owner === root && key == "manifest" -> manifest
                owner === imports && key == "import_source_fences" -> importRows
                else -> owner.get(key)
            }
        }
        assertEquals(old.toString().toByteArray(Charsets.UTF_8).size.toLong(), actual)
        assertEquals(before, root.toString())
    }

    /** 8MiB±1을 정확히 세며 큰 leaf도 별도 quote String을 요구하지 않는다. */
    @Test fun byteBoundaryDoesNotRoundOrTruncate() {
        for (delta in -1..1) {
            val expected = BackupEnvelope.MAX_PAYLOAD + delta
            val text = "a".repeat(expected - 2)
            assertEquals(expected.toLong(), BackupJsonSize.count(text))
        }
    }
}
