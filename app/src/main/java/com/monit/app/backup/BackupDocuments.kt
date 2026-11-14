package com.monit.app.backup

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import dagger.hilt.android.qualifiers.ApplicationContext
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

    internal suspend fun <T> access(block: suspend (CancellationSignal, AtomicReference<Closeable?>) -> T): T = try {
        com.monit.core.common.io.CancellableDocumentAccess(contexts).access(block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: BackupException) {
        throw error
    } catch (_: Exception) {
        throw BackupException(BackupProblem.IO)
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

    fun read(input: InputStream): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var output: ByteArray? = null
        try {
            input.use { stream ->
                var total = 0L
                var zeros = 0
                var ended = false
                while (!ended) {
                    val chunk = ByteArray(minOf(8192L, MAX_FILE.toLong() - total + 1).toInt())
                    chunks.add(chunk)
                    var used = 0
                    while (used < chunk.size) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val count = stream.read(chunk, used, chunk.size - used)
                        if (count < 0) {
                            ended = true
                            break
                        }
                        if (count == 0) {
                            require(++zeros <= 16)
                            continue
                        }
                        zeros = 0
                        used += count
                        if (total + used > MAX_FILE) throw BackupException(BackupProblem.LIMIT)
                    }
                    total += used
                }
                val result = ByteArray(total.toInt())
                output = result
                var offset = 0
                for (chunk in chunks) {
                    val count = minOf(chunk.size, result.size - offset)
                    chunk.copyInto(result, offset, 0, count)
                    offset += count
                }
            }
            return checkNotNull(output)
        } catch (failure: Throwable) {
            output?.fill(0)
            throw failure
        } finally {
            chunks.forEach { it.fill(0) }
        }
    }
}
