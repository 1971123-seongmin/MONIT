package com.monit.core.data.backup

import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 기존 휴대 envelope1/cryptoSuite1. 플랫폼 JCA만 사용하며 provider를 고정하지 않는다. */
internal object BackupEnvelope {
    const val MAX_PAYLOAD = 8 * 1024 * 1024
    const val HEADER_SIZE = 52
    const val MAX_FILE = MAX_PAYLOAD + HEADER_SIZE + 16
    private val magic = "MONITBK1".toByteArray(Charsets.US_ASCII)

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        return seal(payload, password, ByteArray(16).also(random::nextBytes), ByteArray(12).also(random::nextBytes))
    }

    internal fun seal(payload: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        if (payload.size > MAX_PAYLOAD) fail(BackupProblem.LIMIT)
        require(salt.size == 16 && nonce.size == 12)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(magic).putShort(1).putShort(1).putInt(600_000).put(salt).put(nonce).putLong(payload.size.toLong() + 16).array()
        return header + crypt(Cipher.ENCRYPT_MODE, payload, password, salt, nonce, header)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        // 공격자가 정한 KDF 비용과 길이는 비밀번호 처리·큰 할당 전에 거부한다.
        if (bytes.size < HEADER_SIZE + 16 || bytes.size > MAX_FILE) fail(BackupProblem.LIMIT)
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val observed = ByteArray(8).also(buffer::get)
        if (!observed.contentEquals(magic)) fail(BackupProblem.HEADER)
        if (buffer.short.toInt() != 1 || buffer.short.toInt() != 1) fail(BackupProblem.UNSUPPORTED)
        if (buffer.int != 600_000) fail(BackupProblem.HEADER)
        val salt = ByteArray(16).also(buffer::get)
        val nonce = ByteArray(12).also(buffer::get)
        val length = buffer.long
        if (length !in 16L..(MAX_PAYLOAD + 16L) || length != bytes.size.toLong() - HEADER_SIZE) fail(BackupProblem.HEADER)
        // doFinal 하나로 인증을 완료한다. update 결과를 파서/DB에 전달하지 않는다.
        return crypt(Cipher.DECRYPT_MODE, bytes.copyOfRange(HEADER_SIZE, bytes.size), password, salt, nonce, header)
    }

    private fun crypt(mode: Int, bytes: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        validatePassword(password)
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        var key: ByteArray? = null
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(bytes)
            }
        } catch (_: BadPaddingException) {
            fail(BackupProblem.AUTHENTICATION)
        } catch (_: GeneralSecurityException) {
            fail(BackupProblem.CRYPTO_UNAVAILABLE)
        } finally {
            key?.fill(0)
            spec.clearPassword()
        }
    }

    fun validatePassword(value: CharArray) {
        if (value.size > 256) fail(BackupProblem.PASSWORD)
        var points = 0
        var offset = 0
        while (offset < value.size) {
            val point = Character.codePointAt(value, offset)
            if (Character.isISOControl(point) || point in 0xD800..0xDFFF) fail(BackupProblem.PASSWORD)
            points++
            offset += Character.charCount(point)
        }
        if (points !in 15..128) fail(BackupProblem.PASSWORD)
        val encoded = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(BackupProblem.PASSWORD)
        }
        try {
            if (encoded.remaining() > 512) fail(BackupProblem.PASSWORD)
        } finally {
            while (encoded.hasRemaining()) encoded.put(0)
        }
    }

    /** ContentProvider 크기 메타데이터를 신뢰하거나 전체 길이만큼 선할당하지 않는다. */
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var emptyReads = 0
        try {
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                if (count == 0) {
                    if (++emptyReads > 16) fail(BackupProblem.IO)
                    continue
                }
                emptyReads = 0
                if (output.size().toLong() + count > MAX_FILE) fail(BackupProblem.LIMIT)
                output.write(chunk, 0, count)
            }
            return output.toByteArray()
        } finally {
            chunk.fill(0)
        }
    }
    private fun fail(problem: BackupProblem): Nothing = throw BackupException(problem)
}
