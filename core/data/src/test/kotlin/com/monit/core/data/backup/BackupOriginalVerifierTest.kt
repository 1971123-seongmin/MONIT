package com.monit.core.data.backup

import android.app.Application
import java.util.Base64
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class, manifest = Config.NONE)
class BackupOriginalVerifierTest {
    /** 빈 원문·padding 세 종류·chunk 경계·2MiB를 표준 decode/hash와 대조하고 scratch 소거를 확인한다. */
    @Test fun canonicalOriginalsMatchStandardBytesAndHash() {
        val scratch = ByteArray(BACKUP_ORIGINAL_CHUNK)
        for (size in listOf(0, 1, 2, 3, 8191, 8192, 8193, 2 * 1024 * 1024)) {
            val original = ByteArray(size) { (it % 251).toByte() }
            val encoded = Base64.getEncoder().encodeToString(original)
            scratch.fill(53)
            verifyBackupOriginal(encoded, size.toLong(), BackupJson.digest(original), scratch)
            assertTrue(scratch.all { it == 0.toByte() })
        }
    }

    /** 모든 ASCII 치환과 Unicode·padding 변형을 이전 decode→encode 동일성 판정과 대조한다. */
    @Test fun alphabetPaddingAndUnusedBitsMatchOldCanonicalPredicate() {
        val candidates = mutableListOf("", "AQ", "AQI", "A===", "====", "AA=A", "AQ==\n", "AQ==AAAA", "ＡQ==", "/x==", "//9=")
        for (base in listOf("AQ==", "AQI=", "AQID")) for (index in base.indices) for (code in 0..127) {
            candidates += base.replaceRange(index, index + 1, code.toChar().toString())
        }
        val scratch = ByteArray(BACKUP_ORIGINAL_CHUNK)
        for (encoded in candidates) {
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            val expected = decoded != null && Base64.getEncoder().encodeToString(decoded) == encoded
            scratch.fill(59)
            val accepted = runCatching { verifyBackupOriginal(encoded, (decoded?.size ?: 0).toLong(), BackupJson.digest(decoded ?: byteArrayOf()), scratch) }.isSuccess
            assertEquals(expected, accepted, "encoded character count=${encoded.length}")
            assertTrue(scratch.all { it == 0.toByte() })
        }
    }

    /** 길이·hash·상한 불일치와 malformed 입력은 실패하고 scratch에 원문을 남기지 않는다. */
    @Test fun lengthHashAndPerOriginalLimitsStillRejectAndWipe() {
        val bytes = ByteArray(31) { 61 }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val hash = BackupJson.digest(bytes)
        val scratch = ByteArray(BACKUP_ORIGINAL_CHUNK)
        for ((value, size, digest) in listOf(Triple(encoded, 30L, hash), Triple(encoded, 32L, hash), Triple(encoded, 31L, "0".repeat(64)), Triple(encoded, -1L, hash), Triple(encoded, 2 * 1024 * 1024L + 1, hash), Triple("AB==", 1L, hash))) {
            scratch.fill(61)
            assertFailsWith<IllegalArgumentException> { verifyBackupOriginal(value, size, digest, scratch) }
            assertTrue(scratch.all { it == 0.toByte() })
        }
        val oversized = ByteArray(2 * 1024 * 1024 + 1) { 67 }
        assertFailsWith<IllegalArgumentException> { verifyBackupOriginal(Base64.getEncoder().encodeToString(oversized), 2 * 1024 * 1024L, BackupJson.digest(oversized), scratch) }
        assertTrue(scratch.all { it == 0.toByte() })
    }
}
