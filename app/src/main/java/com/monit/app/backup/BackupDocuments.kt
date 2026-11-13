package com.monit.app.backup

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** SAF URI는 메모리에서만 사용한다. 접근 권한/이름/비밀번호를 영속화하지 않는다. */
internal interface BackupDocumentAccess {
    suspend fun read(uri: Uri): ByteArray
    suspend fun writeAndRead(uri: Uri, encrypted: ByteArray): ByteArray
}

internal class BackupDocuments @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val contexts: CoroutineContextProvider,
) : BackupDocumentAccess {
    override suspend fun read(uri: Uri): ByteArray = withTimeout(60_000) {
        access { signal, active ->
            require(uri.scheme == "content")
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal) ?: error("Unavailable document")
            active.set(descriptor)
            signal.throwIfCanceled()
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(BackupDocumentStreams::read)
        }
    }

    override suspend fun writeAndRead(uri: Uri, encrypted: ByteArray): ByteArray = withTimeout(60_000) {
        access { signal, active ->
            require(uri.scheme == "content")
            val descriptor = context.contentResolver.openFileDescriptor(uri, "wt", signal) ?: error("Unavailable document")
            active.set(descriptor)
            signal.throwIfCanceled()
            BackupDocumentStreams.write(encrypted, ParcelFileDescriptor.AutoCloseOutputStream(descriptor))
        }
        // close/flush까지 성공한 뒤 새 descriptor로 읽는다. provider가 재읽기를 거절하면 검증 성공이 아니다.
        read(uri)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun <T> access(block: suspend (CancellationSignal, AtomicReference<Closeable?>) -> T): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val active = AtomicReference<Closeable?>()
            val job = launch(contexts.io) {
                try {
                    signal.throwIfCanceled()
                    val value = block(signal, active)
                    currentCoroutineContext().ensureActive()
                    continuation.resume(value)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (_: Exception) {
                    continuation.resumeWithException(BackupException(BackupProblem.IO))
                } finally {
                    active.getAndSet(null)?.let { runCatching { it.close() } }
                }
            }
            continuation.invokeOnCancellation {
                // ATOMIC으로 취소된 부모에도 정리 child를 시작한다. scope는 실제 close/cancel 종료까지 기다린다.
                // provider의 취소/close 자체가 막혀도 main에서 실행하거나 종료됐다고 추정하지 않는다.
                launch(contexts.io, start = CoroutineStart.ATOMIC) {
                    withContext(NonCancellable) {
                        try {
                            signal.cancel()
                        } finally {
                            active.getAndSet(null)?.let { runCatching { it.close() } }
                        }
                    }
                }
                job.cancel()
            }
        }
    }
}

/** 실제 provider와 분리해 닫기 실패/실제 byte 상한을 시험하는 순수 stream 경계. */
internal object BackupDocumentStreams {
    const val MAX_FILE = 8 * 1024 * 1024 + 68

    fun write(bytes: ByteArray, output: OutputStream) {
        require(bytes.size in 68..MAX_FILE)
        output.use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val size = minOf(8192, bytes.size - offset)
                stream.write(bytes, offset, size)
                offset += size
            }
            stream.flush()
        }
    }

    fun read(input: InputStream): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var zeros = 0
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    require(++zeros <= 16)
                    continue
                }
                zeros = 0
                if (output.size().toLong() + count > MAX_FILE) throw BackupException(BackupProblem.LIMIT)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }
}
