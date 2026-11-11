package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.AccountNature
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
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
        val items = dao.submissions(context.ledgerId).map {
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
        } + pending.values.filter { it.operationId !in activeIds }.map { submission ->
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
            reference ?: operation?.let {
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
            is SubmissionRecoveryItem.Committed -> {
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

    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = database.withTransaction {
        requireLedgerId(operationId)
        val ledger = requireContext(context)
        dao.operation(context.ledgerId, operationId)
            ?.let { verifiedReceipt(context, ledger, it) }
    }

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = database.withTransaction {
        requireContext(context)
        dao.submissions(context.ledgerId).map { PendingWrite(it.operationId, it.requestJson) }
    }

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = withContext(coroutineContexts.default) {
        val (prior, pending) = database.withTransaction {
            requireLedgerId(operationId)
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)
                ?.let { return@withTransaction verifiedReceipt(context, ledger, it) to null }
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
    private suspend fun submit(
        context: LedgerContext,
        operationId: String,
        command: WriteCommand,
    ): WriteReceipt = withContext(coroutineContexts.default) {
        requireLedgerId(operationId)
        val payload = captureWrite(context, command)
        val hash = requestHash(payload)
        val prior = database.withTransaction {
            database.requireDurableWriter()
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                return@withTransaction verifiedReceipt(context, ledger, it)
            }
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
        database.withTransaction {
            database.requireDurableWriter()
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                return@withTransaction verifiedReceipt(context, ledger, it)
            }
            val reference = checkNotNull(dao.submissionReference(context.ledgerId, operationId))
            if (reference.storageGeneration != context.storageGeneration) conflict(LedgerConflict.STALE_GENERATION)
            val pending = checkNotNull(dao.submission(context.ledgerId, operationId))
            requireSameRequest(pending.requestJson, pending.requestSha256, payload, hash)
            val refs = when (command) {
                is WriteCommand.Account -> insertAccount(context, command.value)
                is WriteCommand.Transaction -> insertTransaction(context, command.value)
            }
            if (dao.advanceRevision(
                    context.ledgerId,
                    ledger.revision,
                ) != 1
            ) conflict(LedgerConflict.STALE_REVISION)
            val result = WriteReceipt(operationId, Math.addExact(ledger.revision, 1), refs)
            val kind =
                if (command is WriteCommand.Account) "UPDATE_ACCOUNT" else "CREATE_TRANSACTION"
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
            currentCoroutineContext().ensureActive()
            result
        }
    }

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
        val transactions = dao.transactions(context.ledgerId).map { t ->
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

    private suspend fun requireContext(context: LedgerContext): LedgerEntity {
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
