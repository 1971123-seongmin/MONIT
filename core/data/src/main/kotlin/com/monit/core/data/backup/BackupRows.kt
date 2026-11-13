package com.monit.core.data.backup

import com.monit.core.data.*
import java.time.Instant
import org.json.JSONObject

internal fun JSONObject.nullLong(key: String): Long? = if (isNull(key)) null else exactLong(key)
internal fun JSONObject.instant(key: String): Instant = text(key).also { require(it.endsWith('Z')) }.let(Instant::parse)
internal fun JSONObject.account() = AccountEntity(text("ledgerId"), text("id"), text("name"), text("nature"), text("status"), exactLong("revision"))
internal fun JSONObject.anchor() = AnchorEntity(text("ledgerId"), text("id"), text("accountId"), text("date"), nullLong("amount"), optional("confirmedAt"), optional("unknownReason"), exactLong("revision"))
internal fun JSONObject.transaction() = TransactionEntity(text("ledgerId"), text("id"), text("kind"), text("date"), exactLong("amount"), optional("debitAccountId"), optional("creditAccountId"), optional("debitNature"), optional("creditNature"), text("memo"), exactLong("revision"), exactLong("financialRevision"), optional("deletedAt"))
internal fun JSONObject.transactionHistory() = TransactionHistoryEntity(transaction(), text("operationId"), text("changedAt"), text("changeKind"))
internal fun JSONObject.rule() = PaymentRuleEntity(text("ledgerId"), text("id"), exactLong("revision"), text("startMonth"), optional("endMonth"))
internal fun JSONObject.version() = PaymentRuleVersionEntity(text("ledgerId"), text("ruleId"), exactLong("revision"), text("effectiveMonth"), text("termsJson"))
internal fun JSONObject.item() = PaymentItemEntity(text("ledgerId"), text("id"), text("ruleId"), text("month"), exactLong("revision"), exactLong("ruleVersion"), text("termsJson"), text("dueDate"), text("status"), getBoolean("active"), optional("transactionId"), nullLong("confirmedFinancialRevision"), optional("confirmedJson"))
internal fun JSONObject.event() = PaymentEventEntity(text("ledgerId"), text("operationId"), text("itemId"), text("kind"), optional("transactionId"), if (isNull("full")) null else getBoolean("full"), text("reason"), optional("beforeJson"), text("afterJson"), text("changedAt"))
internal fun JSONObject.anchorHistory() = AnchorHistoryEntity(text("ledgerId"), text("anchorId"), exactLong("revision"), text("accountId"), text("operationId"), text("date"), nullLong("beforeAmount"), optional("beforeConfirmedAt"), optional("beforeUnknownReason"), exactLong("beforeRevision"), exactLong("amount"), text("reason"), text("confirmedAt"))
internal fun JSONObject.operation() = OperationEntity(text("ledgerId"), text("operationId"), text("storageGeneration"), text("kind"), text("requestJson"), text("requestSha256"), text("committedAt"), exactLong("ledgerRevision"), text("resultJson"))
internal fun JSONObject.submission() = SubmissionEntity(text("ledgerId"), text("operationId"), text("requestJson"), text("requestSha256"))
internal fun JSONObject.reference() = SubmissionReferenceEntity(text("ledgerId"), text("operationId"), text("storageGeneration"))
internal fun JSONObject.transactionRejection() = SubmissionRejectionEntity(text("ledgerId"), text("operationId"), text("storageGeneration"), text("requestJson"), text("requestSha256"), text("transactionId"), exactLong("expectedRevision"), exactLong("actualRevision"), optional("actualDeletedAt"), text("rejectedAt"))
internal fun JSONObject.paymentRejection() = PaymentRejectionEntity(text("ledgerId"), text("operationId"), text("storageGeneration"), text("requestJson"), text("requestSha256"), text("collection"), text("targetId"), exactLong("expectedRevision"), exactLong("actualRevision"), text("rejectedAt"))
internal fun JSONObject.intent() = PaymentIntentEntity(text("ledgerId"), text("operationId"), text("storageGeneration"), text("requestJson"), text("requestSha256"), text("status"), optional("draftId"), nullLong("draftRevision"), optional("draftScopeJson"))
internal fun JSONObject.anchorRejection() = AnchorRejectionEntity(text("ledgerId"), text("operationId"), text("storageGeneration"), text("requestJson"), text("requestSha256"), text("anchorId"), exactLong("expectedRevision"), exactLong("actualRevision"), text("rejectedAt"))
