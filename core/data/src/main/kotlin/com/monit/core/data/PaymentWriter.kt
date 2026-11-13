package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 원래 요청은 별도 보존하고 금융·연결·결과·초안 소비는 단일 outer 트랜잭션으로 확정한다. */
internal class PaymentWriter(private val database: LedgerDatabase, private val clock: Clock, private val ledger: RoomLedgerRepository) {
    private val dao get() = database.paymentDao()
    private val records get() = database.ledgerDao()
    private val drafts get() = database.formDraftDao()

    suspend fun submit(context: LedgerContext, operation: String, command: PaymentCommand, draft: DraftSubmission?): WriteReceipt {
        requireLedgerId(operation)
        val raw = capturePayment(context, command)
        val hash = requestHash(raw)
        val prior = database.withTransaction {
            database.requireDurableWriter()
            ledger.requireContext(context)
            records.operation(context.ledgerId, operation)?.let {
                same(it.requestJson, it.requestSha256, raw, hash)
                return@withTransaction PaymentAttempt.Committed(checkNotNull(ledger.receipt(context, operation)))
            }
            dao.rejection(context.ledgerId, operation)?.let {
                same(it.requestJson, it.requestSha256, raw, hash)
                return@withTransaction PaymentAttempt.Rejected(verifiedRejection(context, it))
            }
            val intent = dao.intent(context.ledgerId, operation)
            if (intent == null) {
                if (draft != null) validateDraft(context, operation, command, draft, lock = false)
                dao.insertIntent(PaymentIntentEntity(context.ledgerId, operation, context.storageGeneration, raw, hash, "READY", draft?.reference?.id, draft?.reference?.revision, draft?.let { captureDraftScope(it.scope) }))
            } else {
                check(intent.storageGeneration == context.storageGeneration)
                same(intent.requestJson, intent.requestSha256, raw, hash)
                check(intent.status == "READY") { "Payment request was explicitly released or has a missing result" }
                val original = intent.prepared(context)
                if (draft != null) check(original.draft == draft)
            }
            currentCoroutineContext().ensureActive()
            null
        }
        if (prior != null) return result(prior)
        val outcome = database.withTransaction {
            database.requireDurableWriter()
            val base = ledger.requireContext(context)
            records.operation(context.ledgerId, operation)?.let {
                same(it.requestJson, it.requestSha256, raw, hash)
                return@withTransaction PaymentAttempt.Committed(checkNotNull(ledger.receipt(context, operation)))
            }
            dao.rejection(context.ledgerId, operation)?.let {
                same(it.requestJson, it.requestSha256, raw, hash)
                return@withTransaction PaymentAttempt.Rejected(verifiedRejection(context, it))
            }
            val intent = checkNotNull(dao.intent(context.ledgerId, operation))
            check(intent.status == "READY" && intent.storageGeneration == context.storageGeneration)
            same(intent.requestJson, intent.requestSha256, raw, hash)
            val original = intent.prepared(context)
            check(original.command == command)
            val pending = records.submission(context.ledgerId, operation)
            if (pending == null) records.insertSubmission(SubmissionEntity(context.ledgerId, operation, raw, hash))
            else same(pending.requestJson, pending.requestSha256, raw, hash)
            val reference = records.submissionReference(context.ledgerId, operation)
            if (reference == null) records.insertSubmissionReference(SubmissionReferenceEntity(context.ledgerId, operation, context.storageGeneration))
            else check(reference.storageGeneration == context.storageGeneration)
            original.draft?.let { validateDraft(context, operation, command, it, lock = true) }
            val store = PaymentStore(database, clock)
            val conflict = store.conflict(context, command)
            if (conflict != null) {
                val row = PaymentRejectionEntity(context.ledgerId, operation, context.storageGeneration, raw, hash, conflict.collection, conflict.id, conflict.expected, conflict.actual, clock.instant().toString())
                dao.insertRejection(row)
                original.draft?.let {
                    Math.addExact(it.reference.revision, 1)
                    check(drafts.reject(context.ledgerId, operation) == 1)
                }
                check(dao.closeIntent(context.ledgerId, operation, "CONSUMED") == 1)
                currentCoroutineContext().ensureActive()
                return@withTransaction PaymentAttempt.Rejected(verifiedRejection(context, row))
            }
            val refs = store.apply(context, operation, command) { value -> ledger.insertTransaction(context, value) }
            if (records.advanceRevision(context.ledgerId, base.revision) != 1) throw LedgerConflictException(LedgerConflict.STALE_REVISION)
            val receipt = WriteReceipt(operation, Math.addExact(base.revision, 1), refs)
            records.insertOperation(OperationEntity(context.ledgerId, operation, context.storageGeneration, command.kind, raw, hash, clock.instant().toString(), receipt.ledgerRevision, encodeRefs(refs)))
            check(records.deleteSubmission(context.ledgerId, operation) == 1)
            original.draft?.let {
                Math.addExact(it.reference.revision, 1)
                check(drafts.close(context.ledgerId, context.storageGeneration, it.reference.id, it.reference.revision, "SUBMITTING", "CONSUMED") == 1)
            }
            check(dao.closeIntent(context.ledgerId, operation, "CONSUMED") == 1)
            currentCoroutineContext().ensureActive()
            PaymentAttempt.Committed(receipt)
        }
        return result(outcome)
    }
    private fun result(value: PaymentAttempt): WriteReceipt = when (value) {
        is PaymentAttempt.Committed -> value.receipt
        is PaymentAttempt.Rejected -> throw PaymentRejectedException(value.rejection)
        else -> error("Not a committed outcome")
    }
    private suspend fun validateDraft(context: LedgerContext, operation: String, command: PaymentCommand, value: DraftSubmission, lock: Boolean) {
        val row = drafts.draft(context.ledgerId, value.reference.id) ?: throw DraftConflictException(DraftProblem.STALE_REVISION)
        if (row.storageGeneration != context.storageGeneration || decodeDraftScope(row.scopeJson) != value.scope) throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
        if (row.revision != value.reference.revision) throw DraftConflictException(DraftProblem.STALE_REVISION)
        check(row.editable(context).matches(WriteCommand.Payment(command))) { "Payment draft and captured request differ" }
        if (row.status == "SUBMITTING") check(row.operationId == operation && records.submission(context.ledgerId, operation) != null)
        else {
            if (row.status != "EDITING") throw DraftConflictException(DraftProblem.CLOSED)
            check(row.operationId == null)
            if (lock) check(drafts.lock(context.ledgerId, context.storageGeneration, row.id, row.revision, operation) == 1)
        }
    }
    suspend fun prepared(context: LedgerContext): List<PreparedPayment> = database.withTransaction {
        ledger.requireContext(context)
        dao.intents(context.ledgerId).map { it.prepared(context) }
    }
    suspend fun attempt(context: LedgerContext, operation: String): PaymentAttempt = database.withTransaction {
        requireLedgerId(operation)
        ledger.requireContext(context)
        records.operation(context.ledgerId, operation)?.let {
            check(it.kind in paymentKinds)
            return@withTransaction PaymentAttempt.Committed(checkNotNull(ledger.receipt(context, operation)))
        }
        dao.rejection(context.ledgerId, operation)?.let { return@withTransaction PaymentAttempt.Rejected(verifiedRejection(context, it)) }
        val intent = dao.intent(context.ledgerId, operation) ?: return@withTransaction PaymentAttempt.Missing
        when (intent.status) {
            "READY" -> PaymentAttempt.Prepared(intent.prepared(context))

            "RELEASED" -> {
                intent.prepared(context)
                PaymentAttempt.Released
            }

            else -> error("Payment intent has no verified outcome")
        }
    }
    suspend fun release(context: LedgerContext, operation: String, command: PaymentCommand?): PaymentAttempt = database.withTransaction {
        database.requireDurableWriter()
        when (val result = attempt(context, operation)) {
            is PaymentAttempt.Prepared -> {
                require(command == null || command == result.request.command)
                check(dao.closeIntent(context.ledgerId, operation, "RELEASED") == 1)
                currentCoroutineContext().ensureActive()
                PaymentAttempt.Released
            }

            PaymentAttempt.Missing -> {
                if (command == null) return@withTransaction result
                val raw = capturePayment(context, command)
                dao.insertIntent(PaymentIntentEntity(context.ledgerId, operation, context.storageGeneration, raw, requestHash(raw), "RELEASED", null, null, null))
                currentCoroutineContext().ensureActive()
                PaymentAttempt.Released
            }

            else -> result
        }
    }
    suspend fun retry(context: LedgerContext, operation: String): WriteReceipt = when (val result = attempt(context, operation)) {
        is PaymentAttempt.Committed -> result.receipt
        is PaymentAttempt.Rejected -> throw PaymentRejectedException(result.rejection)
        is PaymentAttempt.Prepared -> submit(context, operation, result.request.command, result.request.draft)
        else -> error("No retryable payment request")
    }
    suspend fun verifiedRejection(context: LedgerContext, row: PaymentRejectionEntity): PaymentRejection {
        val pending = records.submission(context.ledgerId, row.operationId)
        val operation = records.operation(context.ledgerId, row.operationId)
        val transactionRejection = records.rejection(context.ledgerId, row.operationId)
        val intent = dao.intent(context.ledgerId, row.operationId)
        val present = when (row.collection) {
            "payment_rules" -> dao.rule(context.ledgerId, row.targetId) != null
            "payment_items" -> dao.item(context.ledgerId, row.targetId) != null
            "transactions", "transaction_financial" -> records.transaction(context.ledgerId, row.targetId) != null
            else -> false
        }
        return validatePaymentRejection(context, row, pending, operation, transactionRejection, intent, present)
    }
    internal fun PaymentIntentEntity.prepared(context: LedgerContext): PreparedPayment {
        requireLedgerId(operationId)
        check(ledgerId == context.ledgerId && storageGeneration == context.storageGeneration && requestHash(requestJson) == requestSha256)
        val command = decodePayment(context, requestJson)
        val draft = if (draftId == null) {
            check(draftRevision == null && draftScopeJson == null)
            null
        } else DraftSubmission(DraftReference(draftId, checkNotNull(draftRevision)), decodeDraftScope(checkNotNull(draftScopeJson)))
        return PreparedPayment(context, operationId, command, draft)
    }
    private fun same(stored: String, storedHash: String, value: String, hash: String) {
        if (stored != value || storedHash != hash) throw LedgerConflictException(LedgerConflict.DUPLICATE_REQUEST)
    }
}

/** DAO・취소・I/O는 이 순수 검증 영역 밖에서 처리한다. */
internal fun validatePaymentRejection(context: LedgerContext, row: PaymentRejectionEntity, pending: SubmissionEntity?, operation: OperationEntity?, other: SubmissionRejectionEntity?, intent: PaymentIntentEntity?, targetPresent: Boolean): PaymentRejection {
    requireLedgerId(row.operationId)
    check(row.ledgerId == context.ledgerId && row.storageGeneration == context.storageGeneration)
    check(operation == null && other == null && pending != null && targetPresent)
    check(requestHash(row.requestJson) == row.requestSha256 && pending.requestJson == row.requestJson && pending.requestSha256 == row.requestSha256)
    check(intent != null && intent.status == "CONSUMED" && intent.requestJson == row.requestJson && intent.requestSha256 == row.requestSha256 && intent.storageGeneration == context.storageGeneration)
    val command = decodePayment(context, row.requestJson)
    val allowed = when (command) {
        is PaymentCommand.RegisterRule -> listOf(Triple("payment_rules", command.id, 0L))

        is PaymentCommand.ChangeRule -> listOf(Triple("payment_rules", command.id, command.expectedRevision))

        is PaymentCommand.EndRule -> listOf(Triple("payment_rules", command.id, command.expectedRevision))

        is PaymentCommand.Confirm -> itemConditions(command.item) + listOfNotNull(
            command.transaction.financialRevision?.let { Triple("transaction_financial", command.transaction.value.id, it) },
            command.transaction.revision?.let { Triple("transactions", command.transaction.value.id, it) },
        )

        is PaymentCommand.ChangeItem -> itemConditions(command.item)
    }
    check(Triple(row.collection, row.targetId, row.expectedRevision) in allowed && row.actualRevision > 0 && row.actualRevision != row.expectedRevision)
    Instant.parse(row.rejectedAt)
    return PaymentRejection(context, row.operationId, row.collection, row.targetId, row.expectedRevision, row.actualRevision, row.rejectedAt)
}
private fun itemConditions(item: PaymentItemReference): List<Triple<String, String, Long>> = listOf(Triple("payment_items", item.id, item.expectedRevision)) +
    if (item.expectedRevision == 0L) listOf(Triple("payment_rules", item.ruleId, item.expectedRuleRevision)) else emptyList()
