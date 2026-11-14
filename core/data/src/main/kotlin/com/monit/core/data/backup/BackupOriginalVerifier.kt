package com.monit.core.data.backup

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

internal const val BACKUP_ORIGINAL_CHUNK = 8 * 1024

/** 호출 안에서만 사용하는 scratch를 성공·실패 모두 소거한다. 완전한 원문/ASCII 사본은 만들지 않는다. */
internal fun verifyBackupOriginal(encoded: String, expectedSize: Long, expectedHash: String, scratch: ByteArray) {
    try {
        require(scratch.size == BACKUP_ORIGINAL_CHUNK)
        require(expectedSize in 0..(2 * 1024 * 1024L))
        require(requireCanonicalBase64(encoded) == expectedSize)
        val digest = MessageDigest.getInstance("SHA-256")
        val ascii = object : InputStream() {
            private var offset = 0
            override fun read(): Int = if (offset == encoded.length) -1 else encoded[offset++].code
        }
        var size = 0L
        Base64.getDecoder().wrap(ascii).use { decoded ->
            while (true) {
                val count = decoded.read(scratch)
                if (count == -1) break
                require(count > 0)
                size = Math.addExact(size, count.toLong())
                require(size <= expectedSize)
                digest.update(scratch, 0, count)
            }
        }
        require(size == expectedSize)
        val hash = digest.digest()
        try {
            require(hash.joinToString("") { "%02x".format(it.toInt() and 255) } == expectedHash)
        } finally {
            hash.fill(0)
        }
    } finally {
        scratch.fill(0)
    }
}

private fun requireCanonicalBase64(value: String): Long {
    require(value.length % 4 == 0)
    if (value.isEmpty()) return 0L
    var padding = 0
    if (value.last() == '=') padding++
    if (value[value.lastIndex - 1] == '=') padding++
    val dataLength = value.length - padding
    for (index in 0 until dataLength) require(base64Value(value[index]) >= 0)
    for (index in dataLength until value.length) require(value[index] == '=')
    if (padding == 1) require(base64Value(value[dataLength - 1]) and 3 == 0)
    if (padding == 2) require(base64Value(value[dataLength - 1]) and 15 == 0)
    return Math.subtractExact(Math.multiplyExact(value.length.toLong() / 4, 3), padding.toLong())
}

private fun base64Value(value: Char): Int = when (value) {
    in 'A'..'Z' -> value.code - 'A'.code
    in 'a'..'z' -> value.code - 'a'.code + 26
    in '0'..'9' -> value.code - '0'.code + 52
    '+' -> 62
    '/' -> 63
    else -> -1
}
