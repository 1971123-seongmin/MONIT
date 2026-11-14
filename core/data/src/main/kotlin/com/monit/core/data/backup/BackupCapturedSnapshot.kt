package com.monit.core.data.backup

import com.monit.core.model.BackupProblem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 현재 A의 tree와 그 순간 직렬화한 텍스트를 함께 소유한다. 호출자는 tree 소유권을 넘기고 수정하지 않는다.
 * strict 검사는 그대로 수행하고, 논리 검증 완료 뒤에만 실제 plain을 만든다.
 */
internal class BackupCapturedSnapshot private constructor(private val root: JSONObject, private val text: String, private val byteLength: Long) {
    private var plain: ByteArray? = null

    suspend fun parseRoot(): JSONObject {
        if (byteLength > BackupEnvelope.MAX_PAYLOAD) BackupJson.fail(BackupProblem.LIMIT)
        if (text.startsWith('\uFEFF')) BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
        BackupJson.checkText(text)
        // 작은 Long/실수/Decimal/array hole/임의 subclass는 parser 정규화가 필요하므로 기존 경로를 쓴다.
        return if (root.opt("formatVersion") == 3 && unchangedByParsing(root)) root else BackupJson.parseText(text)
    }

    fun encode(): ByteArray = plain ?: BackupJson.utf8(text).also { plain = it }

    fun clear() {
        plain?.fill(0)
    }

    private fun unchangedByParsing(value: Any?): Boolean = when {
        value === JSONObject.NULL -> true

        value is String || value is Boolean || value is Int -> true

        value is Long -> value < Int.MIN_VALUE || value > Int.MAX_VALUE

        value?.javaClass == JSONObject::class.java -> {
            val objectValue = value as JSONObject
            val keys = objectValue.keys()
            var same = true
            while (same && keys.hasNext()) same = unchangedByParsing(objectValue.get(keys.next()))
            same
        }

        value?.javaClass == JSONArray::class.java -> {
            val array = value as JSONArray
            var same = true
            var index = 0
            while (same && index < array.length()) same = unchangedByParsing(array.opt(index++))
            same
        }

        else -> false
    }

    companion object {
        fun capture(root: JSONObject): BackupCapturedSnapshot {
            val text = root.toString()
            // 기존 utf8(text)와 같이 전체 Unicode 오류를 먼저 판정하고 이후 parse에서8MiB를 검사한다.
            val length = BackupJson.utf8ByteCount(text)
            if (length > Int.MAX_VALUE) BackupJson.fail(BackupProblem.LIMIT)
            return BackupCapturedSnapshot(root, text, length)
        }
    }
}
