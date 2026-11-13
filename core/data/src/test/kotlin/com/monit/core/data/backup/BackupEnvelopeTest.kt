package com.monit.core.data.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 합성 OpenSSL 고정값과 별도 JCA 플랫폼 구현을 대조한다. 실제 Android provider 시험은 단말 후속이다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupEnvelopeTest {
    private val password = "synthetic-backup-password-2026".toCharArray()
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 공개 BoringSSL AES256GCM known-answer를 플랫폼 Cipher와 대조한다. envelope/KDF 상호운용과 별도다. */
    @Test fun platformAesGcmMatchesPublishedKnownAnswer() {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"), javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))
        assertContentEquals(hex("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"), cipher.doFinal(ByteArray(16)))
    }

    /** 한글·결합문자·비BMP를 정규화하지 않고 UTF-8 PBKDF2 및 header/AAD 포함 전체 파일 고정값과 일치시킨다. */
    @Test fun unicodePbeAndWholeEnvelopeMatchIndependentOpenSslVectors() {
        val json = javaClass.getResourceAsStream("/backup/crypto-vectors.json")!!.use { JSONObject(it.reader(Charsets.UTF_8).readText()) }.getJSONArray("vectors")
        for (n in 0 until json.length()) {
            val vector = json.getJSONObject(n)
            val chars = vector.getString("password").toCharArray()
            val spec = PBEKeySpec(chars, hex(vector.getString("saltHex")), 600_000, 256)
            try {
                assertContentEquals(hex(vector.getString("keyHex")), SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
                val payload = hex(vector.getString("payloadHex"))
                val encrypted = BackupEnvelope.seal(payload, chars, hex(vector.getString("saltHex")), hex(vector.getString("nonceHex")))
                assertContentEquals(hex(vector.getString("fileHex")), encrypted)
                assertContentEquals(payload, BackupEnvelope.decrypt(encrypted, chars))
            } finally {
                spec.clearPassword()
                chars.fill('\u0000')
            }
        }
    }

    /** 같은 내용/암호라도 새 암호화의 salt·nonce가 서로 다르며 복호 결과는 같다. */
    @Test fun encryptionsHaveFreshSaltAndNonce() {
        val bytes = "{\"synthetic\":true}".toByteArray()
        val one = BackupEnvelope.encrypt(bytes, password)
        val two = BackupEnvelope.encrypt(bytes, password)
        assertFalse(one.copyOfRange(16, 32).contentEquals(two.copyOfRange(16, 32)))
        assertFalse(one.copyOfRange(32, 44).contentEquals(two.copyOfRange(32, 44)))
        assertContentEquals(bytes, BackupEnvelope.decrypt(one, password))
        assertContentEquals(bytes, BackupEnvelope.decrypt(two, password))
    }

    /** header/AAD·ciphertext·tag 변조/암호 오류는 평문을 반환하지 않고 같은 안전한 인증 오류다. */
    @Test fun tamperingAndWrongPasswordNeverReturnPlaintext() {
        val bytes = BackupEnvelope.encrypt("{\"synthetic\":true}".toByteArray(), password)
        for (offset in listOf(16, 32, 52, bytes.lastIndex)) {
            val changed = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertEquals(BackupProblem.AUTHENTICATION, assertFailsWith<BackupException> { BackupEnvelope.decrypt(changed, password) }.problem)
        }
        assertEquals(BackupProblem.AUTHENTICATION, assertFailsWith<BackupException> { BackupEnvelope.decrypt(bytes, "different-synthetic-password".toCharArray()) }.problem)
    }

    /** 지원하지 않는 KDF·부호/길이·버전은 비밀번호 검사 전에 거부해 공격자가 비용을 올리지 못한다. */
    @Test fun invalidHeaderFailsBeforePasswordValidationOrKdf() {
        val bytes = BackupEnvelope.encrypt(byteArrayOf(), password)
        for (iterations in listOf(0, 599_999, 600_001, -1)) {
            val changed = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(12, iterations) }
            assertEquals(BackupProblem.HEADER, assertFailsWith<BackupException> { BackupEnvelope.decrypt(changed, charArrayOf()) }.problem)
        }
        for (length in listOf(0L, -1L, Long.MAX_VALUE, 17L)) {
            val changed = bytes.copyOf().also { ByteBuffer.wrap(it).putLong(44, length) }
            assertEquals(BackupProblem.HEADER, assertFailsWith<BackupException> { BackupEnvelope.decrypt(changed, charArrayOf()) }.problem)
        }
        val future = bytes.copyOf().also { it[9] = 2 }
        assertEquals(BackupProblem.UNSUPPORTED, assertFailsWith<BackupException> { BackupEnvelope.decrypt(future, charArrayOf()) }.problem)
        assertFailsWith<BackupException> { BackupEnvelope.decrypt(bytes + 0, password) }
        assertFailsWith<BackupException> { BackupEnvelope.decrypt(bytes.copyOf(bytes.size - 1), password) }
    }

    /** 원문 비밀번호의 코드포인트/UTF8/제어문자·surrogate 상한을 검사하며 trim/정규화하지 않는다. */
    @Test fun passwordPolicyUsesCodePointsAndPreservesInput() {
        BackupEnvelope.validatePassword(("🔐".repeat(128)).toCharArray())
        BackupEnvelope.validatePassword("  synthetic passphrase  ".toCharArray())
        for (value in listOf("a".repeat(14), "a".repeat(129), "🔐".repeat(129), "a".repeat(20) + '\u0000', "a".repeat(20) + '\uD800')) assertEquals(BackupProblem.PASSWORD, assertFailsWith<BackupException> { BackupEnvelope.validatePassword(value.toCharArray()) }.problem)
    }

    /** 크기 한도는 읽은 실제 바이트로 적용하며 파일 메타데이터나 요청한 배열 크기를 신뢰하지 않는다. */
    @Test fun boundedInputAndPayloadDoNotSilentlyTruncate() {
        assertEquals(BackupEnvelope.MAX_FILE, BackupEnvelope.readBounded(ByteArrayInputStream(ByteArray(BackupEnvelope.MAX_FILE))).size)
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupEnvelope.readBounded(ByteArrayInputStream(ByteArray(BackupEnvelope.MAX_FILE + 1))) }.problem)
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupEnvelope.encrypt(ByteArray(BackupEnvelope.MAX_PAYLOAD + 1), password) }.problem)
    }

    /** 복호 인증 이후 strict parser가 UTF8/BOM/중복 key·고립 surrogate·중첩을 거부한다. */
    @Test fun strictJsonRejectsAmbiguityBeforeObjectConversion() = runTest {
        for (value in listOf("{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "\uFEFF{}", "{\"x\":\"\\uD800\"}", "[".repeat(18) + "0" + "]".repeat(18), "{}{}")) assertFailsWith<BackupException> { BackupJson.parse(value.toByteArray()) }
        assertFailsWith<BackupException> { BackupJson.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertEquals("합성", BackupJson.parse("{\"name\":\"합성\"}".toByteArray()).text("name"))
    }

    /** 암호 인증 뒤에도 scalar token 제한과 JSON 숫자 문법을 거부해 파서 과부하/해석 차이를 막는다. */
    @Test fun tokenAndNumberLimitsAreEnforcedBeforeLogicalMapping() = runTest {
        val oversized = "{\"rows\":[" + List(1_000_001) { "0" }.joinToString(",") + "]}"
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupJson.parse(oversized.toByteArray()) }.problem)
        for (number in listOf("01", "+1", "NaN", "Infinity", "1.")) assertFailsWith<BackupException> { BackupJson.parse("{\"x\":$number}".toByteArray()) }
    }
}
