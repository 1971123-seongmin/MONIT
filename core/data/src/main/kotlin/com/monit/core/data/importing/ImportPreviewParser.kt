package com.monit.core.data.importing

import android.util.JsonReader
import android.util.JsonToken
import com.monit.core.model.*
import com.monit.core.model.LedgerContext
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** 정의된 중간 검토 표현만 읽는다. 일반 Notion 응답/CSV를 자동 해석하지 않는다. */
object ImportPreviewParser {
    const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    const val MAX_ROWS = 10_000
    const val TRANSFORMER_VERSION = "monit-preview-1"

    suspend fun parse(bytes: ByteArray): ImportPreviewInput {
        if (bytes.size > MAX_INPUT_BYTES) fail(ImportFileProblem.TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            fail(ImportFileProblem.INVALID_UTF8)
        }
        try {
            val envelope = strictObject(text, MAX_INPUT_BYTES)
            envelope.only("version", "snapshotJson", "snapshotSha256", "mappingJson", "mappingSha256")
            if (envelope.get("version") != 1) fail(ImportFileProblem.UNSUPPORTED_VERSION)
            val snapshotText = envelope.string("snapshotJson")
            val mappingText = envelope.string("mappingJson")
            val snapshotHash = envelope.digest("snapshotSha256")
            val mappingHash = envelope.digest("mappingSha256")
            if (digest(snapshotText) != snapshotHash || digest(mappingText) != mappingHash) fail(ImportFileProblem.INVALID_DIGEST)
            val snapshot = strictObject(snapshotText)
            snapshot.only("capturedAt", "scope", "sources", "rows")
            val capturedAt = snapshot.label("capturedAt")
            Instant.parse(capturedAt)
            val scope = snapshot.label("scope")
            val coverage = snapshot.array("sources", 64).objects().map {
                it.only("namespace", "entityKind", "paginationComplete", "missingProperties")
                val known = it.has("missingProperties") && !it.isNull("missingProperties")
                ImportCoverage(it.label("namespace"), it.label("entityKind"), it.optionalBoolean("paginationComplete"), if (known) it.strings("missingProperties", 128) else emptyList(), known)
            }
            require(coverage.map { it.namespace to it.entityKind }.distinct().size == coverage.size)
            val rowArray = snapshot.get("rows") as? JSONArray ?: fail(ImportFileProblem.INVALID_JSON)
            if (rowArray.length() > MAX_ROWS) fail(ImportFileProblem.TOO_MANY_ROWS)
            val rows = rowArray.objects().map {
                currentCoroutineContext().ensureActive()
                it.only("sourceNamespace", "entityKind", "sourceId", "locator", "rawJson", "rawSha256", "relationsComplete", "relationNamespace")
                ImportSourceRow(
                    it.label("sourceNamespace"),
                    it.label("entityKind"),
                    it.optionalLabel("sourceId"),
                    it.label("locator"),
                    it.string("rawJson"),
                    it.string("rawSha256"),
                    it.optionalBoolean("relationsComplete"),
                    it.optionalLabel("relationNamespace"),
                )
            }
            require(rows.map { it.locator }.distinct().size == rows.size)
            val mapping = strictObject(mappingText)
            mapping.only("version", "transformerVersion", "target", "fields", "accounts", "choices")
            val version = mapping.label("version")
            val transformer = mapping.label("transformerVersion")
            if (transformer != TRANSFORMER_VERSION) fail(ImportFileProblem.UNSUPPORTED_VERSION)
            val target = if (!mapping.has("target") || mapping.isNull("target")) null else (mapping.get("target") as? JSONObject ?: fail(ImportFileProblem.INVALID_MAPPING))
            target?.only("ledgerId", "storageGeneration")
            val context = target?.let { LedgerContext(it.string("ledgerId"), it.string("storageGeneration")) }
            val fields = mapping.array("fields", 64).objects().map {
                it.only("namespace", "entityKind", "title", "date", "amount", "category", "debitRelation", "creditRelation", "walletNamespace", "currency", "currencyConfirmed")
                ImportFieldMapping(
                    it.label("namespace"), it.label("entityKind"), it.label("title"), it.label("date"), it.label("amount"),
                    it.label("category"), it.label("debitRelation"), it.label("creditRelation"), it.label("walletNamespace"),
                    it.optionalLabel("currency"), it.boolean("currencyConfirmed"),
                )
            }
            if (fields.map { it.namespace to it.entityKind }.distinct().size != fields.size) fail(ImportFileProblem.INVALID_MAPPING)
            if (fields.any { listOf(it.title, it.date, it.amount, it.category, it.debitRelation, it.creditRelation).distinct().size != 6 }) fail(ImportFileProblem.INVALID_MAPPING)
            val accounts = mapping.array("accounts", MAX_ROWS).objects().map {
                it.only("walletNamespace", "sourceId", "accountId", "confirmed")
                ImportAccountMapping(it.label("walletNamespace"), it.label("sourceId"), it.label("accountId"), it.boolean("confirmed"))
            }
            val choices = mapping.array("choices", MAX_ROWS).objects().map {
                it.only("locator", "decision", "confirmed")
                val decision = ImportDecision.valueOf(it.label("decision"))
                if (decision != ImportDecision.HELD && decision != ImportDecision.EXCLUDED) fail(ImportFileProblem.INVALID_MAPPING)
                ImportRowChoice(it.label("locator"), decision, it.boolean("confirmed"))
            }
            if (choices.map { it.locator }.distinct().size != choices.size || choices.any { choice -> rows.none { it.locator == choice.locator } }) fail(ImportFileProblem.INVALID_MAPPING)
            return ImportPreviewInput(digest(bytes), snapshotHash, mappingHash, capturedAt, scope, coverage, rows, version, transformer, context, fields, accounts, choices)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: ImportFileException) {
            throw problem
        } catch (_: Exception) {
            fail(ImportFileProblem.INVALID_JSON)
        }
    }

    /** 객체를 만들기 전에 모든 층의 중복 이름·깊이·노드·scalar 한도를 검사한다. */
    internal suspend fun strictObject(text: String, maximumScalar: Int = 1024 * 1024): JSONObject {
        var nodes = 0
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            suspend fun value(depth: Int) {
                if (++nodes > 200_000 || depth > 16) fail(ImportFileProblem.INVALID_JSON)
                if (nodes % 256 == 0) currentCoroutineContext().ensureActive()
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val names = mutableSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name.length > 256 || !names.add(name)) fail(ImportFileProblem.INVALID_JSON)
                            utf8(name)
                            value(depth + 1)
                        }
                        reader.endObject()
                    }

                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) value(depth + 1)
                        reader.endArray()
                    }

                    JsonToken.STRING, JsonToken.NUMBER -> {
                        val scalar = reader.nextString()
                        if (scalar.length > maximumScalar) fail(ImportFileProblem.INVALID_JSON)
                        utf8(scalar)
                    }

                    JsonToken.BOOLEAN -> reader.nextBoolean()

                    JsonToken.NULL -> reader.nextNull()

                    else -> fail(ImportFileProblem.INVALID_JSON)
                }
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) fail(ImportFileProblem.INVALID_JSON)
            value(0)
            if (reader.peek() != JsonToken.END_DOCUMENT) fail(ImportFileProblem.INVALID_JSON)
        }
        currentCoroutineContext().ensureActive()
        return JSONObject(text)
    }

    internal fun digest(text: String): String = digest(utf8(text))
    internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun utf8(text: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    private fun JSONObject.only(vararg allowed: String) {
        require(keys().asSequence().all { it in allowed })
    }
    private fun JSONObject.string(key: String) = get(key) as? String ?: fail(ImportFileProblem.INVALID_JSON)
    private fun JSONObject.label(key: String) = string(key).also { require(it.isNotBlank() && it.length <= 256) }
    private fun JSONObject.optionalLabel(key: String): String? = if (!has(key) || isNull(key)) null else label(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: fail(ImportFileProblem.INVALID_JSON)
    private fun JSONObject.optionalBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else boolean(key)
    private fun JSONObject.digest(key: String) = string(key).also { if (!it.matches(Regex("[0-9a-f]{64}"))) fail(ImportFileProblem.INVALID_DIGEST) }
    private fun JSONObject.array(key: String, limit: Int): JSONArray = (get(key) as? JSONArray ?: fail(ImportFileProblem.INVALID_JSON)).also { require(it.length() <= limit) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { get(it) as? JSONObject ?: fail(ImportFileProblem.INVALID_JSON) }
    private fun JSONObject.strings(key: String, limit: Int): List<String> = array(key, limit).let { array ->
        (0 until array.length()).map { index -> (array.get(index) as? String ?: fail(ImportFileProblem.INVALID_JSON)).also { require(it.length <= 256) } }
    }
    private fun fail(problem: ImportFileProblem): Nothing = throw ImportFileException(problem)
}
