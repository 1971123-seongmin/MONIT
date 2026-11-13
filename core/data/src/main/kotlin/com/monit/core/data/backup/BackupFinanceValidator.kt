package com.monit.core.data.backup

import com.monit.core.data.*
import com.monit.core.model.*
import java.time.*
import java.util.UUID
import org.json.JSONObject

internal class BackupFinanceValidator(private val data: ValidatedBackup, private val today: LocalDate) {
    val accounts = data.rows("accounts").map { it.account() }.associateBy { it.id }
    val anchors = data.rows("anchors").map { it.anchor() }.associateBy { it.id }
    val transactions = data.rows("transactions").map { it.transaction() }.associateBy { it.id }
    val histories = data.rows("transaction_history").map { it.transactionHistory() }.groupBy { it.before.id }
    val rules = data.rows("payment_rules").map { it.rule() }.associateBy { it.id }
    val versions = data.rows("payment_rule_versions").map { it.version() }.associateBy { it.ruleId to it.revision }
    val items = data.rows("payment_items").map { it.item() }.associateBy { it.id }
    val events = data.rows("payment_events").map { it.event() }.groupBy { it.itemId }
    val anchorHistories = data.rows("anchor_history").map { it.anchorHistory() }.groupBy { it.anchorId }
    val itemSnapshots = HashMap<String, List<PaymentItemEntity>>()
    val historyByOperation = histories.values.flatten().associateBy { it.operationId }
    val anchorHistoryByOperation = anchorHistories.values.flatten().associateBy { it.operationId }
    val eventByItemOperation = events.values.flatten().associateBy { it.itemId to it.operationId }
    private val snapshots = transactions.mapValues { (id, current) -> histories[id].orEmpty().map { it.before } + current }
    private val snapshotsByRevision = snapshots.values.flatten().associateBy { it.id to it.revision }
    private val financialSnapshots = snapshots.values.flatten().groupBy { it.id to it.financialRevision }.mapValues { (_, values) -> values.map { it.transaction().copy(memo = "") }.toSet() }
    private val allFinance = snapshots.mapValues { (_, values) -> values.map { it.transaction().copy(memo = "") }.toSet() }
    private val anchorDates = anchors.values.associate { it.accountId to LocalDate.parse(it.date) }

    fun validate() {
        require(accounts.size == anchors.size && anchors.values.map { it.accountId }.distinct().size == anchors.size)
        for (account in accounts.values) {
            require(account.revision > 0)
            requireLedgerText(account.name)
            AccountNature.valueOf(account.nature)
            AccountStatus.valueOf(account.status)
        }
        for (anchor in anchors.values) {
            val model = anchor.model()
            require(model.date < today)
            model.unknownReason?.let(::requireLedgerText)
            val history = anchorHistories[anchor.id].orEmpty().sortedBy { it.revision }
            require(history.size.toLong() == anchor.revision - 1)
            for ((index, row) in history.withIndex()) {
                val change = row.model()
                require(change.revision == index + 2L && change.accountId == anchor.accountId && change.before.date == model.date)
                val next = history.getOrNull(index + 1)?.model()?.before ?: model
                require(next.revision == change.revision && next.amount == change.amount && next.confirmedAt == change.confirmedAt && next.unknownReason == null)
            }
        }
        for (transaction in transactions.values) {
            validateTransaction(transaction)
            val history = histories[transaction.id].orEmpty().sortedBy { it.before.revision }
            require(history.size.toLong() == transaction.revision - 1)
            for ((index, row) in history.withIndex()) {
                validateTransaction(row.before)
                require(row.before.revision == index + 1L && row.before.deletedAt == null)
                timestamp(row.changedAt)
                val next = history.getOrNull(index + 1)?.before ?: transaction
                require(next.revision == row.before.revision + 1)
                val financial = !sameFinance(row.before.transaction(), next.transaction()) || next.deletedAt != row.before.deletedAt
                require(next.financialRevision == row.before.financialRevision + if (financial) 1 else 0)
                when (row.changeKind) {
                    "DELETE" -> require(next.deletedAt == row.changedAt && row.before.copy(revision = next.revision, financialRevision = next.financialRevision, deletedAt = next.deletedAt) == next)

                    "UPDATE" -> {
                        require(next.deletedAt == null)
                        boundary(row.before, next)
                    }

                    else -> error("Unsupported transaction history")
                }
            }
        }
        validatePayments()
        validatePreferences()
        val active = transactions.values.filter { it.deletedAt == null }.map { it.model() }
        val byAccount = HashMap<String, MutableList<LedgerTransaction>>()
        for (value in active) for (id in listOfNotNull(value.debitAccountId, value.creditAccountId)) byAccount.getOrPut(id) { ArrayList() }.add(value)
        val balances = anchors.values.associate { anchor ->
            val values = byAccount[anchor.accountId].orEmpty()
            requireBalanceHistory(anchor.model(), anchor.accountId, values, today)
            anchor.accountId to accountBalance(anchor.model(), anchor.accountId, values, today)
        }
        sumSignedAmounts(accounts.values.filter { it.status == "ACTIVE" }.mapNotNull { balances[it.id] })
        sumSignedAmounts(accounts.values.filter { it.status == "ACTIVE" && it.nature != "GENERAL" }.mapNotNull { balances[it.id] })
        for ((month, values) in active.groupBy { YearMonth.from(it.date) }) {
            sumSignedAmounts(values.filter { it.kind == TransactionKind.INCOME }.map { it.amount })
            sumSignedAmounts(values.filter { it.kind == TransactionKind.EXPENSE }.map { it.amount })
            assetFlows(values, month)
        }
    }

    private fun validateTransaction(value: TransactionEntity) {
        require(value.revision > 0 && value.financialRevision in 1..value.revision)
        val tx = value.transaction()
        transactionDateOf(tx.date.year, tx.date.monthValue, tx.date.dayOfMonth, today)
        value.deletedAt?.let(::timestamp)
        for ((id, nature) in listOf(value.debitAccountId to value.debitNature, value.creditAccountId to value.creditNature)) {
            if (id == null) require(nature == null) else {
                require(id in accounts)
                AccountNature.valueOf(checkNotNull(nature))
            }
        }
    }

    private fun boundary(before: TransactionEntity, after: TransactionEntity) {
        val old = setOfNotNull(before.debitAccountId, before.creditAccountId)
        val next = setOfNotNull(after.debitAccountId, after.creditAccountId)
        for (id in old + next) require((id in old && LocalDate.parse(before.date) > anchorDates.getValue(id)) == (id in next && LocalDate.parse(after.date) > anchorDates.getValue(id)))
    }

    private fun validatePayments() {
        for (rule in rules.values) {
            require(rule.revision > 0)
            val start = paymentMonth(rule.startMonth)
            rule.endMonth?.let { require(paymentMonth(it) >= start) }
            val first = checkNotNull(versions[rule.id to 1])
            require(first.effectiveMonth == rule.startMonth)
        }
        for (version in versions.values) {
            val rule = rules.getValue(version.ruleId)
            require(version.revision in 1..rule.revision && paymentMonth(version.effectiveMonth) >= paymentMonth(rule.startMonth))
            validateTerms(decodePaymentTerms(version.termsJson))
        }
        require(items.values.map { it.ruleId to it.month }.distinct().size == items.size)
        val reserved = items.values.mapNotNull { it.transactionId }
        require(reserved.distinct().size == reserved.size)
        val partial = events.values.flatten().filter { it.kind == "PARTIAL" }.map { checkNotNull(it.transactionId) }.toSet()
        for (item in items.values) {
            validateItem(item)
            if (item.status == "COMPLETED") require(item.transactionId !in partial)
            val history = events[item.id].orEmpty().sortedBy { decodeItem(it.afterJson).revision }
            require(history.size.toLong() == item.revision)
            val snapshots = ArrayList<PaymentItemEntity>()
            for ((index, event) in history.withIndex()) {
                timestamp(event.changedAt)
                requireLedgerText(event.reason, allowBlank = true)
                val after = decodeItem(event.afterJson)
                validateItem(after, current = false)
                require(after.id == item.id && after.revision == index + 1L)
                if (index == 0) require(event.beforeJson == null) else require(event.beforeJson == paymentItemJson(snapshots.last()))
                event.transactionId?.let { require(it in transactions) }
                when (event.kind) {
                    "CONFIRMED" -> require(event.full == true && event.transactionId == after.transactionId && after.status == "COMPLETED")
                    "PARTIAL" -> require(event.full == false && event.transactionId != null && after.status == "PENDING" && event.reason.isNotBlank())
                    "UNLINK", "SKIP", "UNSKIP", "FINANCIAL_CHANGED", "TRANSACTION_DELETED", "RULE_CHANGED", "RULE_ENDED" -> require(event.full == null)
                    else -> error("Unsupported payment event")
                }
                snapshots.add(after)
            }
            require(snapshots.lastOrNull() == item)
            itemSnapshots[item.id] = snapshots
        }
    }

    private fun validateTerms(value: PaymentTerms) {
        require(value.debitAccountId in accounts)
        value.creditAccountId?.let { require(it in accounts) }
        value.creditAccountId?.let { credit ->
            require(accounts.getValue(value.debitAccountId).nature == "GENERAL")
            require(accounts.getValue(credit).nature == if (value.purpose == PaymentPurpose.SAVINGS) "SAVINGS" else "INVESTMENT")
        }
    }
    private fun validateItem(value: PaymentItemEntity, current: Boolean = true) {
        require(value.revision > 0)
        val rule = rules.getValue(value.ruleId)
        val version = checkNotNull(versions[value.ruleId to value.ruleVersion])
        val month = paymentMonth(value.month)
        require(value.month >= rule.startMonth)
        require(value.id == UUID.nameUUIDFromBytes("${data.context.ledgerId}:${value.ruleId}:${value.month}".toByteArray(Charsets.UTF_8)).toString())
        val terms = decodePaymentTerms(value.termsJson)
        validateTerms(terms)
        require(value.termsJson == version.termsJson)
        require(value.dueDate == month.atDay(minOf(terms.day, month.lengthOfMonth())).toString())
        PaymentStatus.valueOf(value.status)
        if (!value.active) require(value.status == "PENDING")
        require((value.confirmedJson == null) == (value.confirmedFinancialRevision == null))
        val confirmed = value.confirmedJson?.let(::decodePaymentTransaction)
        if (value.status == "COMPLETED" || value.status == "RECONFIRM") require(confirmed != null)
        if (value.transactionId != null) require(value.status == "COMPLETED" || value.status == "RECONFIRM")
        if (confirmed != null) {
            val tx = checkNotNull(transactions[confirmed.id])
            require(confirmed.copy(memo = "") in financialSnapshots[confirmed.id to value.confirmedFinancialRevision].orEmpty())
            require(confirmed.debitAccountId == terms.debitAccountId && confirmed.creditAccountId == terms.creditAccountId)
            require(confirmed.kind == if (terms.creditAccountId == null) TransactionKind.EXPENSE else TransactionKind.TRANSFER)
            if (value.transactionId != null) require(value.transactionId == confirmed.id)
            if (value.status == "COMPLETED") {
                require(value.transactionId == confirmed.id)
                if (current) require(tx.deletedAt == null && tx.financialRevision == value.confirmedFinancialRevision)
            }
        }
    }

    fun decodeItem(raw: String): PaymentItemEntity {
        val json = JSONObject(raw)
        val value = PaymentItemEntity(data.context.ledgerId, json.text("id"), json.text("rule"), json.text("month"), json.exactLong("revision"), json.exactLong("ruleVersion"), json.getJSONObject("terms").toString(), json.text("due"), json.text("status"), json.getBoolean("active"), json.optional("transaction"), json.nullLong("financialRevision"), if (json.isNull("confirmed")) null else json.getJSONObject("confirmed").toString())
        require(paymentItemJson(value) == raw)
        return value
    }
    fun transactionSnapshots(id: String): List<TransactionEntity> = snapshots[id].orEmpty()
    fun transactionSnapshot(id: String, revision: Long): TransactionEntity = snapshotsByRevision.getValue(id to revision)
    fun containsFinance(value: CreateTransaction): Boolean = value.copy(memo = "") in allFinance[value.id].orEmpty()
    fun targetPresent(collection: String, id: String): Boolean = revision(collection, id) != null
    fun revision(collection: String, id: String): Long? = when (collection) {
        "accounts" -> accounts[id]?.revision
        "anchors" -> anchors[id]?.revision
        "transactions" -> transactions[id]?.revision
        "transaction_financial" -> transactions[id]?.financialRevision
        "payment_rules" -> rules[id]?.revision
        "payment_items" -> items[id]?.revision
        else -> null
    }

    private fun validatePreferences() {
        val prefs = data.root.getJSONObject("reminderPreferences")
        val global = prefs.getJSONObject("global")
        if (global.getBoolean("present")) require(!global.isNull("enabled") && global.exactLong("revision") >= 0) else require(global.isNull("enabled") && global.isNull("revision"))
        val preferences = prefs.list("rules")
        require(preferences.map { it.text("ruleId") }.distinct().size == preferences.size)
        for (value in preferences) require(value.text("ruleId") in rules && value.exactLong("revision") > 0)
        if (prefs.text("coverage") == "NOT_RECORDED_BY_V1") require(!global.getBoolean("present") && preferences.isEmpty())
    }
    private fun timestamp(raw: String) {
        require(raw.endsWith('Z'))
        Instant.parse(raw)
    }
    private fun AnchorEntity.model() = BalanceAnchor(id, LocalDate.parse(date), amount, confirmedAt?.let(Instant::parse), unknownReason, revision)
    private fun TransactionEntity.model() = LedgerTransaction(id, TransactionKind.valueOf(kind), LocalDate.parse(date), amount, debitAccountId, creditAccountId, debitNature?.let(AccountNature::valueOf), creditNature?.let(AccountNature::valueOf), memo, revision, financialRevision)
    private fun sameFinance(a: CreateTransaction, b: CreateTransaction) = a.copy(memo = "") == b.copy(memo = "")
}
