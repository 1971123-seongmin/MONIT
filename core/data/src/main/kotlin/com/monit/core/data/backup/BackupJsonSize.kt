package com.monit.core.data.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Room 캡처 또는 strict 파싱한 tree의 기존 JSON 표현 길이만 센다. 스키마 검증을 대신하지 않는다.
 * JSON-native 값만 다루며 숫자는 플랫폼 numberToString의 유한 수 검사/표현을 따른다.
 * 출력 문자열이나 leaf 사본을 만들지 않는다.
 */
internal object BackupJsonSize {
    fun count(value: Any?, member: (JSONObject, String) -> Any? = { owner, key -> owner.get(key) }): Long = Counter(member).size(value)

    private class Counter(private val member: (JSONObject, String) -> Any?) {
        fun size(value: Any?): Long = when {
            value == null || value === JSONObject.NULL -> 4L

            value is String -> stringSize(value)

            value is Boolean -> if (value) 4L else 5L

            value is Number -> JSONObject.numberToString(value).length.toLong()

            value is JSONArray -> {
                var bytes = 2L
                for (index in 0 until value.length()) {
                    if (index > 0) bytes = Math.addExact(bytes, 1)
                    bytes = Math.addExact(bytes, size(value.opt(index)))
                }
                bytes
            }

            value is JSONObject -> {
                var bytes = 2L
                var first = true
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!first) bytes = Math.addExact(bytes, 1)
                    first = false
                    bytes = Math.addExact(bytes, Math.addExact(stringSize(key), 1))
                    bytes = Math.addExact(bytes, size(member(value, key)))
                }
                bytes
            }

            else -> error("Expected a validated JSON value")
        }

        private fun stringSize(value: String): Long {
            var bytes = Math.addExact(BackupJson.utf8ByteCount(value), 2)
            for (character in value) {
                val extra = when (character) {
                    '"', '\\', '/', '\t', '\b', '\n', '\r', '\u000C' -> 1L
                    in '\u0000'..'\u001F' -> 5L
                    else -> 0L
                }
                bytes = Math.addExact(bytes, extra)
            }
            return bytes
        }
    }
}
