package com.monit.core.data

import com.monit.core.model.*
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

internal data class PaymentRevisionConflict(val collection: String, val id: String, val expected: Long, val actual: Long)

/** 호출자의 단일 writer 트랜잭션 안에서만 변경한다. 예정 조회 자체는 쓰지 않는다. */
internal class PaymentStore(private val database: LedgerDatabase, private val clock: Clock) {
    private val dao get() = database.paymentDao()
    private val ledger get() = database.ledgerDao()
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun itemId(context: LedgerContext, rule: String, month: String): String = UUID.nameUUIDFromBytes("${context.ledgerId}:$rule:$month".toByteArray(StandardCharsets.UTF_8)).toString()
    private suspend fun version(context: LedgerContext, rule: PaymentRuleEntity, month: String): PaymentRuleVersionEntity = dao.versions(context.ledgerId, rule.id).filter { it.effectiveMonth <= month }.maxByOrNull { it.revision } ?: error("No applicable rule version")

    suspend fun month(context: LedgerContext, month: String, ledgerRevision: Long): PaymentMonthSnapshot {
        paymentMonth(month)
        val rules = dao.rules(context.ledgerId)
        val stored = dao.month(context.ledgerId, month).associateBy { it.ruleId }
        val items = rules.mapNotNull { rule ->
            val row = stored[rule.id] ?: if (month >= rule.startMonth && (rule.endMonth == null || month <= rule.endMonth)) virtual(context, rule, month) else return@mapNotNull null
            row.model(rule.revision)
        }
        val models = rules.map { rule ->
            val latest = dao.versions(context.ledgerId, rule.id).maxByOrNull { it.revision } ?: error("Rule has no version")
            PaymentRule(rule.id, rule.revision, rule.startMonth, rule.endMonth, decodePaymentTerms(latest.termsJson))
        }
        return PaymentMonthSnapshot(context, ledgerRevision, month, models, items.sortedWith(compareBy({ it.dueDate }, { it.reference.id })), dao.reservations(context.ledgerId).associate { requireNotNull(it.transactionId) to it.id }, dao.partialTransactions(context.ledgerId).toSet())
    }
    private suspend fun virtual(context: LedgerContext, rule: PaymentRuleEntity, month: String): PaymentItemEntity {
        val version = version(context, rule, month)
        val terms = decodePaymentTerms(version.termsJson)
        val date = paymentMonth(month).atDay(minOf(terms.day, paymentMonth(month).lengthOfMonth()))
        return PaymentItemEntity(context.ledgerId, itemId(context, rule.id, month), rule.id, month, 0, version.revision, version.termsJson, date.toString(), "PENDING", true, null, null, null)
    }
    private fun PaymentItemEntity.model(ruleRevision: Long) = MonthlyPayment(
        PaymentItemReference(id, ruleId, month, revision, ruleRevision), ruleVersion, decodePaymentTerms(termsJson),
        LocalDate.parse(dueDate), PaymentStatus.valueOf(status), active, transactionId,
        confirmedFinancialRevision, confirmedJson?.let(::decodePaymentTransaction),
    )

    /** 금융 변경 전 확인한 revision 불일치만 값으로 반환한다. 입력·예약 실패는 포함하지 않는다. */
    suspend fun conflict(context: LedgerContext, command: PaymentCommand): PaymentRevisionConflict? {
        when (command) {
            is PaymentCommand.RegisterRule -> dao.rule(context.ledgerId, command.id)?.let { return PaymentRevisionConflict("payment_rules", it.id, 0, it.revision) }

            is PaymentCommand.ChangeRule -> {
                val row = dao.rule(context.ledgerId, command.id) ?: missing()
                if (row.revision != command.expectedRevision) return PaymentRevisionConflict("payment_rules", row.id, command.expectedRevision, row.revision)
            }

            is PaymentCommand.EndRule -> {
                val row = dao.rule(context.ledgerId, command.id) ?: missing()
                if (row.revision != command.expectedRevision) return PaymentRevisionConflict("payment_rules", row.id, command.expectedRevision, row.revision)
            }

            is PaymentCommand.Confirm -> {
                itemConflict(context, command.item)?.let { return it }
                val selection = command.transaction
                if (selection.financialRevision != null) {
                    val row = ledger.transaction(context.ledgerId, selection.value.id) ?: missing()
                    if (row.financialRevision != selection.financialRevision) return PaymentRevisionConflict("transaction_financial", row.id, requireNotNull(selection.financialRevision), row.financialRevision)
                    if (selection.revision != null && row.revision != selection.revision) return PaymentRevisionConflict("transactions", row.id, requireNotNull(selection.revision), row.revision)
                }
            }

            is PaymentCommand.ChangeItem -> return itemConflict(context, command.item)
        }
        return null
    }
    private suspend fun itemConflict(context: LedgerContext, ref: PaymentItemReference): PaymentRevisionConflict? {
        val row = dao.item(context.ledgerId, ref.id)
        if (row != null) {
            check(row.ruleId == ref.ruleId && row.month == ref.month && row.id == itemId(context, ref.ruleId, ref.month))
            if (row.revision != ref.expectedRevision) return PaymentRevisionConflict("payment_items", row.id, ref.expectedRevision, row.revision)
        } else {
            if (ref.expectedRevision != 0L) missing()
            val rule = dao.rule(context.ledgerId, ref.ruleId) ?: missing()
            if (rule.revision != ref.expectedRuleRevision) return PaymentRevisionConflict("payment_rules", rule.id, ref.expectedRuleRevision, rule.revision)
        }
        return null
    }
    private suspend fun editableItem(context: LedgerContext, ref: PaymentItemReference): PaymentItemEntity {
        require(ref.id == itemId(context, ref.ruleId, ref.month))
        val existing = dao.item(context.ledgerId, ref.id)
        val value = if (existing != null) existing else {
            val rule = dao.rule(context.ledgerId, ref.ruleId) ?: missing()
            require(ref.month >= rule.startMonth && (rule.endMonth == null || ref.month <= rule.endMonth))
            virtual(context, rule, ref.month)
        }
        check(value.revision == ref.expectedRevision && value.ruleId == ref.ruleId && value.month == ref.month)
        require(value.active) { "Payment schedule is inactive" }
        return value
    }
    suspend fun apply(context: LedgerContext, operation: String, command: PaymentCommand, insertTransaction: suspend (CreateTransaction) -> Unit): List<EntityRef> {
        check(conflict(context, command) == null)
        when (command) {
            is PaymentCommand.RegisterRule -> {
                validateTerms(context, command.terms)
                dao.insertRule(PaymentRuleEntity(context.ledgerId, command.id, 1, command.startMonth, command.endMonth))
                dao.insertVersion(PaymentRuleVersionEntity(context.ledgerId, command.id, 1, command.startMonth, paymentTermsJson(command.terms)))
            }

            is PaymentCommand.ChangeRule -> {
                require(paymentMonth(command.effectiveMonth) >= YearMonth.from(today()).plusMonths(1))
                validateTerms(context, command.terms)
                val rule = checkNotNull(dao.rule(context.ledgerId, command.id))
                require(command.effectiveMonth >= rule.startMonth)
                val updated = rule.copy(revision = Math.addExact(rule.revision, 1))
                check(dao.updateRule(updated) == 1)
                dao.insertVersion(PaymentRuleVersionEntity(context.ledgerId, rule.id, updated.revision, command.effectiveMonth, paymentTermsJson(command.terms)))
                for (old in dao.ruleItems(context.ledgerId, rule.id)) {
                    if (old.month >= command.effectiveMonth && old.month > YearMonth.from(today()).toString() && old.status == "PENDING" && old.active) {
                        val next = virtual(context, updated, old.month).copy(id = old.id, revision = Math.addExact(old.revision, 1))
                        saveItem(old, next, operation, "RULE_CHANGED", null, null, "미래 미완료 예정 변경")
                    }
                }
            }

            is PaymentCommand.EndRule -> {
                val rule = checkNotNull(dao.rule(context.ledgerId, command.id))
                require(paymentMonth(command.endMonth) >= YearMonth.from(today()) && command.endMonth >= rule.startMonth)
                require(rule.endMonth == null || command.endMonth <= rule.endMonth) { "Ended schedules are not automatically reactivated" }
                check(dao.updateRule(rule.copy(revision = Math.addExact(rule.revision, 1), endMonth = command.endMonth)) == 1)
                for (old in dao.ruleItems(context.ledgerId, rule.id)) {
                    if (old.month > command.endMonth && old.status == "PENDING" && old.active) saveItem(old, old.copy(revision = Math.addExact(old.revision, 1), active = false), operation, "RULE_ENDED", null, null, "일정 종료로 비활성")
                }
            }

            is PaymentCommand.Confirm -> {
                val old = editableItem(context, command.item)
                require(old.status == "PENDING" || old.status == "RECONFIRM") { "Unlink or unskip before confirming" }
                val selection = command.transaction
                val tx = if (selection.financialRevision == null) selection.value else {
                    val current = ledger.transaction(context.ledgerId, selection.value.id) ?: missing()
                    require(current.deletedAt == null)
                    val actual = current.transaction()
                    require(actual.copy(memo = selection.value.memo) == selection.value) { "Reviewed financial values changed" }
                    actual
                }
                val terms = decodePaymentTerms(old.termsJson)
                validateTransaction(context, terms, tx)
                if (tx.amount != terms.amount || !command.full) requireLedgerText(command.reason)
                val reserved = dao.linked(context.ledgerId, tx.id)
                require(reserved == null || reserved.id == old.id) { "Transaction is reserved by another payment" }
                if (command.full) {
                    require(!dao.hasPartial(context.ledgerId, tx.id)) { "A partial payment cannot be reused as a full payment" }
                    require(old.transactionId == null || old.transactionId == tx.id) { "Unlink the previous transaction first" }
                } else require(old.transactionId == null) { "Unlink the previous confirmation first" }
                if (selection.financialRevision == null) insertTransaction(tx)
                val financialRevision = checkNotNull(ledger.transaction(context.ledgerId, tx.id)).financialRevision
                val next = old.copy(
                    revision = Math.addExact(old.revision, 1),
                    status = if (command.full) "COMPLETED" else "PENDING",
                    transactionId = if (command.full) tx.id else null,
                    confirmedFinancialRevision = if (command.full) financialRevision else old.confirmedFinancialRevision,
                    confirmedJson = if (command.full) paymentTransactionJson(tx) else old.confirmedJson,
                )
                saveItem(old, next, operation, if (command.full) "CONFIRMED" else "PARTIAL", tx.id, command.full, command.reason)
            }

            is PaymentCommand.ChangeItem -> {
                val old = editableItem(context, command.item)
                val status = when (command.action) {
                    PaymentAction.UNLINK -> {
                        require(old.transactionId != null)
                        "PENDING"
                    }

                    PaymentAction.SKIP -> {
                        require(old.transactionId == null && old.status != "SKIPPED")
                        "SKIPPED"
                    }

                    PaymentAction.UNSKIP -> {
                        require(old.status == "SKIPPED")
                        "PENDING"
                    }
                }
                saveItem(old, old.copy(revision = Math.addExact(old.revision, 1), status = status, transactionId = null, confirmedFinancialRevision = null, confirmedJson = null), operation, command.action.name, old.transactionId, null, "사용자 명시 변경")
            }
        }
        return paymentRefs(command)
    }

    /** 기존 거래 writer 안에서만 실행한다. 메모는 금융 revision이 같아 완료 근거를 유지한다. */
    suspend fun transactionChanged(before: TransactionEntity, after: TransactionEntity, operation: String) {
        if (before.financialRevision == after.financialRevision) return
        val old = dao.linked(before.ledgerId, before.id) ?: return
        val next = old.copy(revision = Math.addExact(old.revision, 1), status = "RECONFIRM", transactionId = if (after.deletedAt == null) old.transactionId else null)
        saveItem(old, next, operation, if (after.deletedAt == null) "FINANCIAL_CHANGED" else "TRANSACTION_DELETED", before.id, null, "확인한 금융 근거 변경")
    }
    private suspend fun saveItem(old: PaymentItemEntity, next: PaymentItemEntity, operation: String, kind: String, transaction: String?, full: Boolean?, reason: String) {
        if (old.revision == 0L) dao.insertItem(next) else check(dao.updateItem(next) == 1)
        dao.insertEvent(PaymentEventEntity(next.ledgerId, operation, next.id, kind, transaction, full, reason, if (old.revision == 0L) null else paymentItemJson(old), paymentItemJson(next), clock.instant().toString()))
    }
    private suspend fun validateTerms(context: LedgerContext, terms: PaymentTerms) {
        val debit = ledger.account(context.ledgerId, terms.debitAccountId) ?: missing()
        require(debit.status == "ACTIVE")
        terms.creditAccountId?.let { id ->
            val credit = ledger.account(context.ledgerId, id) ?: missing()
            require(credit.status == "ACTIVE" && debit.nature == "GENERAL")
            require(credit.nature == if (terms.purpose == PaymentPurpose.SAVINGS) "SAVINGS" else "INVESTMENT")
        }
    }
    private suspend fun validateTransaction(context: LedgerContext, terms: PaymentTerms, tx: CreateTransaction) {
        transactionDateOf(tx.date.year, tx.date.monthValue, tx.date.dayOfMonth, today())
        val debit = ledger.account(context.ledgerId, requireNotNull(tx.debitAccountId)) ?: missing()
        require(debit.status == "ACTIVE")
        when (terms.purpose) {
            PaymentPurpose.GENERAL, PaymentPurpose.INSURANCE -> require(tx.kind == TransactionKind.EXPENSE && tx.creditAccountId == null)

            PaymentPurpose.SAVINGS, PaymentPurpose.INVESTMENT -> {
                require(tx.kind == TransactionKind.TRANSFER && tx.creditAccountId == terms.creditAccountId && debit.nature == "GENERAL")
                val credit = ledger.account(context.ledgerId, requireNotNull(tx.creditAccountId)) ?: missing()
                require(credit.status == "ACTIVE" && credit.nature == if (terms.purpose == PaymentPurpose.SAVINGS) "SAVINGS" else "INVESTMENT")
            }
        }
    }
    private fun missing(): Nothing = throw LedgerConflictException(LedgerConflict.MISSING_REFERENCE)
}

internal fun TransactionEntity.transaction() = CreateTransaction(id, TransactionKind.valueOf(kind), LocalDate.parse(date), amount, debitAccountId, creditAccountId, memo)
