package com.monit.core.data

import com.monit.core.model.AccountNature
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerContext
import com.monit.core.model.TransactionKind
import com.monit.core.model.WriteReceipt
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

internal sealed interface WriteCommand {
    data class Account(val value: CreateAccount) : WriteCommand
    data class Transaction(val value: CreateTransaction) : WriteCommand
}

/** 내부 생성 명령 v1의 고정 필드 순서/정수 문자열/JSON escape. 백업 DTO가 아니다. */
internal fun captureWrite(context: LedgerContext, command: WriteCommand): String {
    val kind: String
    val id: String
    val value: String
    when (command) {
        is WriteCommand.Account -> {
            val a = command.value
            kind = "UPDATE_ACCOUNT"
            id = a.id
            value = jsonObject(
                "anchorId" to quote(a.anchorId), "name" to quote(a.name),
                "nature" to quote(a.nature.name), "anchorDate" to quote(a.anchorDate.toString()),
                "unknownReason" to quote(a.unknownReason),
            )
        }
        is WriteCommand.Transaction -> {
            val t = command.value
            kind = "CREATE_TRANSACTION"
            id = t.id
            value = jsonObject(
                "kind" to quote(t.kind.name), "date" to quote(t.date.toString()),
                "amount" to quote(t.amount.toString()), "debitAccountId" to nullableQuote(t.debitAccountId),
                "creditAccountId" to nullableQuote(t.creditAccountId), "memo" to quote(t.memo),
            )
        }
    }
    return jsonObject(
        "version" to "1", "ledgerId" to quote(context.ledgerId),
        "expectedStorageGeneration" to quote(context.storageGeneration), "kind" to quote(kind),
        "targetId" to quote(id), "expectedRevision" to "null", "value" to value,
    )
}

internal fun decodeWrite(context: LedgerContext, request: String): WriteCommand {
    val json = JSONObject(request)
    check(json.getInt("version") == 1 && json.isNull("expectedRevision"))
    check(json.getString("ledgerId") == context.ledgerId &&
        json.getString("expectedStorageGeneration") == context.storageGeneration)
    val value = json.getJSONObject("value")
    val id = json.getString("targetId")
    val command = when (json.getString("kind")) {
        "UPDATE_ACCOUNT" -> WriteCommand.Account(CreateAccount(
            id, value.getString("anchorId"), value.getString("name"),
            AccountNature.valueOf(value.getString("nature")),
            LocalDate.parse(value.getString("anchorDate")), value.getString("unknownReason"),
        ))
        "CREATE_TRANSACTION" -> WriteCommand.Transaction(CreateTransaction(
            id, TransactionKind.valueOf(value.getString("kind")),
            LocalDate.parse(value.getString("date")), value.getString("amount").toLong(),
            value.nullableString("debitAccountId"), value.nullableString("creditAccountId"),
            value.getString("memo"),
        ))
        else -> error("Unsupported captured write")
    }
    check(captureWrite(context, command) == request) { "Captured write is not canonical" }
    return command
}

internal fun requestHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun encodeRefs(refs: List<EntityRef>): String = refs.joinToString(
    prefix = "[", postfix = "]", separator = ",",
) { jsonObject("collection" to quote(it.collection), "id" to quote(it.id), "revision" to quote(it.revision.toString())) }

internal fun OperationEntity.receipt(): WriteReceipt {
    check(requestHash(requestJson) == requestSha256 && ledgerRevision > 0)
    val json = JSONArray(resultJson)
    val refs = (0 until json.length()).map { index ->
        val ref = json.getJSONObject(index)
        EntityRef(ref.getString("collection"), ref.getString("id"), ref.getString("revision").toLong())
    }
    check(refs.isNotEmpty() && refs.all { it.revision > 0 })
    return WriteReceipt(operationId, ledgerRevision, refs)
}

private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
private fun nullableQuote(value: String?): String = value?.let(::quote) ?: "null"
private fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(prefix = "{", postfix = "}", separator = ",") { quote(it.first) + ":" + it.second }

private fun quote(value: String): String = buildString {
    append('"')
    for (char in value) when {
        char == '"' -> append("\\\"")
        char == '\\' -> append("\\\\")
        char.code < 32 -> append("\\u" + char.code.toString(16).padStart(4, '0'))
        else -> append(char)
    }
    append('"')
}
