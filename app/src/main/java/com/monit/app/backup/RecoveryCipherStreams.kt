package com.monit.app.backup

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher

internal const val RECOVERY_CHUNK = 8 * 1024
internal const val RECOVERY_PAYLOAD_LIMIT = 8 * 1024 * 1024

/** 암호화·sync·close 중 하나라도 실패하면 성공으로 반환하지 않는다. */
internal fun writeRecoveryOutput(output: OutputStream, sync: (OutputStream) -> Unit, encrypt: (OutputStream) -> Unit) {
    output.use {
        encrypt(it)
        sync(it)
    }
}

/** 출력 chunk는 쓰기·인증·취소 실패를 포함해 소거한다. 호출자가 소유한 payload는 유지한다. */
internal fun writeRecoveryCiphertext(output: OutputStream, payload: ByteArray, cipher: Cipher, checkActive: () -> Unit = {}) {
    require(payload.size <= RECOVERY_PAYLOAD_LIMIT)
    val expected = payload.size + 16
    var written = 0
    fun write(chunk: ByteArray?) {
        if (chunk == null) return
        try {
            require(chunk.size <= expected - written)
            checkActive()
            output.write(chunk)
            written += chunk.size
        } finally {
            chunk.fill(0)
        }
    }
    var offset = 0
    while (offset < payload.size) {
        checkActive()
        val count = minOf(RECOVERY_CHUNK, payload.size - offset)
        write(cipher.update(payload, offset, count))
        offset += count
    }
    checkActive()
    write(cipher.doFinal())
    require(written == expected)
    checkActive()
}

/**
 * read scratch와 인증 전 출력을 내부에서만 보유한다. 반환 배열은 호출자가 소거한다.
 * allocator는 새 전용 배열의 소유권을 이 함수로 넘기며 오류 시에도 소거한다.
 */
internal fun readRecoveryPlaintext(
    input: InputStream,
    payloadSize: Int,
    cipher: Cipher,
    checkActive: () -> Unit = {},
    allocatePlaintext: (Int) -> ByteArray = { ByteArray(it) },
): ByteArray {
    require(payloadSize in 0..RECOVERY_PAYLOAD_LIMIT)
    val scratch = ByteArray(RECOVERY_CHUNK)
    var quarantine: ByteArray? = null
    var written = 0
    var tail: ByteArray? = null
    try {
        var remaining = payloadSize + 16
        while (remaining > 0) {
            checkActive()
            val count = input.read(scratch, 0, minOf(scratch.size, remaining))
            require(count > 0)
            remaining -= count
            val chunk = cipher.update(scratch, 0, count)
            if (chunk != null) {
                try {
                    require(chunk.size <= payloadSize - written)
                    if (chunk.isNotEmpty()) {
                        if (quarantine == null) {
                            quarantine = allocatePlaintext(payloadSize)
                            require(checkNotNull(quarantine).size == payloadSize)
                        }
                        chunk.copyInto(checkNotNull(quarantine), written)
                        written += chunk.size
                    }
                } finally {
                    chunk.fill(0)
                }
            }
        }
        require(input.read() == -1)
        checkActive()
        tail = cipher.doFinal()
        checkActive()
        val final = tail
        if (quarantine == null) {
            require((final?.size ?: 0) == payloadSize)
            val result = final ?: ByteArray(0)
            tail = null
            return result
        }
        require((final?.size ?: 0) <= payloadSize - written)
        if (final != null) {
            final.copyInto(checkNotNull(quarantine), written)
            written += final.size
        }
        require(written == payloadSize)
        val result = checkNotNull(quarantine)
        quarantine = null
        return result
    } finally {
        scratch.fill(0)
        quarantine?.fill(0)
        tail?.fill(0)
    }
}

/** close도 성공한 뒤에만 인증된 평문을 callback에 빌려주고 모든 종료 경로에서 소거한다. */
internal fun withRecoveryPlaintext(input: InputStream, decrypt: (InputStream) -> ByteArray, verify: (ByteArray) -> Unit) {
    var plain: ByteArray? = null
    try {
        input.use { plain = decrypt(it) }
        verify(checkNotNull(plain))
    } finally {
        plain?.fill(0)
    }
}
