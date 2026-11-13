package com.monit.core.data

import com.monit.core.model.*
import java.time.LocalDate
import org.json.JSONObject

private fun obj(vararg fields: Pair<String, String>) = fields.joinToString(",", "{", "}") { JSONObject.quote(it.first) + ":" + it.second }
private fun text(value: String?) = value?.let(JSONObject::quote) ?: "null"
private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)

internal fun captureDraftScope(scope: DraftScope): String = if (scope.transactionId != null) obj(
    "kind" to text(scope.kind.name),
    "origin" to text(scope.origin.name),
    "parent" to text(scope.parentDraftId),
    "request" to text(scope.selectionRequestId),
    "slot" to text(scope.slot?.name),
    "transaction" to text(scope.transactionId),
    "transactionRevision" to text(scope.transactionRevision.toString()),
) else obj(
    "kind" to text(scope.kind.name),
    "origin" to text(scope.origin.name),
    "parent" to text(scope.parentDraftId),
    "request" to text(scope.selectionRequestId),
    "slot" to text(scope.slot?.name),
)

internal fun decodeDraftScope(json: String): DraftScope {
    val value = JSONObject(json)
    val scope = DraftScope(
        DraftKind.valueOf(value.getString("kind")),
        DraftOrigin.valueOf(value.getString("origin")),
        value.optional("parent"),
        value.optional("request"),
        value.optional("slot")?.let(DraftSlot::valueOf),
        value.optional("transaction"),
        value.optional("transactionRevision")?.toLong(),
    )
    check(captureDraftScope(scope) == json)
    return scope
}

internal fun captureDraft(context: LedgerContext, id: String, scope: DraftScope, content: DraftContent): String {
    requireLedgerId(id)
    val fields = when (content) {
        is DraftContent.Account -> {
            require(scope.kind == DraftKind.ACCOUNT)
            listOf(content.name, content.anchorDate, content.unknownReason).forEach { requireLedgerText(it, allowBlank = true) }
            obj("name" to text(content.name), "nature" to text(content.nature.name), "date" to text(content.anchorDate), "reason" to text(content.unknownReason))
        }

        is DraftContent.Transaction -> {
            require(scope.kind == DraftKind.TRANSACTION)
            listOf(content.amount, content.date, content.memo).forEach { requireLedgerText(it, allowBlank = true) }
            listOfNotNull(content.debitAccountId, content.creditAccountId, content.candidateAccountId, content.selectionRequestId).forEach(::requireLedgerId)
            require(content.selectionRequestId == null || content.selectionSlot != null)
            obj(
                "kind" to text(content.kind.name), "amount" to text(content.amount), "date" to text(content.date),
                "debit" to text(content.debitAccountId), "credit" to text(content.creditAccountId), "memo" to text(content.memo),
                "slot" to text(content.selectionSlot?.name), "request" to text(content.selectionRequestId), "candidate" to text(content.candidateAccountId),
            )
        }
    }
    return obj(
        "version" to "1",
        "ledger" to text(context.ledgerId),
        "generation" to text(context.storageGeneration),
        "id" to text(id),
        "scope" to captureDraftScope(scope),
        "content" to fields,
    )
}

internal fun FormDraftEntity.editable(context: LedgerContext): FormDraft {
    if (storageGeneration != context.storageGeneration || ledgerId != context.ledgerId) throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
    check(status == "EDITING" || status == "SUBMITTING" || status == "REJECTED")
    val raw = checkNotNull(payloadJson)
    check(requestHash(raw) == payloadSha256)
    val json = JSONObject(raw)
    check(
        json.getInt("version") == 1 && json.getString("ledger") == context.ledgerId &&
            json.getString("generation") == context.storageGeneration && json.getString("id") == id,
    )
    val scope = decodeDraftScope(scopeJson)
    val value = json.getJSONObject("content")
    val content = when (scope.kind) {
        DraftKind.ACCOUNT -> DraftContent.Account(value.getString("name"), AccountNature.valueOf(value.getString("nature")), value.getString("date"), value.getString("reason"))

        DraftKind.TRANSACTION -> DraftContent.Transaction(
            TransactionKind.valueOf(value.getString("kind")), value.getString("amount"), value.getString("date"),
            value.optional("debit"), value.optional("credit"), value.getString("memo"), value.optional("slot")?.let(DraftSlot::valueOf), value.optional("request"), value.optional("candidate"),
        )
    }
    check(captureDraft(context, id, scope, content) == raw)
    return FormDraft(context, DraftReference(id, revision), scope, content)
}

internal fun FormDraft.matches(command: WriteCommand): Boolean {
    if (command is WriteCommand.Update) {
        if (scope.transactionId != command.value.value.id || scope.transactionRevision != command.value.expectedRevision) return false
        return copy(scope = scope.copy(transactionId = null, transactionRevision = null)).matches(WriteCommand.Transaction(command.value.value))
    }
    if (command is WriteCommand.Transaction && scope.transactionId != null) return false
    val raw = content
    return when {
        raw is DraftContent.Account && command is WriteCommand.Account -> {
            command.value == CreateAccount(command.value.id, command.value.anchorId, raw.name, raw.nature, LocalDate.parse(raw.anchorDate).also { require(it.toString() == raw.anchorDate) }, raw.unknownReason)
        }

        raw is DraftContent.Transaction && command is WriteCommand.Transaction -> {
            require(raw.amount.matches(Regex("[0-9]{1,12}")))
            command.value == CreateTransaction(
                command.value.id,
                raw.kind,
                LocalDate.parse(raw.date).also { require(it.toString() == raw.date) },
                raw.amount.toLong(),
                raw.debitAccountId.takeIf { raw.kind != TransactionKind.INCOME },
                raw.creditAccountId.takeIf { raw.kind != TransactionKind.EXPENSE },
                raw.memo,
            )
        }

        else -> false
    }
}
