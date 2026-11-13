package com.monit.core.model

import java.time.LocalDate

/** 보존한 원문과 캡처할 명령 사이의 결정적 검증. UI 표시 모델을 저장 계약에 넣지 않는다. */
fun paymentCommandFromDraft(scope: DraftScope, fields: Map<String, String>): PaymentCommand {
    fun text(key: String) = requireNotNull(fields[key]) { "Missing payment field: $key" }
    fun optional(key: String) = fields[key]?.takeUnless { it.isEmpty() }
    fun amount(key: String): Long = text(key).also { require(it.matches(Regex("[0-9]{1,12}"))) }.toLong()
    val id = requireNotNull(scope.paymentId)
    val revision = requireNotNull(scope.paymentRevision)
    fun terms() = PaymentTerms(text("name"), PaymentPurpose.valueOf(text("purpose")), amount("amount"), text("day").also { require(it.matches(Regex("[0-9]{1,2}"))) }.toInt(), text("debit"), optional("credit"))
    return when (scope.kind) {
        DraftKind.PAYMENT_RULE -> when (text("mode")) {
            "REGISTER" -> {
                require(revision == 0L)
                PaymentCommand.RegisterRule(id, terms(), text("start"), optional("end"))
            }

            "CHANGE" -> PaymentCommand.ChangeRule(id, revision, text("effective"), terms())

            "END" -> PaymentCommand.EndRule(id, revision, text("end"))

            else -> error("Unsupported rule draft")
        }

        DraftKind.PAYMENT_CONFIRM -> {
            val item = PaymentItemReference(id, requireNotNull(scope.paymentRuleId), requireNotNull(scope.paymentMonth), revision, requireNotNull(scope.paymentRuleRevision))
            when (text("mode")) {
                "CONFIRM" -> {
                    val date = LocalDate.parse(text("date")).also { require(it.toString() == text("date")) }
                    val transaction = CreateTransaction(text("transactionId"), TransactionKind.valueOf(text("transactionKind")), date, amount("amount"), optional("debit"), optional("credit"), text("memo"))
                    require(text("full") == "true" || text("full") == "false")
                    PaymentCommand.Confirm(item, PaymentTransaction(transaction, optional("transactionRevision")?.toLong(), optional("financialRevision")?.toLong()), text("full") == "true", text("reason"))
                }

                "UNLINK", "SKIP", "UNSKIP" -> PaymentCommand.ChangeItem(item, PaymentAction.valueOf(text("mode")))

                else -> error("Unsupported payment draft")
            }
        }

        else -> error("Not a payment draft")
    }
}

/** 복구 화면에서 원래 요청을 그대로 보여 주기 위한 투영. 새 ID/revision을 만들지 않는다. */
fun paymentDraftForCommand(command: PaymentCommand): Pair<DraftScope, DraftContent.Payment> {
    fun ruleScope(id: String, revision: Long) = DraftScope(DraftKind.PAYMENT_RULE, DraftOrigin.PAYMENTS, paymentId = id, paymentRevision = revision)
    fun itemScope(item: PaymentItemReference) = DraftScope(DraftKind.PAYMENT_CONFIRM, DraftOrigin.PAYMENTS, paymentId = item.id, paymentRevision = item.expectedRevision, paymentRuleId = item.ruleId, paymentRuleRevision = item.expectedRuleRevision, paymentMonth = item.month)
    fun terms(value: PaymentTerms) = mapOf("name" to value.name, "purpose" to value.purpose.name, "amount" to value.amount.toString(), "day" to value.day.toString(), "debit" to value.debitAccountId, "credit" to (value.creditAccountId ?: ""))
    return when (command) {
        is PaymentCommand.RegisterRule -> ruleScope(command.id, 0) to DraftContent.Payment(terms(command.terms) + mapOf("mode" to "REGISTER", "start" to command.startMonth, "end" to (command.endMonth ?: "")))

        is PaymentCommand.ChangeRule -> ruleScope(command.id, command.expectedRevision) to DraftContent.Payment(terms(command.terms) + mapOf("mode" to "CHANGE", "effective" to command.effectiveMonth))

        is PaymentCommand.EndRule -> ruleScope(command.id, command.expectedRevision) to DraftContent.Payment(mapOf("mode" to "END", "end" to command.endMonth))

        is PaymentCommand.Confirm -> itemScope(command.item) to DraftContent.Payment(
            mapOf(
                "mode" to "CONFIRM", "transactionId" to command.transaction.value.id, "transactionKind" to command.transaction.value.kind.name,
                "date" to command.transaction.value.date.toString(), "amount" to command.transaction.value.amount.toString(),
                "debit" to (command.transaction.value.debitAccountId ?: ""), "credit" to (command.transaction.value.creditAccountId ?: ""),
                "memo" to command.transaction.value.memo, "transactionRevision" to (command.transaction.revision?.toString() ?: ""),
                "financialRevision" to (command.transaction.financialRevision?.toString() ?: ""), "full" to command.full.toString(), "reason" to command.reason,
            ),
        )

        is PaymentCommand.ChangeItem -> itemScope(command.item) to DraftContent.Payment(mapOf("mode" to command.action.name))
    }
}
