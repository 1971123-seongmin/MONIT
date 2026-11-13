package com.monit.feature.accounts.importing

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.io.CancellableDocumentAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 사용자 파일 선택의 임시 content 권한만 읽는다. 파일 복사/권한 영속화/원본 쓰기는 없다. */
fun interface ImportDocumentReader {
    suspend fun read(uri: Uri, maximumBytes: Int): ByteArray
}

class ContentImportDocumentReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val contexts: CoroutineContextProvider,
) : ImportDocumentReader {
    override suspend fun read(uri: Uri, maximumBytes: Int): ByteArray = withTimeout(60_000) {
        CancellableDocumentAccess(contexts).access { signal, active ->
            require(uri.scheme == "content")
            require(maximumBytes > 0)
            currentCoroutineContext().ensureActive()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("Import input unavailable")
            active.set(descriptor)
            signal.throwIfCanceled()
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            input.use { stream ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, 8192))
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw IOException("Import input made no progress")
                    if (output.size().toLong() + count > maximumBytes) throw ImportInputTooLargeException()
                    output.write(buffer, 0, count)
                }
                currentCoroutineContext().ensureActive()
                output.toByteArray()
            }
        }
    }
}

class ImportInputTooLargeException : IOException("Import input exceeds preview capacity")
