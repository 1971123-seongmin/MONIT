package com.monit.app.backup

import android.app.Application
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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

    /** 사본은 payload+116의 실제 bytes/EOF를 확인하고 stream을 닫은 뒤 borrowed 암호문만 잠시 제공한다. */
    @Test fun recoveryReadUsesOneExactBufferAndClearsItAfterVerification() {
        for (payloadSize in listOf(0, 31, 8 * 1024 * 1024)) {
            val source = ByteArray(payloadSize + 116) { (it % 251).toByte() }
            val input = ObservedInput(source, maxRead = if (payloadSize < 100) 7 else 8192)
            var verified: ByteArray? = null
            withRecoveryCiphertext(input, payloadSize) { encrypted ->
                assertTrue(input.closed)
                assertContentEquals(source, encrypted)
                verified = encrypted
            }
            assertEquals(1, input.buffers.size)
            assertSame(input.buffers.single(), verified)
            assertEquals(payloadSize + 116, checkNotNull(verified).size)
            assertTrue(checkNotNull(verified).all { it == 0.toByte() })
            assertOwnedBuffersCleared(input)
            assertEquals(1.toByte(), source[1])
        }
    }

    /** 짧은 사본/추가1byte/zero read는 인증 callback 전에 거부하고 소유 암호문을 소거한다. */
    @Test fun recoveryReadRejectsShortLongAndZeroBeforeVerification() {
        for (input in listOf(ObservedInput(ByteArray(146) { 43 }), ObservedInput(ByteArray(148) { 43 }), ObservedInput(ByteArray(147) { 43 }, zeros = 1))) {
            var called = false
            assertFailsWith<IllegalArgumentException> { withRecoveryCiphertext(input, 31) { called = true } }
            assertFalse(called)
            assertOwnedBuffersCleared(input)
        }
    }

    /** key 취득 또는 인증 callback이 실패해도 원래 오류를 전파하며 borrowed 암호문은 남기지 않는다. */
    @Test fun keyAndAuthenticationFailuresStillClearOwnedCiphertext() {
        for (failure in listOf(UnrecoverableKeyException("Synthetic key failure"), AEADBadTagException("Synthetic tag failure"))) {
            val input = ObservedInput(ByteArray(147) { 47 })
            var borrowed: ByteArray? = null
            assertSame(
                failure,
                assertFails {
                    withRecoveryCiphertext(input, 31) {
                        borrowed = it
                        throw failure
                    }
                },
            )
            assertTrue(checkNotNull(borrowed).all { it == 0.toByte() })
            assertOwnedBuffersCleared(input)
        }
    }

    /** read/close 실패에는 인증을 시작하지 않고 이미 할당한 사본 배열을 소거한다. */
    @Test fun recoveryReadAndCloseFailuresDoNotStartVerification() {
        for (input in listOf(ObservedInput(ByteArray(147) { 53 }, failureAfter = 50), ObservedInput(ByteArray(147) { 53 }, closeFailure = IOException("Synthetic close failure")))) {
            var called = false
            assertFailsWith<IOException> { withRecoveryCiphertext(input, 31) { called = true } }
            assertFalse(called)
            assertOwnedBuffersCleared(input)
        }
    }

    /** 불가능한 원래 payload 길이는 읽기/할당 전에 거부하고 stream 소유권은 정리한다. */
    @Test fun recoveryPayloadBoundsFailBeforeAnyRead() {
        for (payloadSize in listOf(-1, 8 * 1024 * 1024 + 1, Int.MAX_VALUE)) {
            val input = ObservedInput(byteArrayOf())
            assertFailsWith<IllegalArgumentException> { withRecoveryCiphertext(input, payloadSize) { fail("Unexpected verification") } }
            assertTrue(input.closed)
            assertEquals(0, input.bulkReads)
        }
    }
}
