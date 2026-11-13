package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.AccountNature
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.DeleteTransaction
import com.monit.core.model.DraftConflictException
import com.monit.core.model.DraftProblem
import com.monit.core.model.DraftReference
import com.monit.core.model.DraftScope
import com.monit.core.model.EntityRef
import com.monit.core.model.FinishSubmissionRecoveryResult
import com.monit.core.model.LedgerAccount
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerEntryProblem
import com.monit.core.model.LedgerEntryResult
import com.monit.core.model.LedgerRepository
import com.monit.core.model.LedgerSnapshot
import com.monit.core.model.LedgerTransaction
import com.monit.core.model.PendingRecoverySnapshot
import com.monit.core.model.PendingWrite
import com.monit.core.model.SubmissionRecoveryItem
import com.monit.core.model.SubmissionRecoveryProblem
import com.monit.core.model.SubmissionRecoverySnapshot
import com.monit.core.model.TransactionKind
import com.monit.core.model.TransactionRejectedException
import com.monit.core.model.TransactionRejection
import com.monit.core.model.UpdateTransaction
import com.monit.core.model.WriteReceipt
import com.monit.core.model.requireLedgerId
import com.monit.core.model.sumSignedAmounts
import com.monit.core.model.transactionDateOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RoomLedgerRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val clock: Clock,
    private val coroutineContexts: CoroutineContextProvider,
) : LedgerRepository {
    private val dao = database.ledgerDao()
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))

    override suspend fun openLedger(): LedgerEntryResult = database.withTransaction {
        currentCoroutineContext().ensureActive()
        val ledgers = dao.entryLedgers()
        when {
            ledgers.size > 1 -> LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MULTIPLE_LEDGERS)

            ledgers.size == 1 -> {
                val row = ledgers.single()
                val context = try {
                    require(row.revision > 0)
                    LedgerContext(row.id, row.storageGeneration)
                } catch (_: IllegalArgumentException) {
                    return@withTransaction LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.INVALID_METADATA)
                }
                if (dao.hasRecordsWithoutLedger()) {
                    LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA)
                } else {
                    LedgerEntryResult.Ready(context)
                }
            }

            dao.hasBusinessRecords() -> LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA)

            else -> {
                database.requireDurableWriter()
                val context =
                    LedgerContext(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                dao.insertLedger(LedgerEntity(context.ledgerId, context.storageGeneration, 1))
                currentCoroutineContext().ensureActive()
                LedgerEntryResult.Ready(context)
            }
        }
    }

    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot = database.withTransaction {
        requireContext(context)
        val items = dao.submissions(context.ledgerId).filter { !hasVerifiedRejection(context, it.operationId) }.map {
            currentCoroutineContext().ensureActive()
            it.toRecoveryItem(context)
        }
        PendingRecoverySnapshot(context, items)
    }

    override suspend fun submissionRecovery(context: LedgerContext): SubmissionRecoverySnapshot = database.withTransaction {
        val ledger = requireContext(context)
        val references = dao.submissionReferences(context.ledgerId)
        val pending = dao.submissions(context.ledgerId).associateBy { it.operationId }
        val activeIds = references.mapTo(mutableSetOf()) { it.operationId }
        val items = references.map { reference ->
            currentCoroutineContext().ensureActive()
            recoveryItem(
                context,
                ledger.revision,
                reference,
                pending[reference.operationId],
                dao.operation(context.ledgerId, reference.operationId),
            )
        } + pending.values.filter { it.operationId !in activeIds && !hasVerifiedRejection(context, it.operationId) }.map { submission ->
            currentCoroutineContext().ensureActive()
            recoveryItem(
                context,
                ledger.revision,
                null,
                submission,
                dao.operation(context.ledgerId, submission.operationId),
            )
        }
        SubmissionRecoverySnapshot(context, items)
    }

    override suspend fun finishSubmissionRecovery(
        context: LedgerContext,
        operationId: String,
    ): FinishSubmissionRecoveryResult = database.withTransaction {
        requireLedgerId(operationId)
        database.requireDurableWriter()
        val ledger = requireContext(context)
        val reference = dao.submissionReference(context.ledgerId, operationId)
        val operation = dao.operation(context.ledgerId, operationId)
        val item = recoveryItem(
            context,
            ledger.revision,
            reference ?: dao.rejection(context.ledgerId, operationId)?.let { SubmissionReferenceEntity(context.ledgerId, operationId, it.storageGeneration) } ?: operation?.let {
                SubmissionReferenceEntity(
                    context.ledgerId,
                    operationId,
                    it.storageGeneration,
                )
            },
            dao.submission(context.ledgerId, operationId),
            operation,
        )
        when (item) {
            is SubmissionRecoveryItem.Committed, is SubmissionRecoveryItem.Rejected -> {
                val result =
                    if (reference == null) FinishSubmissionRecoveryResult.AlreadyInactive else {
                        check(dao.deleteSubmissionReference(context.ledgerId, operationId) == 1)
                        FinishSubmissionRecoveryResult.Finished
                    }
                currentCoroutineContext().ensureActive()
                result
            }

            is SubmissionRecoveryItem.Blocked -> FinishSubmissionRecoveryResult.NotReady(item.reason)

            is SubmissionRecoveryItem.Pending, is SubmissionRecoveryItem.LegacyPending ->
                FinishSubmissionRecoveryResult.NotReady(SubmissionRecoveryProblem.PENDING)
        }
    }

    override suspend fun initialize(context: LedgerContext) = database.withTransaction {
        database.requireDurableWriter()
        val existing = dao.ledger(context.ledgerId)
        if (existing == null) dao.insertLedger(
            LedgerEntity(
                context.ledgerId,
                context.storageGeneration,
                1,
            ),
        )
        else requireContext(context)
        Unit
    }

    override suspend fun createAccount(
        context: LedgerContext,
        operationId: String,
        value: CreateAccount,
    ) = submit(context, operationId, WriteCommand.Account(value))

    override suspend fun createTransaction(
        context: LedgerContext,
        operationId: String,
        value: CreateTransaction,
    ) = submit(context, operationId, WriteCommand.Transaction(value))

    override suspend fun updateTransaction(context: LedgerContext, operationId: String, value: UpdateTransaction) = submit(context, operationId, WriteCommand.Update(value))
    override suspend fun deleteTransaction(context: LedgerContext, operationId: String, value: DeleteTransaction) = submit(context, operationId, WriteCommand.Delete(value))

    override suspend fun rejection(context: LedgerContext, operationId: String): TransactionRejection? = database.withTransaction {
        requireLedgerId(operationId)
        requireContext(context)
        dao.rejection(context.ledgerId, operationId)?.let { verifiedRejection(context, it) }
    }

    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = database.withTransaction {
        requireLedgerId(operationId)
        val ledger = requireContext(context)
        dao.operation(context.ledgerId, operationId)
            ?.let { verifiedReceipt(context, ledger, it) }
    }

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = database.withTransaction {
        requireContext(context)
        dao.submissions(context.ledgerId).filter { !hasVerifiedRejection(context, it.operationId) }.map { PendingWrite(it.operationId, it.requestJson) }
    }

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = withContext(coroutineContexts.default) {
        val (prior, pending) = database.withTransaction {
            requireLedgerId(operationId)
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)
                ?.let { return@withTransaction verifiedReceipt(context, ledger, it) to null }
            dao.rejection(context.ledgerId, operationId)?.let { throw TransactionRejectedException(verifiedRejection(context, it)) }
            val submission = checkNotNull(
                dao.submission(
                    context.ledgerId,
                    operationId,
                ),
            ) { "No captured write" }
            check(requestHash(submission.requestJson) == submission.requestSha256)
            null to submission
        }
        if (prior != null) return@withContext prior
        val submission = checkNotNull(pending)
        submit(context, operationId, decodeWrite(context, submission.requestJson))
    }

    /** 제출 의도를 먼저 commit하고 금융 변경·결과·의도 소비는 다음 단일 트랜잭션에서 확정한다. */
    internal suspend fun submitFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, command: WriteCommand) = submit(context, operationId, command, DraftAttempt(reference, scope))

    private data class DraftAttempt(val reference: DraftReference, val scope: DraftScope)

    private suspend fun submit(
        context: LedgerContext,
        operationId: String,
        command: WriteCommand,
        draft: DraftAttempt? = null,
    ): WriteReceipt = withContext(coroutineContexts.default) {
        requireLedgerId(operationId)
        val payload = captureWrite(context, command)
        val hash = requestHash(payload)
        val prior = database.withTransaction {
            database.requireDurableWriter()
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                completedDraft(context, operationId, draft)
                return@withTransaction verifiedReceipt(context, ledger, it)
            }
            dao.rejection(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                throw TransactionRejectedException(verifiedRejection(context, it))
            }
            lockDraft(context, operationId, command, draft)
            val reference = dao.submissionReference(context.ledgerId, operationId)
            if (reference != null && reference.storageGeneration != context.storageGeneration) {
                conflict(LedgerConflict.STALE_GENERATION)
            }
            val pending = dao.submission(context.ledgerId, operationId)
            check(reference == null || pending != null) { "Submission reference has no captured write" }
            if (pending == null) dao.insertSubmission(
                SubmissionEntity(
                    context.ledgerId,
                    operationId,
                    payload,
                    hash,
                ),
            )
            else requireSameRequest(pending.requestJson, pending.requestSha256, payload, hash)
            if (reference == null) dao.insertSubmissionReference(
                SubmissionReferenceEntity(context.ledgerId, operationId, context.storageGeneration),
            )
            currentCoroutineContext().ensureActive()
            null
        }
        if (prior != null) return@withContext prior
        val outcome = database.withTransaction {
            database.requireDurableWriter()
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                completedDraft(context, operationId, draft)
                return@withTransaction verifiedReceipt(context, ledger, it)
            }
            val reference = checkNotNull(dao.submissionReference(context.ledgerId, operationId))
            if (reference.storageGeneration != context.storageGeneration) conflict(LedgerConflict.STALE_GENERATION)
            val pending = checkNotNull(dao.submission(context.ledgerId, operationId))
            requireSameRequest(pending.requestJson, pending.requestSha256, payload, hash)
            dao.rejection(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                return@withTransaction verifiedRejection(context, it)
            }
            val linked = linkedDraft(context, operationId, command)
            val target = when (command) {
                is WriteCommand.Update -> command.value.value.id to command.value.expectedRevision
                is WriteCommand.Delete -> command.value.id to command.value.expectedRevision
                else -> null
            }
            if (target != null) {
                val row = dao.transaction(context.ledgerId, target.first) ?: conflict(LedgerConflict.MISSING_REFERENCE)
                if (row.revision != target.second || row.deletedAt != null) {
                    val rejected = SubmissionRejectionEntity(context.ledgerId, operationId, context.storageGeneration, payload, hash, target.first, target.second, row.revision, row.deletedAt, clock.instant().toString())
                    dao.insertRejection(rejected)
                    if (linked != null) {
                        Math.addExact(linked.revision, 1)
                        check(database.formDraftDao().reject(context.ledgerId, operationId) == 1)
                    }
                    currentCoroutineContext().ensureActive()
                    return@withTransaction verifiedRejection(context, rejected)
                }
            }
            val refs = when (command) {
                is WriteCommand.Account -> insertAccount(context, command.value)
                is WriteCommand.Transaction -> insertTransaction(context, command.value)
                is WriteCommand.Update -> changeTransaction(context, operationId, command.value.value.id, command.value.expectedRevision, command.value.value)
                is WriteCommand.Delete -> changeTransaction(context, operationId, command.value.id, command.value.expectedRevision, null)
            }
            if (dao.advanceRevision(
                    context.ledgerId,
                    ledger.revision,
                ) != 1
            ) conflict(LedgerConflict.STALE_REVISION)
            val result = WriteReceipt(operationId, Math.addExact(ledger.revision, 1), refs)
            val kind = when (command) {
                is WriteCommand.Account -> "UPDATE_ACCOUNT"
                is WriteCommand.Transaction -> "CREATE_TRANSACTION"
                is WriteCommand.Update -> "UPDATE_TRANSACTION"
                is WriteCommand.Delete -> "DELETE_TRANSACTION"
            }
            dao.insertOperation(
                OperationEntity(
                    context.ledgerId,
                    operationId,
                    context.storageGeneration,
                    kind,
                    payload,
                    hash,
                    clock.instant().toString(),
                    result.ledgerRevision,
                    encodeRefs(refs),
                ),
            )
            check(dao.deleteSubmission(context.ledgerId, operationId) == 1)
            linked?.let {
                Math.addExact(it.revision, 1)
                check(database.formDraftDao().close(context.ledgerId, context.storageGeneration, it.id, it.revision, "SUBMITTING", "CONSUMED") == 1)
            }
            currentCoroutineContext().ensureActive()
            result
        }
        when (outcome) {
            is WriteReceipt -> outcome
            is TransactionRejection -> throw TransactionRejectedException(outcome)
            else -> error("Unknown transaction outcome")
        }
    }

    private suspend fun hasVerifiedRejection(context: LedgerContext, operationId: String): Boolean {
        val row = dao.rejection(context.ledgerId, operationId) ?: return false
        verifiedRejection(context, row)
        return true
    }

    private suspend fun verifiedRejection(context: LedgerContext, row: SubmissionRejectionEntity): TransactionRejection {
        val pending = dao.submission(context.ledgerId, row.operationId)
        val operation = dao.operation(context.ledgerId, row.operationId)
        val targetPresent = dao.transaction(context.ledgerId, row.transactionId) != null
        return decodeRejection(context, row, pending, operation, targetPresent)
    }

    private fun decodeRejection(context: LedgerContext, row: SubmissionRejectionEntity, pendingRow: SubmissionEntity?, operation: OperationEntity?, targetPresent: Boolean): TransactionRejection {
        check(row.ledgerId == context.ledgerId && row.storageGeneration == context.storageGeneration)
        requireLedgerId(row.operationId)
        check(operation == null)
        check(requestHash(row.requestJson) == row.requestSha256)
        val pending = checkNotNull(pendingRow)
        check(pending.requestJson == row.requestJson && pending.requestSha256 == row.requestSha256)
        val target = when (val command = decodeWrite(context, row.requestJson)) {
            is WriteCommand.Update -> command.value.value.id to command.value.expectedRevision
            is WriteCommand.Delete -> command.value.id to command.value.expectedRevision
            else -> error("Not a transaction change")
        }
        check(target == (row.transactionId to row.expectedRevision))
        check(row.actualRevision > 0 && (row.actualRevision != row.expectedRevision || row.actualDeletedAt != null))
        java.time.Instant.parse(row.rejectedAt)
        row.actualDeletedAt?.let(java.time.Instant::parse)
        check(targetPresent)
        return TransactionRejection(context, row.operationId, row.transactionId, row.expectedRevision, row.actualRevision, row.actualDeletedAt, row.rejectedAt)
    }

    private suspend fun lockDraft(context: LedgerContext, operation: String, command: WriteCommand, attempt: DraftAttempt?) {
        val drafts = database.formDraftDao()
        if (attempt == null) {
            linkedDraft(context, operation, command)
            return
        }
        val row = drafts.draft(context.ledgerId, attempt.reference.id) ?: draftConflict(DraftProblem.STALE_REVISION)
        if (row.storageGeneration != context.storageGeneration || decodeDraftScope(row.scopeJson) != attempt.scope) draftConflict(DraftProblem.WRONG_CONTEXT)
        if (row.revision != attempt.reference.revision) draftConflict(DraftProblem.STALE_REVISION)
        if (row.status != "EDITING" && row.status != "SUBMITTING") draftConflict(DraftProblem.CLOSED)
        check(row.editable(context).matches(command)) { "Draft and submitted value differ" }
        if (row.status == "SUBMITTING") {
            check(row.operationId == operation && dao.submission(context.ledgerId, operation) != null && dao.submissionReference(context.ledgerId, operation) != null)
        } else {
            check(row.operationId == null)
            if (dao.submission(context.ledgerId, operation) != null || dao.submissionReference(context.ledgerId, operation) != null) draftConflict(DraftProblem.WRONG_CONTEXT)
            drafts.requireCaller(context, attempt.scope)
            check(drafts.lock(context.ledgerId, context.storageGeneration, row.id, row.revision, operation) == 1)
        }
    }

    private suspend fun linkedDraft(context: LedgerContext, operation: String, command: WriteCommand): FormDraftEntity? {
        val row = database.formDraftDao().linked(context.ledgerId, operation) ?: return null
        check(row.status == "SUBMITTING" && row.operationId == operation)
        check(row.editable(context).matches(command)) { "Linked draft and captured command differ" }
        return row
    }

    private suspend fun completedDraft(context: LedgerContext, operation: String, attempt: DraftAttempt?) {
        val row = database.formDraftDao().linked(context.ledgerId, operation)
        if (row == null) {
            if (attempt != null) draftConflict(DraftProblem.WRONG_CONTEXT)
            return
        }
        check(row.storageGeneration == context.storageGeneration && row.status == "CONSUMED" && row.payloadJson == null && row.payloadSha256 == null)
        decodeDraftScope(row.scopeJson)
        if (attempt != null) {
            if (row.id != attempt.reference.id || row.revision != Math.addExact(attempt.reference.revision, 1) || decodeDraftScope(row.scopeJson) != attempt.scope) draftConflict(DraftProblem.WRONG_CONTEXT)
        }
    }

    private fun draftConflict(problem: DraftProblem): Nothing = throw DraftConflictException(problem)

    private suspend fun insertAccount(
        context: LedgerContext,
        value: CreateAccount,
    ): List<EntityRef> {
        require(value.anchorDate >= LocalDate.of(1900, 1, 1) && value.anchorDate < today()) {
            "Anchor date must be before today and within the supported range"
        }
        if (dao.account(context.ledgerId, value.id) != null || dao.anchor(
                context.ledgerId,
                value.anchorId,
            ) != null
        )
            conflict(LedgerConflict.DUPLICATE_ENTITY)
        dao.insertAccount(
            AccountEntity(
                context.ledgerId,
                value.id,
                value.name,
                value.nature.name,
                "ACTIVE",
                1,
            ),
        )
        dao.insertAnchor(
            AnchorEntity(
                context.ledgerId,
                value.anchorId,
                value.id,
                value.anchorDate.toString(),
                null,
                null,
                value.unknownReason,
                1,
            ),
        )
        return listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1))
    }

    private suspend fun insertTransaction(
        context: LedgerContext,
        value: CreateTransaction,
    ): List<EntityRef> {
        transactionDateOf(value.date.year, value.date.monthValue, value.date.dayOfMonth, today())
        if (dao.transaction(
                context.ledgerId,
                value.id,
            ) != null
        ) conflict(LedgerConflict.DUPLICATE_ENTITY)
        suspend fun account(id: String?): AccountEntity? {
            if (id == null) return null
            val account =
                dao.account(context.ledgerId, id) ?: conflict(LedgerConflict.MISSING_REFERENCE)
            if (account.status != "ACTIVE") conflict(LedgerConflict.ARCHIVED_ACCOUNT)
            return account
        }

        val debit = account(value.debitAccountId)
        val credit = account(value.creditAccountId)
        dao.insertTransaction(
            TransactionEntity(
                context.ledgerId,
                value.id,
                value.kind.name,
                value.date.toString(),
                value.amount,
                debit?.id,
                credit?.id,
                debit?.nature,
                credit?.nature,
                value.memo,
                1,
                1,
            ),
        )
        // 현재 잔액과 입력 거래가 영향을 준 월의 전체 합계를 같은 writer 스냅샷에서 검사한다.
        snapshotInside(context, today())
        val monthEnd = value.date.withDayOfMonth(value.date.lengthOfMonth())
        snapshotInside(context, minOf(monthEnd, today()))
        return listOf(EntityRef("transactions", value.id, 1))
    }

    /** CAS는 성공 영수증 확인 후 동일 writer 트랜잭션에서만 수행한다. */
    private suspend fun changeTransaction(context: LedgerContext, operationId: String, id: String, expected: Long, value: CreateTransaction?): List<EntityRef> {
        val before = dao.transaction(context.ledgerId, id) ?: conflict(LedgerConflict.MISSING_REFERENCE)
        if (before.revision != expected || before.deletedAt != null) conflict(LedgerConflict.STALE_REVISION)
        val changedAt = clock.instant().toString()
        val after = if (value == null) before.copy(
            revision = Math.addExact(before.revision, 1),
            financialRevision = Math.addExact(before.financialRevision, 1),
            deletedAt = changedAt,
        ) else {
            transactionDateOf(value.date.year, value.date.monthValue, value.date.dayOfMonth, today())
            val financialChange = before.kind != value.kind.name || before.date != value.date.toString() ||
                before.amount != value.amount || before.debitAccountId != value.debitAccountId || before.creditAccountId != value.creditAccountId
            suspend fun account(id: String?): AccountEntity? {
                if (id == null) return null
                val account = dao.account(context.ledgerId, id) ?: conflict(LedgerConflict.MISSING_REFERENCE)
                if (financialChange && account.status != "ACTIVE") conflict(LedgerConflict.ARCHIVED_ACCOUNT)
                return account
            }
            val debit = account(value.debitAccountId)
            val credit = account(value.creditAccountId)
            before.copy(
                kind = value.kind.name, date = value.date.toString(), amount = value.amount,
                debitAccountId = debit?.id, creditAccountId = credit?.id,
                debitNature = if (financialChange) debit?.nature else before.debitNature,
                creditNature = if (financialChange) credit?.nature else before.creditNature,
                memo = value.memo, revision = Math.addExact(before.revision, 1),
                financialRevision = if (financialChange) Math.addExact(before.financialRevision, 1) else before.financialRevision,
            )
        }
        dao.insertTransactionHistory(TransactionHistoryEntity(before, operationId, changedAt, if (value == null) "DELETE" else "UPDATE"))
        check(dao.updateTransaction(after) == 1)
        snapshotInside(context, today())
        for (date in listOf(before.date, after.date).distinct()) {
            val month = LocalDate.parse(date)
            snapshotInside(context, minOf(month.withDayOfMonth(month.lengthOfMonth()), today()))
        }
        return listOf(EntityRef("transactions", id, after.revision))
    }

    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot = database.withTransaction {
        require(asOf >= LocalDate.of(1900, 1, 1) && asOf <= today())
        snapshotInside(context, asOf)
    }

    private suspend fun snapshotInside(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        val ledger = requireContext(context)
        val anchors = dao.anchors(context.ledgerId).associateBy { it.accountId }
        val accounts = dao.accounts(context.ledgerId).map { a ->
            val anchor = checkNotNull(anchors[a.id]) { "Account has no anchor state" }
            LedgerAccount(
                a.id,
                a.name,
                AccountNature.valueOf(a.nature),
                AccountStatus.valueOf(a.status),
                a.revision,
                BalanceAnchor(
                    anchor.id,
                    LocalDate.parse(anchor.date),
                    anchor.amount,
                    anchor.confirmedAt?.let(Instant::parse),
                    anchor.unknownReason,
                ),
            )
        }
        val transactions = dao.transactions(context.ledgerId).filter { it.deletedAt == null }.map { t ->
            val value = CreateTransaction(
                t.id,
                TransactionKind.valueOf(t.kind),
                LocalDate.parse(t.date),
                t.amount,
                t.debitAccountId,
                t.creditAccountId,
                t.memo,
            )
            LedgerTransaction(
                value.id,
                value.kind,
                value.date,
                value.amount,
                value.debitAccountId,
                value.creditAccountId,
                t.debitNature?.let(AccountNature::valueOf),
                t.creditNature?.let(AccountNature::valueOf),
                value.memo,
                t.revision,
                t.financialRevision,
            )
        }.filter { it.date <= asOf }
        val balances = accounts.associate { account ->
            val base = account.anchor
            val baseAmount = base.amount
            val balance = if (baseAmount == null || asOf < base.date) null else sumSignedAmounts(
                listOf(baseAmount) + transactions.filter { it.date > base.date }.mapNotNull { t ->
                    when (account.id) {
                        t.debitAccountId -> -t.amount
                        t.creditAccountId -> t.amount
                        else -> null
                    }
                },
            )
            account.id to balance
        }
        val monthly = transactions.filter { YearMonth.from(it.date) == YearMonth.from(asOf) }
        return LedgerSnapshot(
            ledger.revision,
            accounts,
            transactions,
            balances,
            sumSignedAmounts(
                monthly.filter { it.kind == TransactionKind.INCOME }
                    .map { it.amount },
            ),
            sumSignedAmounts(
                monthly.filter { it.kind == TransactionKind.EXPENSE }
                    .map { it.amount },
            ),
        )
    }

    internal suspend fun requireContext(context: LedgerContext): LedgerEntity {
        val ledger = dao.ledger(context.ledgerId) ?: conflict(LedgerConflict.MISSING_REFERENCE)
        if (ledger.storageGeneration != context.storageGeneration) conflict(LedgerConflict.STALE_GENERATION)
        check(ledger.revision > 0)
        return ledger
    }

    private suspend fun verifiedReceipt(
        context: LedgerContext,
        ledger: LedgerEntity,
        operation: OperationEntity,
    ): WriteReceipt {
        val reference = dao.submissionReference(context.ledgerId, operation.operationId)
            ?: SubmissionReferenceEntity(
                context.ledgerId,
                operation.operationId,
                operation.storageGeneration,
            )
        val item = recoveryItem(
            context,
            ledger.revision,
            reference,
            dao.submission(context.ledgerId, operation.operationId),
            operation,
        )
        check(item is SubmissionRecoveryItem.Committed) { "Stored submission is not a verified success" }
        return item.receipt
    }

    /** 호출자의 트랜잭션 안에서 대상 행도 확인한다. 현재 revision을 과거 receipt 값으로 맞추지 않는다. */
    private suspend fun recoveryItem(
        context: LedgerContext,
        ledgerRevision: Long,
        reference: SubmissionReferenceEntity?,
        pending: SubmissionEntity?,
        operation: OperationEntity?,
    ): SubmissionRecoveryItem {
        val rejected = reference?.let { dao.rejection(context.ledgerId, it.operationId) }
        if (rejected != null) {
            val rejectedPending = dao.submission(context.ledgerId, rejected.operationId)
            val rejectedOperation = dao.operation(context.ledgerId, rejected.operationId)
            val targetPresent = dao.transaction(context.ledgerId, rejected.transactionId) != null
            return try {
                check(reference.storageGeneration == context.storageGeneration)
                SubmissionRecoveryItem.Rejected(com.monit.core.model.SubmissionReference(context, reference.operationId), decodeRejection(context, rejected, rejectedPending, rejectedOperation, targetPresent))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)
            } catch (error: IllegalStateException) {
                SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)
            } catch (error: org.json.JSONException) {
                SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)
            } catch (error: java.time.DateTimeException) {
                SubmissionRecoveryItem.Blocked(null, SubmissionRecoveryProblem.CORRUPTED)
            }
        }
        val item = submissionRecoveryItem(context, ledgerRevision, reference, pending, operation)
        if (item !is SubmissionRecoveryItem.Committed) return item
        for (entity in item.receipt.entities) {
            val present = when (entity.collection) {
                "accounts" -> dao.account(context.ledgerId, entity.id) != null
                "anchors" -> dao.anchor(context.ledgerId, entity.id) != null
                "transactions" -> dao.transaction(context.ledgerId, entity.id) != null
                else -> false
            }
            if (!present) return SubmissionRecoveryItem.Blocked(
                item.reference,
                SubmissionRecoveryProblem.CORRUPTED,
            )
        }
        return item
    }

    private fun requireSameRequest(
        stored: String,
        storedHash: String,
        request: String,
        hash: String,
    ) {
        if (stored != request || storedHash != hash) conflict(LedgerConflict.DUPLICATE_REQUEST)
    }

    private fun conflict(kind: LedgerConflict): Nothing = throw LedgerConflictException(kind)
}
