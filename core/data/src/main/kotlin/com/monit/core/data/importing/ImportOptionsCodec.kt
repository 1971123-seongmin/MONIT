package com.monit.core.data.importing

import com.monit.core.model.*
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** 보관된 root의 사용자 판단도 별도 strict 필드 집합으로 읽는다. */
internal fun importOptions(json: JSONObject): ImportReviewOptions {
    json.onlyImport("targetConfirmed", "accounts", "anchors", "groups", "confirmedRows", "distinctRows", "excludedRows")
    val accounts = json.objects("accounts").map {
        it.onlyImport("walletNamespace", "sourceId", "accountId", "confirmed")
        ImportAccountMapping(it.string("walletNamespace"), it.string("sourceId"), requireLedgerId(it.string("accountId")), it.boolean("confirmed"))
    }
    val anchors = json.objects("anchors").map {
        it.onlyImport("accountId", "anchorId", "revision", "date", "action", "amount", "reason", "confirmed")
        val revision = it.string("revision").also { value -> require(value.matches(Regex("[1-9][0-9]*"))) }.toLong()
        val date = it.string("date").also { value -> require(LocalDate.parse(value).toString() == value) }
        ImportAnchorChoice(requireLedgerId(it.string("accountId")), requireLedgerId(it.string("anchorId")), revision, date, ImportAnchorAction.valueOf(it.string("action")), if (it.isNull("amount")) null else it.string("amount").also { value -> require(value.matches(Regex("0|-[1-9][0-9]*|[1-9][0-9]*"))) }.toLong(), it.string("reason", true), it.boolean("confirmed"))
    }
    val groups = json.objects("groups").map {
        it.onlyImport("locators", "confirmed")
        ImportTransferGroup(it.strings("locators"), it.boolean("confirmed"))
    }
    val value = ImportReviewOptions(json.boolean("targetConfirmed"), accounts, anchors, groups, json.strings("confirmedRows").toSet(), json.strings("distinctRows").toSet(), json.strings("excludedRows").toSet())
    require(canonicalImport(optionsJson(value)) == canonicalImport(json))
    return value
}

private fun JSONObject.onlyImport(vararg names: String) = require(keys().asSequence().toSet() == names.toSet())
private fun JSONObject.string(name: String, blank: Boolean = false): String = (get(name) as String).also { requireLedgerText(it, allowBlank = blank) }
private fun JSONObject.boolean(name: String): Boolean = get(name) as Boolean
private fun JSONObject.objects(name: String): List<JSONObject> = (get(name) as JSONArray).let { array -> (0 until array.length()).map { array.get(it) as JSONObject } }
private fun JSONObject.strings(name: String): List<String> = (get(name) as JSONArray).let { array -> (0 until array.length()).map { (array.get(it) as String).also { value -> requireLedgerText(value) } } }.also { require(it.distinct().size == it.size) }
