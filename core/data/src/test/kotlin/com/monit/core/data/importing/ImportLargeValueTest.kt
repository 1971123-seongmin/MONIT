package com.monit.core.data.importing

import android.app.Application
import androidx.room.withTransaction
import com.monit.core.data.*
import com.monit.core.data.backup.*
import com.monit.core.model.LedgerContext
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 물리 읽기 경계 시험이다. 큰 임의 문자열을 업무상 유효한 반영 명령이라고 주장하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ImportLargeValueTest {
    /** 목적: CursorWindow보다 큰 원본/요청을 자르지 않고 멀티바이트 경계도 보존한다.
     * 조건: 2MiB BLOB과 첫 128KiB chunk의 끝에서 한글 UTF-8이 갈리는 2MiB 초과 TEXT.
     * 절차: 실제 파일 Room에 저장하고 동일 읽기 transaction에서 분할 loader로 읽는다.
     * 기대: bytes·문자열·SHA256이 완전히 같고 REQUESTED 상태/결과 없음도 그대로다.
     */
    @Test fun maximumOriginalAndLargerRootTextAreReadWithoutCursorWindowTruncation() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val database = createLedgerDatabase(app, "import-large.db")
        val context = LedgerContext(ImportPreviewFixture.id(1), ImportPreviewFixture.id(2))
        val original = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val raw = "a".repeat(128 * 1024 - 1) + "한".repeat(750_000)
        assertTrue(raw.toByteArray().size > 2 * 1024 * 1024)
        try {
            database.withTransaction {
                database.requireDurableWriter()
                database.ledgerDao().insertLedger(LedgerEntity(context.ledgerId, context.storageGeneration, 1))
                database.backupDao().original(BackupOriginalEntity(context.ledgerId, ImportPreviewFixture.id(3), "synthetic", "original.bin", original.size.toLong(), BackupJson.digest(original), original))
                database.importDao().insertBatch(ImportBatchEntity(context.ledgerId, ImportPreviewFixture.id(4), context.storageGeneration, raw, requestHash(raw), ImportPreviewFixture.id(3), 1, "a".repeat(64), "REQUESTED", null, "2026-11-13T00:00:00Z", null, false, false))
                val read = readBackupOriginals(database, context.ledgerId).single()
                assertContentEquals(original, read.bytes)
                assertEquals(BackupJson.digest(original), read.sha256)
                val batch = checkNotNull(readImportBatch(database, context.ledgerId, ImportPreviewFixture.id(4)))
                assertEquals(raw, batch.requestJson)
                assertEquals(requestHash(raw), batch.requestSha256)
                assertEquals("REQUESTED", batch.status)
                assertNull(batch.resultJson)
            }
        } finally {
            database.close()
            app.deleteDatabase("import-large.db")
        }
    }
}
