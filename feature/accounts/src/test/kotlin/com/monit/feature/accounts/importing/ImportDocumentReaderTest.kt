package com.monit.feature.accounts.importing

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.monit.core.common.coroutines.CoroutineContextProvider
import java.io.File
import java.io.FileDescriptor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ImportDocumentReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private lateinit var file: File
    private lateinit var provider: SyntheticProvider
    private lateinit var reader: ContentImportDocumentReader
    private val uri = Uri.parse("content://synthetic-import/preview")

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        file = temporary.newFile("synthetic-input.json")
        provider = SyntheticProvider(file)
        provider.attachInfo(app, ProviderInfo().apply { authority = "synthetic-import" })
        ShadowContentResolver.registerProviderInternal("synthetic-import", provider)
        reader = ContentImportDocumentReader(app, contexts)
    }

    /** 실제 content provider를 r 모드로 읽고 bytes 경계에서 원문을 바꾸거나 권한을 영속화하지 않는다. */
    @Test fun readsExactBytesAndClosesDescriptor() = runTest(dispatcher) {
        val bytes = "가\n 합성 원문 ".toByteArray(Charsets.UTF_8)
        file.writeBytes(bytes)
        assertContentEquals(bytes, reader.read(uri, bytes.size))
        assertEquals(listOf("r"), provider.modes)
        assertFalse(checkNotNull(provider.lastDescriptor).valid())
        assertContentEquals(bytes, file.readBytes())
        assertEquals(0, provider.writes)
    }

    /** 실제 읽은 bytes가 한도+1이면 절단한 성공 결과 없이 오류를 전파하고 원본/descriptor를 보존·정리한다. */
    @Test fun oversizedInputIsRejectedWithoutPartialResultOrOriginalMutation() = runTest(dispatcher) {
        val bytes = ByteArray(8193) { (it % 127).toByte() }
        file.writeBytes(bytes)
        assertFailsWith<ImportInputTooLargeException> { reader.read(uri, 8192) }
        assertFalse(checkNotNull(provider.lastDescriptor).valid())
        assertContentEquals(bytes, file.readBytes())
        assertEquals(0, provider.writes)
    }

    /**
     * 목적: 입력 열기 중 취소를 성공한 읽기로 바꾸지 않고 stream을 닫는다.
     * 조건: 합성 provider의 openFile에서 호출 Job을 취소한다.
     * 절차: LAZY Job에 참조 연결 → 시작 → 종료 대기.
     * 기대: Job 취소, descriptor 닫힘, 원본 불변·쓰기0이다. 무응답 외부 provider의 즉시 중단 보장은 아니다.
     */
    @Test fun cancellationClosesOpenedContentStream() = runTest(dispatcher) {
        val bytes = "합성 파일".toByteArray()
        file.writeBytes(bytes)
        lateinit var child: Job
        provider.onOpen = { child.cancel() }
        child = launch(start = CoroutineStart.LAZY) {
            reader.read(uri, 8192)
            fail("취소는 성공 반환이 아니다")
        }
        child.start()
        runCurrent()
        child.join()
        assertTrue(child.isCancelled)
        assertFalse(checkNotNull(provider.lastDescriptor).valid())
        assertContentEquals(bytes, file.readBytes())
        assertEquals(0, provider.writes)
    }

    /** content URI와 양수 한도 이외의 요청은 provider를 열기 전에 거부한다. */
    @Test fun unsupportedSchemeAndInvalidBudgetNeverOpenProvider() = runTest(dispatcher) {
        assertFailsWith<IllegalArgumentException> { reader.read(Uri.parse("file:///synthetic.json"), 1024) }
        assertFailsWith<IllegalArgumentException> { reader.read(uri, 0) }
        assertTrue(provider.modes.isEmpty())
        assertEquals(0, provider.writes)
    }
}

private class SyntheticProvider(private val file: File) : ContentProvider() {
    val modes = mutableListOf<String>()
    var lastDescriptor: FileDescriptor? = null
    var writes = 0
    var onOpen: () -> Unit = {}
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        modes += mode
        require(mode == "r")
        onOpen()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { lastDescriptor = it.fileDescriptor }
    }
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("metadata query 없음")
    private fun forbidden(): Nothing {
        writes++
        error("원본 쓰기 금지")
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = forbidden()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = forbidden()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = forbidden()
}
