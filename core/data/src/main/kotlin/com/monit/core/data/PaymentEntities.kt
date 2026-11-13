package com.monit.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "payment_rules", primaryKeys = ["ledgerId", "id"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class PaymentRuleEntity(val ledgerId: String, val id: String, val revision: Long, val startMonth: String, val endMonth: String?)

@Entity(tableName = "payment_rule_versions", primaryKeys = ["ledgerId", "ruleId", "revision"], foreignKeys = [ForeignKey(entity = PaymentRuleEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "ruleId"])])
internal data class PaymentRuleVersionEntity(val ledgerId: String, val ruleId: String, val revision: Long, val effectiveMonth: String, val termsJson: String)

@Entity(
    tableName = "payment_items",
    primaryKeys = ["ledgerId", "id"],
    indices = [Index(value = ["ledgerId", "ruleId", "month"], unique = true), Index(value = ["ledgerId", "transactionId"], unique = true)],
    foreignKeys = [
        ForeignKey(entity = PaymentRuleEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "ruleId"]),
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "transactionId"]),
    ],
)
internal data class PaymentItemEntity(
    val ledgerId: String,
    val id: String,
    val ruleId: String,
    val month: String,
    val revision: Long,
    val ruleVersion: Long,
    val termsJson: String,
    val dueDate: String,
    val status: String,
    val active: Boolean,
    val transactionId: String?,
    val confirmedFinancialRevision: Long?,
    val confirmedJson: String?,
)

@Entity(
    tableName = "payment_events",
    primaryKeys = ["ledgerId", "operationId", "itemId"],
    indices = [Index(value = ["ledgerId", "transactionId"]), Index(value = ["ledgerId", "itemId"])],
    foreignKeys = [ForeignKey(entity = PaymentItemEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "itemId"])],
)
internal data class PaymentEventEntity(
    val ledgerId: String,
    val operationId: String,
    val itemId: String,
    val kind: String,
    val transactionId: String?,
    val full: Boolean?,
    val reason: String,
    val beforeJson: String?,
    val afterJson: String,
    val changedAt: String,
)

@Entity(tableName = "payment_rejections", primaryKeys = ["ledgerId", "operationId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class PaymentRejectionEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
    val requestJson: String,
    val requestSha256: String,
    val collection: String,
    val targetId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val rejectedAt: String,
)

/** 금융 트랜잭션 밖에서 먼저 commit하는 원래 요청. 해제 후에도 원문과 ID fence를 유지한다. */
@Entity(tableName = "payment_intents", primaryKeys = ["ledgerId", "operationId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class PaymentIntentEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
    val requestJson: String,
    val requestSha256: String,
    val status: String,
    val draftId: String?,
    val draftRevision: Long?,
    val draftScopeJson: String?,
)
