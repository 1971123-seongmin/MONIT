package com.monit.app.backup

import com.monit.core.model.LedgerContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import org.junit.Test

/** JCA 출력을 제어하는 대역과 실제 소프트웨어 AES를 구분한다. 실제 Keystore 성공 증거는 아니다. */
class RecoveryCipherStreamsTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val context = LedgerContext("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")

    private class Engine(private val updates: List<ByteArray?>, private val tail: ByteArray?, private val failure: Throwable? = null) : CipherSpi() {
        val inputs = mutableSetOf<ByteArray>()
        val inputSizes = mutableListOf<Int>()
        val outputs = mutableListOf<ByteArray>()
        var finished = false
        private var index = 0
        override fun engineSetMode(mode: String) = Unit
        override fun engineSetPadding(padding: String) = Unit
        override fun engineGetBlockSize() = 16
        override fun engineGetOutputSize(inputLen: Int) = inputLen + 16
        override fun engineGetIV() = ByteArray(12)
        override fun engineGetParameters(): AlgorithmParameters? = null
        override fun engineGetKeySize(key: Key) = 256
        override fun engineInit(mode: Int, key: Key, random: SecureRandom?) = Unit
        override fun engineInit(mode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
        override fun engineInit(mode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) = Unit
        override fun engineUpdate(input: ByteArray, offset: Int, length: Int): ByteArray? {
            inputs += input
            inputSizes += length
            return updates.getOrNull(index++)?.also { outputs += it }
        }
        override fun engineUpdate(input: ByteArray, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
            val bytes = engineUpdate(input, offset, length) ?: return 0
            bytes.copyInto(output, outputOffset)
            return bytes.size
        }
        override fun engineDoFinal(input: ByteArray?, offset: Int, length: Int): ByteArray? {
            finished = true
            failure?.let { throw it }
            return tail?.also { outputs += it }
        }
        override fun engineDoFinal(input: ByteArray?, offset: Int, length: Int, output: ByteArray, outputOffset: Int): Int {
            val bytes = engineDoFinal(input, offset, length) ?: return 0
            bytes.copyInto(output, outputOffset)
            return bytes.size
        }
    }

    private fun cipher(engine: Engine, mode: Int): Cipher = object : Cipher(engine, object : Provider("SyntheticRecovery", 1.0, "Host-only output contract") {}, "AES/GCM/NoPadding") {}.apply { init(mode, key) }

    private class ObservedInput(private val bytes: ByteArray, private val maxRead: Int = RECOVERY_CHUNK, private var zero: Boolean = false, private val failAfter: Int? = null, private val closeError: IOException? = null) : InputStream() {
        var closed = false
        var position = 0
        override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt() and 255
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (zero) {
                zero = false
                return 0
            }
            if (failAfter != null && position >= failAfter) throw IOException("Synthetic read failure")
            if (position == bytes.size) return -1
            val count = minOf(length, maxRead, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun close() {
            closed = true
            closeError?.let { throw it }
        }
    }

    /** 고정 key/nonce/AAD의 구 one-shot bytes와 실제 multipart AES를 두 방향으로 교차 검증한다. */
    @Test fun multipartMatchesOldOneShotEnvelopeAtChunkBoundaries() {
        for (size in listOf(0, 1, 8191, 8192, 8193, 65_537)) {
            val plain = ByteArray(size) { (it % 251).toByte() }
            val nonce = ByteArray(12) { it.toByte() }
            val header = ByteBuffer.allocate(100).put("MONITRC1".toByteArray()).put(context.ledgerId.toByteArray()).put(context.storageGeneration.toByteArray()).put(nonce).putLong(size.toLong() + 16).array()
            fun encryptor() = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
            val old = encryptor().doFinal(plain)
            val output = ByteArrayOutputStream()
            writeRecoveryCiphertext(output, plain, encryptor())
            assertContentEquals(old, output.toByteArray())
            val recovered = LocalRecoveryCipher.decrypt(ObservedInput(header + old, maxRead = 7), size, context, key)
            assertContentEquals(plain, recovered)
            recovered.fill(0)
            val file = ByteArrayOutputStream().apply { LocalRecoveryCipher.encrypt(this, plain, context, key) }.toByteArray()
            assertEquals(size + 116, file.size)
            val decoder = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, file.copyOfRange(80, 92)))
                updateAAD(file, 0, 100)
            }
            assertContentEquals(plain, decoder.doFinal(file, 100, file.size - 100))
        }
    }

    /** null/빈 update와 마지막 전량 출력을 지원하고 입력 요청/총 출력을 제한하며 chunk를 소거한다. */
    @Test fun encryptionSupportsDelayedOutputsAndPreservesBorrowedPayload() {
        val plain = ByteArray(RECOVERY_CHUNK * 2 + 3) { 83 }
        val first = byteArrayOf(1, 2, 3)
        val tail = ByteArray(plain.size + 13) { 5 }
        val expected = first + tail
        val engine = Engine(listOf(null, byteArrayOf(), first), tail)
        val output = ByteArrayOutputStream()
        writeRecoveryCiphertext(output, plain, cipher(engine, Cipher.ENCRYPT_MODE))
        assertContentEquals(expected, output.toByteArray())
        assertTrue(engine.inputSizes.all { it <= RECOVERY_CHUNK })
        assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(plain.all { it == 83.toByte() })
    }

    /** update 평문은 인증·close 전 격리하고 성공 callback 뒤 입력 scratch/출력/격리 배열을 소거한다. */
    @Test fun earlyPlaintextIsQuarantinedUntilAuthenticationAndClose() {
        val size = RECOVERY_CHUNK * 2 + 1
        val first = byteArrayOf(7, 8)
        val tail = ByteArray(size - 2) { 9 }
        val expected = first + tail
        val engine = Engine(listOf(first, null, byteArrayOf()), tail)
        val input = ObservedInput(ByteArray(size + 16) { 11 })
        var quarantine: ByteArray? = null
        withRecoveryPlaintext(input, {
            readRecoveryPlaintext(it, size, cipher(engine, Cipher.DECRYPT_MODE), allocatePlaintext = { count -> ByteArray(count).also { bytes -> quarantine = bytes } })
        }) {
            assertTrue(engine.finished)
            assertTrue(input.closed)
            assertContentEquals(expected, it)
        }
        assertTrue(checkNotNull(quarantine).all { it == 0.toByte() })
        assertTrue(engine.inputs.all { bytes -> bytes.size <= RECOVERY_CHUNK && bytes.all { it == 0.toByte() } })
        assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    /** 전량 final provider에서는 별도 전량 격리 배열을 미리 할당하지 않는다. */
    @Test fun finalOnlyPlaintextTransfersWithoutAnExtraFullBuffer() {
        val tail = ByteArray(31) { 89 }
        val engine = Engine(listOf(null), tail)
        withRecoveryPlaintext(ObservedInput(ByteArray(47)), {
            readRecoveryPlaintext(it, 31, cipher(engine, Cipher.DECRYPT_MODE), allocatePlaintext = { fail("Unexpected full quarantine allocation") })
        }) {
            assertSame(tail, it)
            assertTrue(it.any { byte -> byte != 0.toByte() })
        }
        assertTrue(tail.all { it == 0.toByte() })
    }

    /**
     * 목적: 인증 오류/취소가 격리 평문과 scratch를 소거하고 외부 검증을 막는다.
     * 조건: 부분 update 출력과 tag 오류 또는 final 직후의 합성 CancellationException.
     * 절차: 제어한 CipherSpi와 취소 확인 callback을 실행해 예외·배열·callback을 관찰한다.
     * 기대: 원래 예외·close 완료·모든 앱 소유 배열0·외부 검증0. blocking 선점의 증거는 아니다.
     */
    @Test fun authenticationAndCancellationFailuresWipeAllOwnedPlaintext() {
        for (cancelAfterFinal in listOf(false, true)) {
            val failure = if (cancelAfterFinal) CancellationException("Synthetic post-auth cancellation") else AEADBadTagException("Synthetic tag failure")
            val engine = Engine(listOf(byteArrayOf(1, 2)), ByteArray(29) { 3 }, if (cancelAfterFinal) null else failure)
            val input = ObservedInput(ByteArray(47) { 5 })
            var quarantine: ByteArray? = null
            var verified = false
            assertSame(
                failure,
                assertFails {
                    withRecoveryPlaintext(input, {
                        readRecoveryPlaintext(it, 31, cipher(engine, Cipher.DECRYPT_MODE), checkActive = { if (cancelAfterFinal && engine.finished) throw failure }, allocatePlaintext = { size -> ByteArray(size).also { bytes -> quarantine = bytes } })
                    }) { verified = true }
                },
            )
            assertFalse(verified)
            assertTrue(input.closed)
            assertTrue(checkNotNull(quarantine).all { it == 0.toByte() })
            assertTrue(engine.inputs.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    /** 실제 EOF/zero/read 오류와 합계 출력 상한/미달을 검증하며 외부 callback은 실행하지 않는다. */
    @Test fun invalidReadsAndOutputTotalsNeverPublishPlaintext() {
        val inputs = listOf(ObservedInput(ByteArray(46)), ObservedInput(ByteArray(48)), ObservedInput(ByteArray(47), zero = true), ObservedInput(ByteArray(47), maxRead = 7, failAfter = 10))
        for (input in inputs) {
            val engine = Engine(emptyList(), ByteArray(31))
            assertFails { withRecoveryPlaintext(input, { readRecoveryPlaintext(it, 31, cipher(engine, Cipher.DECRYPT_MODE)) }) { fail("Unexpected verify") } }
            assertFalse(engine.finished)
            assertTrue(input.closed)
            assertTrue(engine.inputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
        for ((updates, final) in listOf(listOf(ByteArray(32)) to ByteArray(0), listOf(byteArrayOf(1, 2)) to ByteArray(30), emptyList<ByteArray>() to ByteArray(30))) {
            val engine = Engine(updates, final)
            var quarantine: ByteArray? = null
            assertFailsWith<IllegalArgumentException> {
                withRecoveryPlaintext(ObservedInput(ByteArray(47)), { readRecoveryPlaintext(it, 31, cipher(engine, Cipher.DECRYPT_MODE), allocatePlaintext = { size -> ByteArray(size).also { bytes -> quarantine = bytes } }) }) { fail("Unexpected verify") }
            }
            assertTrue(quarantine?.all { it == 0.toByte() } != false)
            assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    /** 암호화/sync/close 실패를 성공으로 바꾸지 않고 close와 chunk 정리를 끝낸다. */
    @Test fun outputSyncAndCloseFailuresPreserveErrorAndCleanup() {
        for (stage in listOf("encrypt", "sync", "close")) {
            val failure = IOException("Synthetic $stage failure")
            val closeFailure = IOException("Synthetic secondary close")
            var closed = false
            var synced = false
            val output = object : ByteArrayOutputStream() {
                override fun close() {
                    closed = true
                    if (stage == "close") throw failure else throw closeFailure
                }
            }
            val engine = Engine(emptyList(), ByteArray(47) { 97 })
            val actual = assertFailsWith<IOException> {
                writeRecoveryOutput(output, sync = {
                    synced = true
                    if (stage == "sync") throw failure
                }) {
                    writeRecoveryCiphertext(it, ByteArray(31) { 101 }, cipher(engine, Cipher.ENCRYPT_MODE))
                    if (stage == "encrypt") throw failure
                }
            }
            assertSame(failure, actual)
            assertTrue(closed)
            assertEquals(stage != "encrypt", synced)
            if (stage != "close") assertTrue(actual.suppressed.contains(closeFailure))
            assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    /**
     * 목적: 암호화 출력 상한·미달·쓰기 실패·취소에도 빌린 payload는 보존한다.
     * 조건: final 출력 크기를 제어한 Cipher와 합성 출력 오류/취소.
     * 절차: 실제 writer에 각 경계를 입력하고 예외 뒤 반환 chunk/입력을 대조한다.
     * 기대: 실패 전파·반환 chunk 전부0·payload 원본 동일. 실제 provider 즉시 취소를 뜻하지 않는다.
     */
    @Test fun encryptionFailuresWipeReturnedChunksAndPreserveInput() {
        for (stage in listOf("over", "under", "write", "cancel")) {
            val payload = ByteArray(31) { 107 }
            val size = when (stage) {
                "over" -> 48
                "under" -> 46
                else -> 47
            }
            val engine = Engine(emptyList(), ByteArray(size) { 109 })
            val writeFailure = IOException("Synthetic write failure")
            val cancelled = CancellationException("Synthetic output cancellation")
            val output = object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (stage == "write") throw writeFailure
                    super.write(bytes, offset, count)
                }
            }
            val error = assertFails {
                writeRecoveryCiphertext(output, payload, cipher(engine, Cipher.ENCRYPT_MODE)) { if (stage == "cancel" && engine.finished) throw cancelled }
            }
            when (stage) {
                "write" -> assertSame(writeFailure, error)
                "cancel" -> assertSame(cancelled, error)
                else -> assertTrue(error is IllegalArgumentException)
            }
            assertTrue(payload.all { it == 107.toByte() })
            assertTrue(engine.outputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    /** 닫기 실패 또는 검증 실패에는 인증된 최종 배열도 소거하며 callback 순서를 보존한다. */
    @Test fun authenticatedResultIsWipedOnCloseOrVerificationFailure() {
        for (closeFails in listOf(false, true)) {
            val failure = IOException("Synthetic close/verify failure")
            val tail = ByteArray(31) { 103 }
            val engine = Engine(emptyList(), tail)
            val input = ObservedInput(ByteArray(47), closeError = if (closeFails) failure else null)
            var called = false
            assertSame(
                failure,
                assertFailsWith<IOException> {
                    withRecoveryPlaintext(input, { readRecoveryPlaintext(it, 31, cipher(engine, Cipher.DECRYPT_MODE)) }) {
                        called = true
                        throw failure
                    }
                },
            )
            assertEquals(!closeFails, called)
            assertTrue(input.closed)
            assertTrue(tail.all { it == 0.toByte() })
        }
    }
}
