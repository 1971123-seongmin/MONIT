package com.monit.core.data.backup

import android.util.JsonReader
import android.util.JsonToken
import com.monit.core.model.BackupException
import com.monit.core.model.BackupProblem
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** 인증을 마친 바이트만 받는다. JSONObject가 중복 키/숫자 표현을 잃기 전에 strict pass를 수행한다. */
internal object BackupJson {
    suspend fun parse(bytes: ByteArray): JSONObject {
        if (bytes.size > BackupEnvelope.MAX_PAYLOAD) fail(BackupProblem.LIMIT)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(BackupProblem.INVALID_PAYLOAD)
        }
        if (text.startsWith('\uFEFF')) fail(BackupProblem.INVALID_PAYLOAD)
        return parseText(text)
    }

    suspend fun parseText(text: String): JSONObject {
        if (utf8(text).size > BackupEnvelope.MAX_PAYLOAD) fail(BackupProblem.LIMIT)
        var tokens = 0
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                suspend fun visit(depth: Int) {
                    if (depth > 16 || ++tokens > 1_000_000) fail(BackupProblem.LIMIT)
                    if (tokens % 1024 == 0) currentCoroutineContext().ensureActive()
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            val names = HashSet<String>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (++tokens > 1_000_000) fail(BackupProblem.LIMIT)
                                val name = reader.nextName()
                                utf8(name)
                                if (!names.add(name)) fail(BackupProblem.INVALID_PAYLOAD)
                                visit(depth + 1)
                            }
                            reader.endObject()
                        }

                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) visit(depth + 1)
                            reader.endArray()
                        }

                        JsonToken.STRING -> utf8(reader.nextString())

                        JsonToken.NUMBER -> {
                            if (!reader.nextString().matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) fail(BackupProblem.INVALID_PAYLOAD)
                        }

                        JsonToken.BOOLEAN -> reader.nextBoolean()

                        JsonToken.NULL -> reader.nextNull()

                        else -> fail(BackupProblem.INVALID_PAYLOAD)
                    }
                }
                visit(0)
                if (reader.peek() != JsonToken.END_DOCUMENT) fail(BackupProblem.INVALID_PAYLOAD)
            }
            currentCoroutineContext().ensureActive()
            return JSONObject(text)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: org.json.JSONException) {
            fail(BackupProblem.INVALID_PAYLOAD)
        } catch (_: java.io.IOException) {
            fail(BackupProblem.INVALID_PAYLOAD)
        } catch (_: IllegalArgumentException) {
            fail(BackupProblem.INVALID_PAYLOAD)
        } catch (error: IllegalStateException) {
            if (error is BackupException) throw error else fail(BackupProblem.INVALID_PAYLOAD)
        }
    }

    fun utf8(text: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: java.nio.charset.CharacterCodingException) {
        fail(BackupProblem.INVALID_PAYLOAD)
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun digest(text: String): String = digest(utf8(text))
    fun fail(problem: BackupProblem): Nothing = throw BackupException(problem)
}

internal fun JSONObject.fields(vararg names: String) {
    if (keys().asSequence().toSet() != names.toSet()) BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
}
internal fun JSONObject.text(key: String): String = get(key) as? String ?: BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
internal fun JSONObject.list(key: String): List<JSONObject> = (get(key) as? JSONArray ?: BackupJson.fail(BackupProblem.INVALID_PAYLOAD)).let { array -> (0 until array.length()).map { array.get(it) as? JSONObject ?: BackupJson.fail(BackupProblem.INVALID_PAYLOAD) } }
internal fun JSONObject.optional(key: String): String? = if (isNull(key)) null else text(key)
internal fun JSONObject.exactLong(key: String): Long = text(key).also { if (!it.matches(Regex("0|-[1-9][0-9]*|[1-9][0-9]*"))) BackupJson.fail(BackupProblem.INVALID_PAYLOAD) }.toLong()
