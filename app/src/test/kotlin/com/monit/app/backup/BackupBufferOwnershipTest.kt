package com.monit.app.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import com.monit.core.model.LedgerContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 합성 stream으로 바이트/상한/소거 소유권을 확인하며 기기 heap/Keystore 성공을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupBufferOwnershipTest {
    private class ObservedInput(
        private val source: ByteArray,
        private val maxRead: Int = Int.MAX_VALUE,
        private val failureAfter: Int? = null,
        private val readFailure: IOException = IOException("Synthetic read failure"),
        private val closeFailure: IOException? = null,
        private var zeros: Int = 0,
    ) : InputStream() {
        val buffers = HashSet<ByteArray>()
        var bulkReads = 0
            private set
        var closed = false
            private set
        private var position = 0

        override fun read(): Int = if (position == source.size) -1 else source[position++].toInt() and 255

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            buffers.add(buffer)
            bulkReads++
            if (failureAfter != null && position >= failureAfter) throw readFailure
            if (zeros > 0) {
                zeros--
                return 0
            }
            if (position == source.size) return -1
            val count = minOf(length, maxRead, source.size - position, failureAfter?.minus(position) ?: Int.MAX_VALUE)
            source.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private fun assertOwnedBuffersCleared(input: ObservedInput) {
        assertTrue(input.closed)
        assertTrue(input.buffers.all { buffer -> buffer.all { it == 0.toByte() } })
    }

    /** 1byte씩 읽어도 segment를 재사용하고 결과/호출자 입력을 보존한 뒤 내부 chunk만 소거한다. */
    @Test fun partialReadsFillSegmentsAndPreserveReturnedAndCallerBytes() {
        val source = ByteArray(16_421) { (it % 251).toByte() }
        val original = source.copyOf()
        val input = ObservedInput(source, maxRead = 1)
        val actual = BackupDocumentStreams.read(input)
        assertContentEquals(original, actual)
        assertContentEquals(original, source)
        assertTrue(input.buffers.all { it.size <= 8192 })
        assertTrue(input.buffers.size < input.bulkReads / 100)
        assertOwnedBuffersCleared(input)
    }

    /** 빈 입력과 segment 전후 경계에서도 실제 EOF bytes만 반환한다. */
    @Test fun emptyAndSegmentBoundaryReadsRemainExact() {
        for (size in listOf(0, 1, 8191, 8192, 8193)) {
            val source = ByteArray(size) { (it % 251).toByte() }
            val input = ObservedInput(source, maxRead = 37)
            assertContentEquals(source, BackupDocumentStreams.read(input))
            assertOwnedBuffersCleared(input)
        }
    }

    /** 상한-1은 그대로 읽고 +1은 LIMIT이며 실패해도 모든 내부 chunk를 소거한다. */
    @Test fun actualByteLimitAndOverflowCleanupRemainIndependentOfMetadata() {
        val allowed = ObservedInput(ByteArray(BackupDocumentStreams.MAX_FILE - 1) { 19 })
        val actual = BackupDocumentStreams.read(allowed)
        assertEquals(BackupDocumentStreams.MAX_FILE - 1, actual.size)
        assertTrue(actual.all { it == 19.toByte() })
        assertOwnedBuffersCleared(allowed)
        val rejected = ObservedInput(ByteArray(BackupDocumentStreams.MAX_FILE + 1) { 23 })
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupDocumentStreams.read(rejected) }.problem)
        assertOwnedBuffersCleared(rejected)
    }

    /** 16회 연속 zero는 진행을 기다리고 17회는 거부하며 소유 chunk/stream을 정리한다. */
    @Test fun consecutiveZeroReadBoundaryRemainsSixteen() {
        val allowed = ObservedInput(byteArrayOf(1, 2, 3), zeros = 16)
        assertContentEquals(byteArrayOf(1, 2, 3), BackupDocumentStreams.read(allowed))
        assertOwnedBuffersCleared(allowed)
        val rejected = ObservedInput(byteArrayOf(1), zeros = 17)
        assertFailsWith<IllegalArgumentException> { BackupDocumentStreams.read(rejected) }
        assertEquals(17, rejected.bulkReads)
        assertOwnedBuffersCleared(rejected)
    }

    /** 읽기 중 오류와 닫기 중 오류의 원래 예외/suppressed 관계를 유지하고 부분 chunk도 소거한다. */
    @Test fun readFailurePreservesPrimaryAndSuppressedCloseFailure() {
        val readFailure = IOException("Synthetic primary read failure")
        val closeFailure = IOException("Synthetic suppressed close failure")
        val input = ObservedInput(ByteArray(9000) { 37 }, maxRead = 37, failureAfter = 1024, readFailure = readFailure, closeFailure = closeFailure)
        assertSame(readFailure, assertFailsWith<IOException> { BackupDocumentStreams.read(input) })
        assertTrue(closeFailure in readFailure.suppressed)
        assertOwnedBuffersCleared(input)
    }

    /** EOF 후 close 실패는 결과 bytes를 전달하지 않고 내부 chunk 정리를 끝낸 뒤 전파한다. */
    @Test fun closeFailureNeverReturnsAnUnclosedResult() {
        val failure = IOException("Synthetic close failure")
        val input = ObservedInput(ByteArray(9000) { 41 }, closeFailure = failure)
        assertSame(failure, assertFailsWith<IOException> { BackupDocumentStreams.read(input) })
        assertOwnedBuffersCleared(input)
    }

    /**
     * 목적: 이미 interrupt된 작업이 읽기 진행 없이 종료하며 다른 시험에 interrupt를 남기지 않는다.
     * 조건: 동일 시험 thread와 합성 stream만 사용한다.
     * 절차: interrupt→read 오류/정리 확인→finally에서 flag를 지운다.
     * 기대: InterruptedIOException, stream close, 소유 chunk 소거. 실기기 blocking 선점의 증거는 아니다.
     */
    @Test fun interruptedReadClosesAndClearsOwnedChunks() {
        val input = ObservedInput(byteArrayOf(1, 2, 3))
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedIOException> { BackupDocumentStreams.read(input) }
            assertEquals(0, input.bulkReads)
            assertTrue(input.closed)
        } finally {
            Thread.interrupted()
        }
    }

    private val recoveryContext = LedgerContext("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
    private val recoveryKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private fun encrypted(payload: ByteArray) = ByteArrayOutputStream().apply { LocalRecoveryCipher.encrypt(this, payload, recoveryContext, recoveryKey) }.toByteArray()
    private fun decrypt(input: InputStream, size: Int) = LocalRecoveryCipher.decrypt(input, size, recoveryContext, recoveryKey)

    /** 인증과 close 뒤에만 평문을 빌려주고 scratch/평문을 소거하며 원래 입력은 보존한다. */
    @Test fun recoveryReadIsBoundedAndClearsAuthenticatedPlaintextAfterVerification() {
        for (payloadSize in listOf(0, 31, 8 * 1024 * 1024)) {
            val payload = ByteArray(payloadSize) { (it % 251).toByte() }
            val source = encrypted(payload)
            val before = source.copyOf()
            val input = ObservedInput(source, maxRead = if (payloadSize < 100) 7 else 8192)
            var verified: ByteArray? = null
            withRecoveryPlaintext(input, { decrypt(it, payloadSize) }) { plain ->
                assertTrue(input.closed)
                assertContentEquals(payload, plain)
                verified = plain
            }
            assertTrue(input.buffers.all { it.size <= RECOVERY_CHUNK })
            assertEquals(payloadSize, checkNotNull(verified).size)
            assertTrue(checkNotNull(verified).all { it == 0.toByte() })
            assertOwnedBuffersCleared(input)
            assertContentEquals(before, source)
        }
    }

    /** 짧은 사본/추가1byte/zero read는 인증 callback 전에 거부하고 소유 암호문을 소거한다. */
    @Test fun recoveryReadRejectsShortLongAndZeroBeforeVerification() {
        val source = encrypted(ByteArray(31) { 43 })
        for (input in listOf(ObservedInput(source.copyOf(source.size - 1)), ObservedInput(source + byteArrayOf(1)), ObservedInput(source, zeros = 1))) {
            var called = false
            assertFailsWith<IllegalArgumentException> { withRecoveryPlaintext(input, { decrypt(it, 31) }) { called = true } }
            assertFalse(called)
            assertOwnedBuffersCleared(input)
        }
    }

    /** key 취득/인증 오류를 그대로 전파하고 stream을 닫으며 검증 callback은 실행하지 않는다. */
    @Test fun keyAndAuthenticationFailuresNeverReachPlaintextVerification() {
        for (failure in listOf(UnrecoverableKeyException("Synthetic key failure"), AEADBadTagException("Synthetic tag failure"))) {
            val input = ObservedInput(ByteArray(147) { 47 })
            var called = false
            assertSame(
                failure,
                assertFails {
                    withRecoveryPlaintext(input, { throw failure }) { called = true }
                },
            )
            assertFalse(called)
            assertOwnedBuffersCleared(input)
        }
    }

    /** close가 실패하면 인증된 반환 배열도 외부 검증에 넘기지 않고 소거한다. */
    @Test fun recoveryCloseFailureClearsDecryptedResultBeforeCallback() {
        val input = ObservedInput(byteArrayOf(), closeFailure = IOException("Synthetic close failure"))
        val plain = ByteArray(31) { 53 }
        var called = false
        assertFailsWith<IOException> { withRecoveryPlaintext(input, { plain }) { called = true } }
        assertFalse(called)
        assertTrue(plain.all { it == 0.toByte() })
        assertTrue(input.closed)
    }

    /** 불가능한 원래 payload 길이는 읽기/할당 전에 거부하고 stream 소유권은 정리한다. */
    @Test fun recoveryPayloadBoundsFailBeforeAnyRead() {
        for (payloadSize in listOf(-1, 8 * 1024 * 1024 + 1, Int.MAX_VALUE)) {
            val input = ObservedInput(byteArrayOf())
            assertFailsWith<IllegalArgumentException> { withRecoveryPlaintext(input, { decrypt(it, payloadSize) }) { fail("Unexpected verification") } }
            assertTrue(input.closed)
            assertEquals(0, input.bulkReads)
        }
    }
}
