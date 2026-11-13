package com.monit.core.data

import com.monit.core.model.*
import java.time.LocalDate
import org.json.JSONObject

internal fun paymentObject(vararg fields: Pair<String, String>): String = fields.joinToString(",", "{", "}") { JSONObject.quote(it.first) + ":" + it.second }
internal fun paymentText(value: Any?): String = value?.let { JSONObject.quote(it.toString()) } ?: "null"
private fun JSONObject.optional(name: String): String? = if (isNull(name)) null else getString(name)

internal fun paymentTermsJson(value: PaymentTerms): String = paymentObject(
    "name" to paymentText(value.name),
    "purpose" to paymentText(value.purpose.name),
    "amount" to paymentText(value.amount),
    "day" to paymentText(value.day),
    "debit" to paymentText(value.debitAccountId),
    "credit" to paymentText(value.creditAccountId),
)
internal fun decodePaymentTerms(raw: String): PaymentTerms {
    val json = JSONObject(raw)
    return PaymentTerms(json.getString("name"), PaymentPurpose.valueOf(json.getString("purpose")), json.getString("amount").toLong(), json.getString("day").toInt(), json.getString("debit"), json.optional("credit")).also { check(paymentTermsJson(it) == raw) }
}
internal fun paymentTransactionJson(value: CreateTransaction): String = paymentObject(
    "id" to paymentText(value.id),
    "kind" to paymentText(value.kind.name),
    "date" to paymentText(value.date),
    "amount" to paymentText(value.amount),
    "debit" to paymentText(value.debitAccountId),
    "credit" to paymentText(value.creditAccountId),
    "memo" to paymentText(value.memo),
)
internal fun decodePaymentTransaction(raw: String): CreateTransaction {
    val json = JSONObject(raw)
    return CreateTransaction(json.getString("id"), TransactionKind.valueOf(json.getString("kind")), LocalDate.parse(json.getString("date")), json.getString("amount").toLong(), json.optional("debit"), json.optional("credit"), json.getString("memo")).also { check(paymentTransactionJson(it) == raw) }
}
private fun itemJson(value: PaymentItemReference): String = paymentObject(
    "id" to paymentText(value.id),
    "rule" to paymentText(value.ruleId),
    "month" to paymentText(value.month),
    "revision" to paymentText(value.expectedRevision),
    "ruleRevision" to paymentText(value.expectedRuleRevision),
)
private fun item(json: JSONObject) = PaymentItemReference(json.getString("id"), json.getString("rule"), json.getString("month"), json.getString("revision").toLong(), json.getString("ruleRevision").toLong())

internal val paymentKinds = setOf("PAYMENT_REGISTER_RULE", "PAYMENT_CHANGE_RULE", "PAYMENT_END_RULE", "PAYMENT_CONFIRM", "PAYMENT_CHANGE_ITEM")

internal val PaymentCommand.kind: String get() = when (this) {
    is PaymentCommand.RegisterRule -> "PAYMENT_REGISTER_RULE"
    is PaymentCommand.ChangeRule -> "PAYMENT_CHANGE_RULE"
    is PaymentCommand.EndRule -> "PAYMENT_END_RULE"
    is PaymentCommand.Confirm -> "PAYMENT_CONFIRM"
    is PaymentCommand.ChangeItem -> "PAYMENT_CHANGE_ITEM"
}
internal val PaymentCommand.target: Pair<String, Long> get() = when (this) {
    is PaymentCommand.RegisterRule -> id to 0L
    is PaymentCommand.ChangeRule -> id to expectedRevision
    is PaymentCommand.EndRule -> id to expectedRevision
    is PaymentCommand.Confirm -> item.id to item.expectedRevision
    is PaymentCommand.ChangeItem -> item.id to item.expectedRevision
}
internal fun paymentRefs(command: PaymentCommand): List<EntityRef> = paymentResultEntities(command)
internal fun capturePayment(context: LedgerContext, command: PaymentCommand): String {
    val value = when (command) {
        is PaymentCommand.RegisterRule -> paymentObject("terms" to paymentTermsJson(command.terms), "start" to paymentText(command.startMonth), "end" to paymentText(command.endMonth))

        is PaymentCommand.ChangeRule -> paymentObject("terms" to paymentTermsJson(command.terms), "effective" to paymentText(command.effectiveMonth))

        is PaymentCommand.EndRule -> paymentObject("end" to paymentText(command.endMonth))

        is PaymentCommand.Confirm -> paymentObject(
            "item" to itemJson(command.item),
            "transaction" to paymentTransactionJson(command.transaction.value),
            "transactionRevision" to paymentText(command.transaction.revision),
            "financialRevision" to paymentText(command.transaction.financialRevision),
            "full" to command.full.toString(),
            "reason" to paymentText(command.reason),
        )

        is PaymentCommand.ChangeItem -> paymentObject("item" to itemJson(command.item), "action" to paymentText(command.action.name))
    }
    return paymentObject(
        "version" to "3",
        "ledgerId" to paymentText(context.ledgerId),
        "expectedStorageGeneration" to paymentText(context.storageGeneration),
        "kind" to paymentText(command.kind),
        "targetId" to paymentText(command.target.first),
        "expectedRevision" to paymentText(command.target.second),
        "value" to value,
    )
}
internal fun decodePayment(context: LedgerContext, raw: String): PaymentCommand {
    val json = JSONObject(raw)
    check(json.getInt("version") == 3 && json.getString("ledgerId") == context.ledgerId && json.getString("expectedStorageGeneration") == context.storageGeneration)
    val value = json.getJSONObject("value")
    val id = json.getString("targetId")
    val revision = json.getString("expectedRevision").toLong()
    val command = when (json.getString("kind")) {
        "PAYMENT_REGISTER_RULE" -> PaymentCommand.RegisterRule(id, decodeTermsObject(value.getJSONObject("terms")), value.getString("start"), value.optional("end"))
        "PAYMENT_CHANGE_RULE" -> PaymentCommand.ChangeRule(id, revision, value.getString("effective"), decodeTermsObject(value.getJSONObject("terms")))
        "PAYMENT_END_RULE" -> PaymentCommand.EndRule(id, revision, value.getString("end"))
        "PAYMENT_CONFIRM" -> PaymentCommand.Confirm(item(value.getJSONObject("item")), PaymentTransaction(decodeTransactionObject(value.getJSONObject("transaction")), value.optional("transactionRevision")?.toLong(), value.optional("financialRevision")?.toLong()), value.getBoolean("full"), value.getString("reason"))
        "PAYMENT_CHANGE_ITEM" -> PaymentCommand.ChangeItem(item(value.getJSONObject("item")), PaymentAction.valueOf(value.getString("action")))
        else -> error("Unsupported payment command")
    }
    check(capturePayment(context, command) == raw) { "Payment command is not canonical" }
    return command
}
private fun decodeTermsObject(json: JSONObject) = PaymentTerms(json.getString("name"), PaymentPurpose.valueOf(json.getString("purpose")), json.getString("amount").toLong(), json.getString("day").toInt(), json.getString("debit"), json.optional("credit"))
private fun decodeTransactionObject(json: JSONObject) = CreateTransaction(json.getString("id"), TransactionKind.valueOf(json.getString("kind")), LocalDate.parse(json.getString("date")), json.getString("amount").toLong(), json.optional("debit"), json.optional("credit"), json.getString("memo"))

internal fun paymentItemJson(value: PaymentItemEntity): String = paymentObject(
    "id" to paymentText(value.id), "rule" to paymentText(value.ruleId), "month" to paymentText(value.month),
    "revision" to paymentText(value.revision), "ruleVersion" to paymentText(value.ruleVersion), "terms" to value.termsJson,
    "due" to paymentText(value.dueDate), "status" to paymentText(value.status), "active" to value.active.toString(),
    "transaction" to paymentText(value.transactionId), "financialRevision" to paymentText(value.confirmedFinancialRevision), "confirmed" to (value.confirmedJson ?: "null"),
)
