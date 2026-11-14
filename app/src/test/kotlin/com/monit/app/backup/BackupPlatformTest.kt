package com.monit.app.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.io.*
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** host에서 stream/JCA 실패 경계를 검증하며 실제 Android Keystore·문서 provider 성공을 주장하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackupPlatformTest {
    /** 목적: CancellationSignal/descriptor close가 main에서 실행되지 않고 실제 정리 종료까지 작업을 붙잡는다.
     * 조건: IO worker와 닫기 gate를 실제 스레드에서 제어한다. 각 blocking 대기는 10초 상한이다.
     * 절차: read 진입→호출 코루틴 취소→close 진입 관찰→미완료 확인→close 해제/종료.
     * 기대: cancel/close 모두 호출 스레드 밖, 닫기 종료 전 부모 미완료, 늦은 성공 전달 없음.
     */
    @Test fun providerCancellationAndCloseRunOffCallerAndRemainStructured() = runTest {
        val caller = Thread.currentThread()
        val cancelThread = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val closeThread = java.util.concurrent.atomic.AtomicReference<Thread?>()
        val entered = CompletableDeferred<Unit>()
        val closing = CompletableDeferred<Unit>()
        val readRelease = java.util.concurrent.CountDownLatch(1)
        val closeRelease = java.util.concurrent.CountDownLatch(1)
        val contexts = object : CoroutineContextProvider {
            override val main = Dispatchers.Main
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        val documents = BackupDocuments(RuntimeEnvironment.getApplication(), contexts)
        var delivered = false
        val task = launch(Dispatchers.Default) {
            documents.access { signal, active ->
                active.set(
                    Closeable {
                        closeThread.set(Thread.currentThread())
                        closing.complete(Unit)
                        check(closeRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    },
                )
                signal.setOnCancelListener {
                    cancelThread.set(Thread.currentThread())
                    readRelease.countDown()
                }
                entered.complete(Unit)
                check(readRelease.await(10, java.util.concurrent.TimeUnit.SECONDS))
                "late bytes"
            }
            delivered = true
        }
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
            task.cancel()
            withContext(Dispatchers.Default) { withTimeout(10_000) { closing.await() } }
            assertFalse(task.isCompleted)
            assertNotSame(caller, checkNotNull(cancelThread.get()))
            assertNotSame(caller, checkNotNull(closeThread.get()))
        } finally {
            readRelease.countDown()
            closeRelease.countDown()
            withContext(Dispatchers.Default) { withTimeout(10_000) { task.cancelAndJoin() } }
        }
        assertFalse(delivered)
    }

    /** 목적: 취소를 따르지 않는 read의 반환 전에는 scope가 종료되지 않으며 늦은 값은 전달하지 않는다.
     * 조건: 같은 가상 스케줄러의 NonCancellable provider gate다.
     * 절차: read→취소→실제 미완료 확인→provider 반환→join.
     * 기대: 취소 요청 후에도 미완료이며 반환 뒤 취소로 종료되고 성공 callback은0이다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nonCooperativeProviderCannotOutliveTheAccessScopeAsASuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val contexts = object : CoroutineContextProvider {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
        }
        val release = CompletableDeferred<Unit>()
        var delivered = false
        val task = launch {
            BackupDocuments(RuntimeEnvironment.getApplication(), contexts).access { _, _ ->
                withContext(NonCancellable) { release.await() }
                "late"
            }
            delivered = true
        }
        runCurrent()
        task.cancel()
        runCurrent()
        assertFalse(task.isCompleted)
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(delivered)
    }
    private val context = LedgerContext("00000000-0000-0000-0000-000000000900", "00000000-0000-0000-0000-000000000901")

    /** close/flush 오류를 저장 성공으로 처리하면 재읽기/완료 시각이 잘못 확정되므로 반드시 전파한다. */
    @Test fun closeAndFlushFailuresAreNotSuccessfulWrites() {
        for (closeFailure in listOf(true, false)) {
            val stream = object : ByteArrayOutputStream() {
                override fun flush() {
                    if (!closeFailure) throw IOException("synthetic flush")
                }
                override fun close() {
                    if (closeFailure) throw IOException("synthetic close")
                }
            }
            assertFailsWith<IOException> { BackupDocumentStreams.write(ByteArray(68), stream) }
        }
    }

    /** metadata가 아닌 실제 바이트 수로 상한을 검사하고 경계 파일은 정확히 읽는다. */
    @Test fun actualStreamBytesAreBoundedAndClosed() {
        var closed = false
        val input = object : InputStream() {
            var emitted = 0
            override fun read(): Int = error("chunk read required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (emitted == BackupDocumentStreams.MAX_FILE + 1) return -1
                val count = minOf(length, BackupDocumentStreams.MAX_FILE + 1 - emitted)
                buffer.fill(0, offset, offset + count)
                emitted += count
                return count
            }
            override fun close() {
                closed = true
            }
        }
        assertEquals(BackupProblem.LIMIT, assertFailsWith<BackupException> { BackupDocumentStreams.read(input) }.problem)
        assertTrue(closed)
        val boundary = ByteArray(BackupDocumentStreams.MAX_FILE) { (it % 251).toByte() }
        assertContentEquals(boundary, BackupDocumentStreams.read(boundary.inputStream()))
    }

    /** 진행하지 않는 provider stream이 CPU 무한 루프로 이어지지 않는다. */
    @Test fun zeroByteReadCannotSpinIndefinitely() {
        var reads = 0
        assertFails {
            BackupDocumentStreams.read(object : InputStream() {
                override fun read(): Int = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    return 0
                }
            })
        }
        assertEquals(17, reads)
    }

    /** 내부 사본은 무작위 IV이며 ledger/generation/type/header/cipher/tag의 모든 변조를 인증 실패로 막는다. */
    @Test fun recoveryCipherBindsOriginalContextAndRejectsTampering() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val payload = "합성 복구 사본".toByteArray()
        fun encrypt(): ByteArray = java.io.ByteArrayOutputStream().apply { LocalRecoveryCipher.encrypt(this, payload, context, key) }.toByteArray()
        fun decrypt(bytes: ByteArray, owner: LedgerContext = context): ByteArray = bytes.inputStream().use { LocalRecoveryCipher.decrypt(it, bytes.size - 116, owner, key) }
        val encrypted = encrypt()
        assertFalse(encrypted.contentEquals(encrypt()))
        assertContentEquals(payload, decrypt(encrypted))
        for (offset in listOf(0, 8, 44, 80, 92, 100, encrypted.lastIndex)) {
            assertFails { decrypt(encrypted.copyOf().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }) }
        }
        assertFails { decrypt(encrypted, context.copy(storageGeneration = "00000000-0000-0000-0000-000000000902")) }
        assertFails { decrypt(encrypted.copyOf(encrypted.size - 1)) }
    }

    /** host의 AndroidKeyStore 미제공은 명시 실패이며 평문 사본을 남기지 않는다. */
    @Test fun unavailableKeystoreBlocksRecoveryWithoutPlaintextFallback() = runTest {
        val app: Application = RuntimeEnvironment.getApplication()
        val contexts = object : CoroutineContextProvider {
            override val main = Dispatchers.Main
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        val payload = "synthetic private recovery bytes".toByteArray()
        val problem = withContext(Dispatchers.Default) { assertFailsWith<BackupException> { KeystoreRecoveryCopyStore(app, contexts).protect(context, payload) } }
        assertEquals(BackupProblem.RECOVERY_COPY, problem.problem)
        val folder = File(app.filesDir, "restore-recovery")
        assertTrue(folder.listFiles().orEmpty().none { it.readBytes().contentEquals(payload) })
        assertTrue(folder.listFiles().orEmpty().none { it.name == "verified.monitrec" })
    }
}
