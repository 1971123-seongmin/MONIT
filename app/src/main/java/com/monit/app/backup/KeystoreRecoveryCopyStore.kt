package com.monit.app.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 휴대 파일과 다른 기기 전용 형식. Keystore/파일 검증 실패 시 평문 fallback이나 복원 시작은 없다. */
@Singleton
class KeystoreRecoveryCopyStore @Inject constructor(@param:ApplicationContext private val context: Context, private val contexts: CoroutineContextProvider) : RecoveryCopyStore {
    private val mutex = Mutex()
    override suspend fun protect(context: LedgerContext, payload: ByteArray): RecoveryCopyProof = withTimeout(60_000) {
        withContext(contexts.io) {
            if (!mutex.tryLock()) throw BackupException(BackupProblem.RECOVERY_COPY)
            try {
                require(payload.size <= 8 * 1024 * 1024)
                val folder = File(this@KeystoreRecoveryCopyStore.context.filesDir, "restore-recovery")
                require(folder.isDirectory || folder.mkdirs())
                val database = this@KeystoreRecoveryCopyStore.context.getDatabasePath("monit-ledger.db")
                val databaseSize = listOf(database, File(database.path + "-wal"), File(database.path + "-shm")).sumOf { it.length() }
                val needed = maxOf(64 * 1024 * 1024L, Math.addExact(Math.multiplyExact(databaseSize, 2), Math.addExact(Math.multiplyExact(payload.size.toLong(), 4), 4 * 1024 * 1024L)))
                require(folder.usableSpace >= needed)
                currentCoroutineContext().ensureActive()
                val id = UUID.randomUUID().toString()
                val candidate = File(folder, "$id.pending")
                val target = File(folder, "verified.monitrec")
                require(candidate.canonicalFile.parentFile == folder.canonicalFile && target.canonicalFile.parentFile == folder.canonicalFile)
                val encrypted = LocalRecoveryCipher.encrypt(payload, context, key())
                try {
                    currentCoroutineContext().ensureActive()
                    require(candidate.createNewFile())
                    FileOutputStream(candidate).use { stream ->
                        stream.write(encrypted)
                        stream.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    verify(candidate, context, payload)
                    currentCoroutineContext().ensureActive()
                    // 같은 앱 내부 디렉터리에서 이미 검증한 암호문만 원자적으로 교체한다.
                    Os.rename(candidate.absolutePath, target.absolutePath)
                    val directory = Os.open(folder.absolutePath, OsConstants.O_RDONLY, 0)
                    try {
                        Os.fsync(directory)
                    } finally {
                        Os.close(directory)
                    }
                    verify(target, context, payload)
                    currentCoroutineContext().ensureActive()
                    RecoveryCopyProof(id, digest(payload), payload.size)
                } finally {
                    encrypted.fill(0)
                }
                // 오류 상태의 암호화 사본은 진단/복구를 위해 자동 폐기하지 않는다.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw BackupException(BackupProblem.RECOVERY_COPY)
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun verify(file: File, context: LedgerContext, payload: ByteArray) {
        require(file.length() in 116L..(8 * 1024 * 1024L + 116))
        val encrypted = file.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(count > 0 && output.size().toLong() + count <= 8 * 1024 * 1024L + 116)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            } finally {
                chunk.fill(0)
            }
        }
        val plain = LocalRecoveryCipher.decrypt(encrypted, context, key())
        try {
            require(MessageDigest.isEqual(payload, plain))
        } finally {
            plain.fill(0)
            encrypted.fill(0)
        }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) return checkNotNull(store.getKey(ALIAS, null) as? SecretKey)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object {
        private const val ALIAS = "monit-backup-recovery-v1"
    }
}

internal object LocalRecoveryCipher {
    private val magic = "MONITRC1".toByteArray(Charsets.US_ASCII)
    fun encrypt(payload: ByteArray, context: LedgerContext, key: SecretKey): ByteArray {
        require(payload.size <= 8 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        val header = ByteBuffer.allocate(100).put(magic).put(context.ledgerId.toByteArray(Charsets.US_ASCII)).put(context.storageGeneration.toByteArray(Charsets.US_ASCII)).put(cipher.iv).putLong(payload.size.toLong() + 16).array()
        cipher.updateAAD(header)
        return header + cipher.doFinal(payload)
    }
    fun decrypt(file: ByteArray, context: LedgerContext, key: SecretKey): ByteArray {
        require(file.size in 116..(8 * 1024 * 1024 + 116))
        val header = file.copyOfRange(0, 100)
        val buffer = ByteBuffer.wrap(header)
        require(ByteArray(8).also(buffer::get).contentEquals(magic))
        require(String(ByteArray(36).also(buffer::get), Charsets.US_ASCII) == context.ledgerId)
        require(String(ByteArray(36).also(buffer::get), Charsets.US_ASCII) == context.storageGeneration)
        val nonce = ByteArray(12).also(buffer::get)
        require(buffer.long == file.size.toLong() - 100)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            updateAAD(header)
            doFinal(file, 100, file.size - 100)
        }
    }
}
