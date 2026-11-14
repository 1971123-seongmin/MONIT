package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 길이 검사 최적화가 Unicode·표준 encoder bytes·기존 JSON 한도/오류 우선순위를 바꾸지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class BackupUtf8Test {
    private fun referenceUtf8(text: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun assertMatchesStandard(text: String) {
        val expected = referenceUtf8(text)
        assertEquals(expected.size.toLong(), BackupJson.utf8ByteCount(text))
        assertContentEquals(expected, BackupJson.utf8(text))
    }

    /** ASCII/NUL·한글·결합문자·BMP 경계·비BMP를 변형하거나 정규화하지 않는다. */
    @Test fun unicodeByteWidthsAndExactBytesMatchStandardEncoder() {
        val values = listOf(
            "" to 0L,
            "ASCII\u0000" to 6L,
            "\u007F" to 1L,
            "\u0080" to 2L,
            "\u07FF" to 2L,
            "\u0800" to 3L,
            "\uD7FF" to 3L,
            "\uE000" to 3L,
            "\uFFFF" to 3L,
            "\uD800\uDC00" to 4L,
            "\uDBFF\uDFFF" to 4L,
            "가나다" to 9L,
            "e\u0301" to 3L,
            "\u00E9" to 2L,
            "\uFEFF" to 3L,
        )
        for ((text, bytes) in values) {
            assertEquals(bytes, BackupJson.utf8ByteCount(text))
            assertMatchesStandard(text)
        }
        assertFalse(BackupJson.utf8("e\u0301").contentEquals(BackupJson.utf8("\u00E9")))
    }

    /** 유효한 모든 BMP scalar와 비BMP surrogate 경계 조합을 표준 REPORT encoder와 대조한다. */
    @Test fun allBmpScalarsAndSupplementaryBoundariesMatchStandardEncoder() {
        val text = buildString {
            for (value in 0..0xFFFF) if (value !in 0xD800..0xDFFF) append(value.toChar())
            for (high in listOf('\uD800', '\uD801', '\uDBFE', '\uDBFF')) {
                for (low in listOf('\uDC00', '\uDC01', '\uDFFE', '\uDFFF')) append(high).append(low)
            }
        }
        assertMatchesStandard(text)
    }

    /** 고립·역순·반복 surrogate는 대체문자로 인코딩하지 않고 기존 INVALID_PAYLOAD로 거부한다. */
    @Test fun malformedSurrogatesRemainInvalidInCountAndEncoding() {
        for (text in listOf("\uD800", "\uDC00", "\uD800a", "\uD800\uD800", "\uDC00\uD800", "a\uDBFF", "\uD800\uDC00\uDFFF", "a".repeat(8191) + "\uD800")) {
            assertFailsWith<CharacterCodingException> { referenceUtf8(text) }
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.utf8ByteCount(text) }.problem)
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.utf8(text) }.problem)
        }
    }

    /** 내부 encoder 처리 경계를 가로지르는 정상 surrogate pair도 끊거나 누락하지 않는다. */
    @Test fun validPairsAcrossBufferBoundariesRemainExact() {
        for (offset in listOf(0, 8191, 8192, 16383)) assertMatchesStandard("a".repeat(offset) + "\uD800\uDC00가\uDBFF\uDFFF")
    }

    /** raw JSON은 ASCII여도 escape 해제된 키·값의 surrogate 유효성을 각각 확인한다. */
    @Test fun escapedJsonKeysAndValuesKeepStrictUnicodeValidation() = runTest {
        val parsed = BackupJson.parseText("{\"\\ud800\\udc00\":\"가\\u0000e\\u0301\\udbff\\udfff\"}")
        assertEquals("가\u0000e\u0301\uDBFF\uDFFF", parsed.getString("\uD800\uDC00"))
        for (text in listOf("{\"\\ud800\":0}", "{\"\\udc00\":0}", "{\"v\":\"\\ud800\"}", "{\"v\":\"\\udc00\"}", "{\"v\":\"\\udc00\\ud800\"}")) {
            assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.parseText(text) }.problem)
        }
    }

    private fun payload(bytes: Int, unit: String, unitBytes: Int): String {
        val prefix = "{\"value\":\""
        val suffix = "\"}"
        val contentBytes = bytes - prefix.length - suffix.length
        return prefix + unit.repeat(contentBytes / unitBytes) + "a".repeat(contentBytes % unitBytes) + suffix
    }

    private suspend fun assertPayloadBoundary(unit: String, unitBytes: Int) {
        for (bytes in listOf(BackupEnvelope.MAX_PAYLOAD - 1, BackupEnvelope.MAX_PAYLOAD, BackupEnvelope.MAX_PAYLOAD + 1)) {
            val text = payload(bytes, unit, unitBytes)
            assertEquals(bytes.toLong(), BackupJson.utf8ByteCount(text))
            assertMatchesStandard(text)
            if (bytes <= BackupEnvelope.MAX_PAYLOAD) {
                assertEquals(text.substring(10, text.length - 2), BackupJson.parseText(text).getString("value"))
            } else {
                assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupJson.parseText(text) }.problem)
            }
        }
    }

    /** 8MiB는 JSON 전체의 실제 UTF-8 bytes 기준이며 encoder 자체의 새 payload 제한으로 바꾸지 않는다. */
    @Test fun asciiPayloadKeepsEightMiBMinusExactAndPlusOneBoundary() = runTest {
        assertPayloadBoundary("a", 1)
    }

    /** 한글+비BMP의 UTF-16 길이가 아닌 실제 UTF-8 bytes로 8MiB±1을 판정한다. */
    @Test fun multibytePayloadKeepsEightMiBMinusExactAndPlusOneBoundary() = runTest {
        assertPayloadBoundary("가\uD83D\uDE00", 7)
    }

    /**
     * 목적: 전체 Unicode 오류를 byte 한도보다 먼저 검사하던 parseText 계약을 보존한다.
     * 조건: 한도를 이미 넘은 ASCII 뒤에 잘못된 surrogate가 있거나 유효 ASCII만 있는 입력.
     * 절차: parseText를 직접 호출해 JsonReader 진입 전 오류를 대조한다.
     * 기대: 늦은 surrogate는 INVALID_PAYLOAD, 유효 Unicode의 초과 입력은 LIMIT다.
     */
    @Test fun malformedUnicodeAfterPayloadLimitStillWinsOverLimit() = runTest {
        val oversized = "a".repeat(BackupEnvelope.MAX_PAYLOAD + 1)
        assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.parseText(oversized + "\uD800") }.problem)
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupJson.parseText(oversized) }.problem)
        assertEquals(BackupProblem.INVALID_PAYLOAD, assertFailsWith<BackupException> { BackupJson.parseText("") }.problem)
    }
}
