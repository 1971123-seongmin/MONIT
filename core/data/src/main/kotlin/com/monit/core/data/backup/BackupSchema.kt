package com.monit.core.data.backup

import com.monit.core.model.BackupProblem
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** 번들에 고정한 백업 schema의 사용 키워드만 판정한다. 외부 ref/임의 스키마/네트워크 로드는 없다. */
internal class BackupSchema private constructor(private val root: JSONObject) {
    fun validate(value: JSONObject) {
        if (!accepts(root, value)) BackupJson.fail(BackupProblem.INVALID_PAYLOAD)
    }

    private fun accepts(schema: JSONObject, value: Any): Boolean {
        if (schema.has("\$ref")) {
            val ref = schema.getString("\$ref")
            check(ref.startsWith("#/\$defs/") && '/' !in ref.removePrefix("#/\$defs/"))
            return accepts(root.getJSONObject("\$defs").getJSONObject(ref.removePrefix("#/\$defs/")), value)
        }
        if (schema.has("const") && !same(schema.get("const"), value)) return false
        if (schema.has("enum") && schema.getJSONArray("enum").values().none { same(it, value) }) return false
        if (schema.has("anyOf") && schema.getJSONArray("anyOf").values().none { accepts(it as JSONObject, value) }) return false
        if (schema.has("allOf") && schema.getJSONArray("allOf").values().any { !accepts(it as JSONObject, value) }) return false
        if (schema.has("if")) {
            val branch = if (accepts(schema.getJSONObject("if"), value)) "then" else "else"
            if (schema.has(branch) && !accepts(schema.getJSONObject(branch), value)) return false
        }
        if (schema.has("type")) {
            val types = schema.get("type").let { if (it is JSONArray) it.values().map { type -> type as String } else listOf(it as String) }
            if (types.none { type ->
                    when (type) {
                        "object" -> value is JSONObject
                        "array" -> value is JSONArray
                        "string" -> value is String
                        "integer" -> value is Int || value is Long
                        "boolean" -> value is Boolean
                        "null" -> value === JSONObject.NULL
                        else -> error("Unsupported bundled backup schema type")
                    }
                }
            ) return false
        }
        if (value is JSONObject) {
            val keys = value.keys().asSequence().toSet()
            if (schema.has("required") && schema.getJSONArray("required").values().any { it !in keys }) return false
            val properties = schema.optJSONObject("properties")
            if (schema.opt("additionalProperties") == false && keys.any { properties?.has(it) != true }) return false
            if (properties != null) for (key in keys) if (properties.has(key) && !accepts(properties.getJSONObject(key), value.get(key))) return false
        }
        if (value is JSONArray) {
            if (schema.has("maxItems") && value.length() > schema.getInt("maxItems")) return false
            if (schema.has("minItems") && value.length() < schema.getInt("minItems")) return false
            if (schema.optBoolean("uniqueItems")) {
                val seen = HashSet<String>()
                if (value.values().any { !seen.add(canonical(it)) }) return false
            }
            if (schema.has("items") && value.values().any { !accepts(schema.getJSONObject("items"), it) }) return false
        }
        if (value is String) {
            val count = value.codePointCount(0, value.length)
            if (schema.has("maxLength") && count > schema.getInt("maxLength")) return false
            if (schema.has("minLength") && count < schema.getInt("minLength")) return false
            if (schema.has("pattern") && !Regex(schema.getString("pattern")).containsMatchIn(value)) return false
            if (schema.optString("format") == "date-time") try {
                Instant.parse(value)
            } catch (_: java.time.DateTimeException) {
                return false
            }
        }
        if (value is Number) {
            if (schema.has("minimum") && value.toLong() < schema.getLong("minimum")) return false
            if (schema.has("maximum") && value.toLong() > schema.getLong("maximum")) return false
        }
        return true
    }

    private fun same(a: Any, b: Any): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && a.values().indices.all { same(a.get(it), b.get(it)) }
        else -> a == b
    }
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> value.values().joinToString(",", "[", "]", transform = ::canonical)
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    companion object {
        fun bundled(version: Int): BackupSchema {
            require(version == 1 || version == 2)
            val text = checkNotNull(BackupSchema::class.java.getResourceAsStream("/backup/backup-v$version.schema.json")).use { it.reader(Charsets.UTF_8).readText() }
            return BackupSchema(JSONObject(text))
        }
    }
}

internal fun JSONArray.values(): List<Any> = (0 until length()).map(::get)
