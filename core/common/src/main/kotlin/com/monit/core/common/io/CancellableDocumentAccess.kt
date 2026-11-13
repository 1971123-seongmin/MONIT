package com.monit.core.common.io

import android.os.CancellationSignal
import com.monit.core.common.coroutines.CoroutineContextProvider
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** 취소 신호와 descriptor close를 IO child에서 끝내고 실제 종료를 기다린다. */
class CancellableDocumentAccess @Inject constructor(private val contexts: CoroutineContextProvider) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> access(block: suspend (CancellationSignal, AtomicReference<Closeable?>) -> T): T = coroutineScope {
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
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
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
