package com.monit.core.data.backup

import com.monit.core.data.*
import com.monit.core.model.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 원래 요청을 검증할 뿐 submit/retry를 호출하지 않는다. UI 초안을 생성하거나 READY로 승격하지 않는다. */
internal class BackupEvidenceValidator(private val data: ValidatedBackup, private val finance: BackupFinanceValidator) {
    private val evidence = data.root.list("submissionsEvidence")
    private val successful = HashMap<String, WriteCommand>()
    private val operations = HashMap<String, OperationEntity>()

    suspend fun validate() {
        require(evidence.map { it.text("operationId") }.distinct().size == evidence.size)
        for (item in evidence) {
            currentCoroutineContext().ensureActive()
            validateItem(item)
        }
        validateCommittedCommands()
        for (history in finance.histories.values.flatten()) {
            val command = checkNotNull(successful[history.operationId])
            val next = finance.transactionSnapshot(history.before.id, history.before.revision + 1)
            when (command) {
                is WriteCommand.Update -> require(history.changeKind == "UPDATE" && command.value.expectedRevision == history.before.revision && command.value.value == next.transaction())
                is WriteCommand.Delete -> require(history.changeKind == "DELETE" && command.value.expectedRevision == history.before.revision && command.value.id == history.before.id)
                else -> error("History has no matching committed change")
            }
        }
        for (history in finance.anchorHistories.values.flatten()) {
            val command = checkNotNull(successful[history.operationId]) as? WriteCommand.Anchor ?: error("Anchor history has no confirmation")
            require(validAnchorCommit(command.value, operations.getValue(history.operationId), history, finance.anchors[history.anchorId]))
        }
        for (history in finance.events.values.flatten()) validateEvent(history)
    }

    private suspend fun validateItem(item: JSONObject) {
        val id = requireLedgerId(item.text("operationId"))
        val source = item.getJSONObject("sourceContext")
        val context = LedgerContext(source.text("ledgerId"), source.text("storageGeneration"))
        require(context.ledgerId == data.context.ledgerId)
        val rows = BackupLayout.evidence.associateWith { if (item.isNull(it)) null else item.getJSONObject(it) }
        require(rows.values.any { it != null })
        for (row in rows.values.filterNotNull()) {
            require(row.text("ledgerId") == context.ledgerId && row.text("operationId") == id)
            if (row.has("storageGeneration")) require(row.text("storageGeneration") == context.storageGeneration)
            for (field in row.keys().asSequence().filter { it.endsWith("At") && !row.isNull(it) }) {
                require(row.text(field).endsWith('Z'))
                Instant.parse(row.text(field))
            }
            if (row.has("requestJson")) {
                val raw = row.text("requestJson")
                BackupJson.parseText(raw)
                require(BackupJson.digest(raw) == row.text("requestSha256"))
                decodeWrite(context, raw)
            }
        }
        val operation = rows["operations"]?.operation()
        val pending = rows["submissions"]?.submission()
        val reference = rows["submission_references"]?.reference()
        val transactionRejection = rows["submission_rejections"]?.transactionRejection()
        val paymentRejection = rows["payment_rejections"]?.paymentRejection()
        val anchorRejection = rows["anchor_rejections"]?.anchorRejection()
        val intent = rows["payment_intents"]?.intent()
        require(listOfNotNull(transactionRejection, paymentRejection, anchorRejection).size <= 1)
        val rawRows = rows.values.filterNotNull().filter { it.has("requestJson") }
        require(rawRows.map { it.text("requestJson") to it.text("requestSha256") }.distinct().size <= 1)
        if (intent != null) {
            require(intent.status in setOf("READY", "CONSUMED", "RELEASED"))
            val command = decodePayment(context, intent.requestJson)
            if (intent.draftId == null) require(intent.draftRevision == null && intent.draftScopeJson == null) else {
                requireLedgerId(intent.draftId)
                require(checkNotNull(intent.draftRevision) > 0)
                BackupJson.parseText(checkNotNull(intent.draftScopeJson))
                val scope = decodeDraftScope(intent.draftScopeJson)
                require(scope == paymentDraftForCommand(command).first)
                require(item.text("draftAvailability") == "EXCLUDED_BY_POLICY")
            }
            when (intent.status) {
                "CONSUMED" -> require(operation != null || paymentRejection != null)
                "READY", "RELEASED" -> require(operation == null && paymentRejection == null && transactionRejection == null && anchorRejection == null && pending == null && reference == null)
            }
        }
        when {
            operation != null -> {
                require(transactionRejection == null && paymentRejection == null && anchorRejection == null)
                BackupJson.parseText("{\"refs\":${operation.resultJson}}")
                val result = submissionRecoveryItem(context, data.revision, reference ?: SubmissionReferenceEntity(context.ledgerId, id, context.storageGeneration), pending, operation)
                require(result is SubmissionRecoveryItem.Committed)
                for (ref in result.receipt.entities) require(checkNotNull(finance.revision(ref.collection, ref.id)) >= ref.revision)
                val command = decodeWrite(context, operation.requestJson)
                if (command is WriteCommand.Payment) require(intent?.status == "CONSUMED") else require(intent == null)
                successful[id] = command
                operations[id] = operation
            }

            transactionRejection != null -> {
                require(intent == null)
                validateTransactionRejection(context, transactionRejection, pending, operation, finance.targetPresent("transactions", transactionRejection.transactionId))
                require(checkNotNull(finance.revision("transactions", transactionRejection.transactionId)) >= transactionRejection.actualRevision)
            }

            paymentRejection != null -> {
                validatePaymentRejection(context, paymentRejection, pending, operation, transactionRejection, intent, finance.targetPresent(paymentRejection.collection, paymentRejection.targetId))
                require(checkNotNull(finance.revision(paymentRejection.collection, paymentRejection.targetId)) >= paymentRejection.actualRevision)
            }

            anchorRejection != null -> {
                require(intent == null)
                validateAnchorRejection(context, anchorRejection, pending, operation, true, finance.anchors[anchorRejection.anchorId])
            }

            pending != null -> {
                require(intent == null && pending.toRecoveryItem(context) is PendingRecoveryItem.TypedSubmission)
            }

            else -> require(intent != null && reference == null)
        }
        // 참조 부재는 부재 그대로다. 검증용 임시 참조를 결과/현재 실행 테이블에 저장하지 않는다.
        if (reference != null) require(operation != null || pending != null)
    }

    private fun validateCommittedCommands() {
        for ((operation, command) in successful) when (command) {
            is WriteCommand.Account -> {
                val value = command.value
                val account = finance.accounts.getValue(value.id)
                require(account.revision == 1L && account.name == value.name && account.nature == value.nature.name)
                val anchor = finance.anchors.getValue(value.anchorId)
                require(anchor.accountId == value.id && anchor.date == value.anchorDate.toString())
                val initial = finance.anchorHistories[value.anchorId].orEmpty().singleOrNull { it.beforeRevision == 1L }?.model()?.before
                    ?: BalanceAnchor(anchor.id, LocalDate.parse(anchor.date), anchor.amount, anchor.confirmedAt?.let(Instant::parse), anchor.unknownReason, anchor.revision)
                require(initial.revision == 1L && initial.amount == null && initial.unknownReason == value.unknownReason)
            }

            is WriteCommand.Transaction -> require(finance.transactionSnapshot(command.value.id, 1L).transaction() == command.value)

            is WriteCommand.Update -> require(finance.historyByOperation[operation]?.let { it.before.id == command.value.value.id && it.before.revision == command.value.expectedRevision } == true)

            is WriteCommand.Delete -> require(finance.historyByOperation[operation]?.let { it.before.id == command.value.id && it.before.revision == command.value.expectedRevision } == true)

            is WriteCommand.Anchor -> {
                val row = finance.anchorHistoryByOperation.getValue(operation)
                require(validAnchorCommit(command.value, operations.getValue(operation), row, finance.anchors[command.value.anchorId]))
            }

            is WriteCommand.Payment -> when (val value = command.value) {
                is PaymentCommand.RegisterRule -> {
                    val rule = finance.rules.getValue(value.id)
                    val first = finance.versions.getValue(value.id to 1L)
                    require(rule.startMonth == value.startMonth && first.effectiveMonth == value.startMonth && first.termsJson == paymentTermsJson(value.terms))
                    if (rule.revision == 1L) require(rule.endMonth == value.endMonth)
                }

                is PaymentCommand.ChangeRule -> {
                    val version = finance.versions.getValue(value.id to value.expectedRevision + 1)
                    require(version.termsJson == paymentTermsJson(value.terms) && version.effectiveMonth == value.effectiveMonth)
                }

                is PaymentCommand.EndRule -> require(checkNotNull(finance.rules.getValue(value.id).endMonth) <= value.endMonth)

                is PaymentCommand.Confirm -> {
                    require(finance.eventByItemOperation[value.item.id to operation]?.kind == if (value.full) "CONFIRMED" else "PARTIAL")
                    if (value.transaction.financialRevision == null) require(finance.transactionSnapshot(value.transaction.value.id, 1L).transaction() == value.transaction.value)
                }

                is PaymentCommand.ChangeItem -> require(finance.eventByItemOperation[value.item.id to operation]?.kind == value.action.name)
            }
        }
    }

    private fun validateEvent(event: PaymentEventEntity) {
        val command = checkNotNull(successful[event.operationId])
        val after = finance.decodeItem(event.afterJson)
        when (event.kind) {
            "CONFIRMED", "PARTIAL" -> {
                val value = (command as? WriteCommand.Payment)?.value as? PaymentCommand.Confirm ?: error("Missing payment confirmation")
                require(value.item.id == event.itemId && value.item.expectedRevision + 1 == after.revision && value.full == event.full && value.reason == event.reason && value.transaction.value.id == event.transactionId)
                require(finance.containsFinance(value.transaction.value))
                val terms = decodePaymentTerms(after.termsJson)
                if (value.transaction.value.amount != terms.amount || !value.full) requireLedgerText(value.reason)
            }

            "UNLINK", "SKIP", "UNSKIP" -> {
                val value = (command as? WriteCommand.Payment)?.value as? PaymentCommand.ChangeItem ?: error("Missing payment action")
                require(value.item.id == event.itemId && value.action.name == event.kind && value.item.expectedRevision + 1 == after.revision)
            }

            "RULE_CHANGED" -> {
                val value = (command as? WriteCommand.Payment)?.value as? PaymentCommand.ChangeRule ?: error("Missing rule change")
                require(value.id == after.ruleId && after.month >= value.effectiveMonth && after.ruleVersion == value.expectedRevision + 1 && after.termsJson == paymentTermsJson(value.terms))
            }

            "RULE_ENDED" -> {
                val value = (command as? WriteCommand.Payment)?.value as? PaymentCommand.EndRule ?: error("Missing rule end")
                require(value.id == after.ruleId && after.month > value.endMonth && !after.active)
            }

            "FINANCIAL_CHANGED" -> require(command is WriteCommand.Update && command.value.value.id == event.transactionId && after.status == "RECONFIRM")

            "TRANSACTION_DELETED" -> require(command is WriteCommand.Delete && command.value.id == event.transactionId && after.status == "RECONFIRM" && after.transactionId == null)

            else -> error("Unsupported payment history")
        }
    }
}
