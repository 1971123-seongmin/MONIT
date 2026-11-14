package com.monit.app.backup.probe

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import java.io.File
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** API26 host의 cursor 계약을 검증하며 실제 기기의 SAF/Keystore 성공을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class, manifest = Config.NONE)
class ProbeDocumentsProviderTest {
    private lateinit var provider: ProbeDocumentsProvider
    private lateinit var documentId: String

    @Before fun open() {
        provider = ProbeDocumentsProvider()
        provider.attachInfo(
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getPackageName(): String = ProbeDocumentsProvider.PACKAGE
            },
            ProviderInfo().apply {
                authority = ProbeDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            },
        )
        documentId = provider.createDocument("root", "application/octet-stream", "synthetic-projection")
    }

    @After fun close() {
        if (::documentId.isInitialized) {
            val app = RuntimeEnvironment.getApplication()
            assertTrue(File(app.filesDir, "backup-probe-provider-72/$documentId").delete())
        }
        if (::provider.isInitialized) provider.shutdown()
    }

    /** COLUMN_SIZE 단독 조회는 UUID 대신 해당 Long을 반환하고 FALSE_SIZE는 Long.MAX_VALUE를 유지한다. */
    @Test fun sizeOnlyProjectionReturnsFalseMetadataAsLong() {
        provider.call("probe.configure", documentId, Bundle().apply { putString("mode", ProbeDocumentMode.FALSE_SIZE.name) })
        val projection = arrayOf(Document.COLUMN_SIZE)
        provider.queryDocument(documentId, projection).use { cursor ->
            assertContentEquals(projection, cursor.columnNames)
            assertTrue(cursor.moveToFirst())
            assertEquals(Long.MAX_VALUE, cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
        }
    }

    /** 부분·재정렬·알 수 없는 컬럼은 요청한 순서를 유지하고 알 수 없는 값은 null이다. */
    @Test fun documentProjectionPreservesSubsetOrderAndUnknownColumns() {
        val projection = arrayOf(Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID, "unknown")
        provider.queryDocument(documentId, projection).use { cursor ->
            assertContentEquals(projection, cursor.columnNames)
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals("synthetic.monitbak", cursor.getString(1))
            assertEquals(documentId, cursor.getString(2))
            assertTrue(cursor.isNull(3))
        }
    }

    /** null projection은 기존 다섯 컬럼과 document ID·이름·MIME·flags·실제 크기를 유지한다. */
    @Test fun nullDocumentProjectionKeepsExistingDefaults() {
        provider.queryDocument(documentId, null).use { cursor ->
            assertContentEquals(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE), cursor.columnNames)
            assertTrue(cursor.moveToFirst())
            assertEquals(documentId, cursor.getString(0))
            assertEquals("synthetic.monitbak", cursor.getString(1))
            assertEquals("application/octet-stream", cursor.getString(2))
            assertEquals(Document.FLAG_SUPPORTS_WRITE, cursor.getInt(3))
            assertEquals(0L, cursor.getLong(4))
        }
    }

    /** 가상 root 문서도 일반 문서와 같은 projection 계약을 따르며 directory 값을 보존한다. */
    @Test fun rootDocumentProjectionKeepsDirectoryValues() {
        val projection = arrayOf(Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_DOCUMENT_ID)
        provider.queryDocument("root", projection).use { cursor ->
            assertContentEquals(projection, cursor.columnNames)
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(Document.MIME_TYPE_DIR, cursor.getString(1))
            assertEquals("root", cursor.getString(2))
        }
    }

    /** roots의 기본 네 컬럼과 부분·재정렬 projection이 각 컬럼의 원래 값을 반환한다. */
    @Test fun rootsProjectionPreservesDefaultsAndRequestedOrder() {
        provider.queryRoots(null).use { cursor ->
            assertContentEquals(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS), cursor.columnNames)
            assertEquals(1, cursor.count)
        }
        val projection = arrayOf(Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_ROOT_ID, "unknown")
        provider.queryRoots(projection).use { cursor ->
            assertContentEquals(projection, cursor.columnNames)
            assertTrue(cursor.moveToFirst())
            assertEquals(Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE, cursor.getInt(0))
            assertEquals("공개 합성 계측", cursor.getString(1))
            assertEquals("root", cursor.getString(2))
            assertTrue(cursor.isNull(3))
        }
    }

    /** 빈 children도 요청 컬럼을 유지하고 null일 때 기존 두 컬럼을 반환한다. */
    @Test fun childrenProjectionPreservesEmptyResultAndRequestedColumns() {
        provider.queryChildDocuments("root", null, sortOrder = null).use { cursor ->
            assertContentEquals(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), cursor.columnNames)
            assertEquals(0, cursor.count)
        }
        val projection = arrayOf(Document.COLUMN_SIZE, Document.COLUMN_DOCUMENT_ID)
        provider.queryChildDocuments("root", projection, sortOrder = null).use { cursor ->
            assertContentEquals(projection, cursor.columnNames)
            assertEquals(0, cursor.count)
        }
        assertFailsWith<IllegalArgumentException> { provider.queryChildDocuments(documentId, projection, sortOrder = null) }
    }
}
