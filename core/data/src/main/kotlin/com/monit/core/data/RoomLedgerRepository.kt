package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.di.DefaultDispatcher
import com.monit.core.model.AccountNature
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.CreateTransaction
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerAccount
import com.monit.core.model.LedgerConflict
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.LedgerSnapshot
import com.monit.core.model.LedgerTransaction
import com.monit.core.model.PendingWrite
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
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class RoomLedgerRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val clock: Clock,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : LedgerRepository {
    private val dao = database.ledgerDao()
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))

    override suspend fun initialize(context: LedgerContext) = database.withTransaction {
        database.requireDurableWriter()
        val existing = dao.ledger(context.ledgerId)
        if (existing == null) dao.insertLedger(LedgerEntity(context.ledgerId, context.storageGeneration, 1))
        else requireContext(context)
        Unit
    }

    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount) =
        submit(context, operationId, WriteCommand.Account(value))

    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction) =
        submit(context, operationId, WriteCommand.Transaction(value))

    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? =
        database.withTransaction {
            requireLedgerId(operationId)
            requireContext(context)
            dao.operation(context.ledgerId, operationId)?.receipt()
        }

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = database.withTransaction {
        requireContext(context)
        dao.submissions(context.ledgerId).map { PendingWrite(it.operationId, it.requestJson) }
    }

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = withContext(defaultDispatcher) {
        val (prior, pending) = database.withTransaction {
            requireLedgerId(operationId)
            requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let { return@withTransaction it.receipt() to null }
            val submission = checkNotNull(dao.submission(context.ledgerId, operationId)) { "No captured write" }
            check(requestHash(submission.requestJson) == submission.requestSha256)
            null to submission
        }
        if (prior != null) return@withContext prior
        val submission = checkNotNull(pending)
        submit(context, operationId, decodeWrite(context, submission.requestJson))
    }

    /** 제출 의도를 먼저 commit하고 금융 변경·결과·의도 소비는 다음 단일 트랜잭션에서 확정한다. */
    private suspend fun submit(context: LedgerContext, operationId: String, command: WriteCommand): WriteReceipt = withContext(defaultDispatcher) {
        requireLedgerId(operationId)
        val payload = captureWrite(context, command)
        val hash = requestHash(payload)
        val prior = database.withTransaction {
            database.requireDurableWriter()
            requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                return@withTransaction it.receipt()
            }
            val pending = dao.submission(context.ledgerId, operationId)
            if (pending == null) dao.insertSubmission(SubmissionEntity(context.ledgerId, operationId, payload, hash))
            else requireSameRequest(pending.requestJson, pending.requestSha256, payload, hash)
            null
        }
        if (prior != null) return@withContext prior
        database.withTransaction {
            database.requireDurableWriter()
            val ledger = requireContext(context)
            dao.operation(context.ledgerId, operationId)?.let {
                requireSameRequest(it.requestJson, it.requestSha256, payload, hash)
                return@withTransaction it.receipt()
            }
            val pending = checkNotNull(dao.submission(context.ledgerId, operationId))
            requireSameRequest(pending.requestJson, pending.requestSha256, payload, hash)
            val refs = when (command) {
                is WriteCommand.Account -> insertAccount(context, command.value)
                is WriteCommand.Transaction -> insertTransaction(context, command.value)
            }
            if (dao.advanceRevision(context.ledgerId, ledger.revision) != 1) conflict(LedgerConflict.STALE_REVISION)
            val result = WriteReceipt(operationId, Math.addExact(ledger.revision, 1), refs)
            val kind = if (command is WriteCommand.Account) "UPDATE_ACCOUNT" else "CREATE_TRANSACTION"
            dao.insertOperation(OperationEntity(context.ledgerId, operationId, context.storageGeneration, kind,
                payload, hash, clock.instant().toString(), result.ledgerRevision, encodeRefs(refs)))
            check(dao.deleteSubmission(context.ledgerId, operationId) == 1)
            result
        }
    }

    private suspend fun insertAccount(context: LedgerContext, value: CreateAccount): List<EntityRef> {
        require(value.anchorDate >= LocalDate.of(1900, 1, 1) && value.anchorDate < today()) {
            "Anchor date must be before today and within the supported range"
        }
        if (dao.account(context.ledgerId, value.id) != null || dao.anchor(context.ledgerId, value.anchorId) != null)
            conflict(LedgerConflict.DUPLICATE_ENTITY)
        dao.insertAccount(AccountEntity(context.ledgerId, value.id, value.name, value.nature.name, "ACTIVE", 1))
        dao.insertAnchor(AnchorEntity(context.ledgerId, value.anchorId, value.id, value.anchorDate.toString(),
            null, null, value.unknownReason, 1))
        return listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1))
    }

    private suspend fun insertTransaction(context: LedgerContext, value: CreateTransaction): List<EntityRef> {
        transactionDateOf(value.date.year, value.date.monthValue, value.date.dayOfMonth, today())
        if (dao.transaction(context.ledgerId, value.id) != null) conflict(LedgerConflict.DUPLICATE_ENTITY)
        suspend fun account(id: String?): AccountEntity? {
            if (id == null) return null
            val account = dao.account(context.ledgerId, id) ?: conflict(LedgerConflict.MISSING_REFERENCE)
            if (account.status != "ACTIVE") conflict(LedgerConflict.ARCHIVED_ACCOUNT)
            return account
        }
        val debit = account(value.debitAccountId)
        val credit = account(value.creditAccountId)
        dao.insertTransaction(TransactionEntity(context.ledgerId, value.id, value.kind.name,
            value.date.toString(), value.amount, debit?.id, credit?.id, debit?.nature, credit?.nature,
            value.memo, 1, 1))
        // 현재 잔액과 입력 거래가 영향을 준 월의 전체 합계를 같은 writer 스냅샷에서 검사한다.
        snapshotInside(context, today())
        val monthEnd = value.date.withDayOfMonth(value.date.lengthOfMonth())
        snapshotInside(context, minOf(monthEnd, today()))
        return listOf(EntityRef("transactions", value.id, 1))
    }

    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot =
        database.withTransaction {
            require(asOf >= LocalDate.of(1900, 1, 1) && asOf <= today())
            snapshotInside(context, asOf)
        }

    private suspend fun snapshotInside(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        val ledger = requireContext(context)
        val anchors = dao.anchors(context.ledgerId).associateBy { it.accountId }
        val accounts = dao.accounts(context.ledgerId).map { a ->
            val anchor = checkNotNull(anchors[a.id]) { "Account has no anchor state" }
            LedgerAccount(a.id, a.name, AccountNature.valueOf(a.nature), AccountStatus.valueOf(a.status),
                a.revision, BalanceAnchor(anchor.id, LocalDate.parse(anchor.date), anchor.amount,
                    anchor.confirmedAt?.let(Instant::parse), anchor.unknownReason))
        }
        val transactions = dao.transactions(context.ledgerId).map { t ->
            val value = CreateTransaction(t.id, TransactionKind.valueOf(t.kind), LocalDate.parse(t.date),
                t.amount, t.debitAccountId, t.creditAccountId, t.memo)
            LedgerTransaction(value.id, value.kind, value.date, value.amount, value.debitAccountId,
                value.creditAccountId, t.debitNature?.let(AccountNature::valueOf),
                t.creditNature?.let(AccountNature::valueOf), value.memo, t.revision, t.financialRevision)
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
        return LedgerSnapshot(ledger.revision, accounts, transactions, balances,
            sumSignedAmounts(monthly.filter { it.kind == TransactionKind.INCOME }.map { it.amount }),
            sumSignedAmounts(monthly.filter { it.kind == TransactionKind.EXPENSE }.map { it.amount }))
    }

    private suspend fun requireContext(context: LedgerContext): LedgerEntity {
        val ledger = dao.ledger(context.ledgerId) ?: conflict(LedgerConflict.MISSING_REFERENCE)
        if (ledger.storageGeneration != context.storageGeneration) conflict(LedgerConflict.STALE_GENERATION)
        check(ledger.revision > 0)
        return ledger
    }

    private fun requireSameRequest(stored: String, storedHash: String, request: String, hash: String) {
        if (stored != request || storedHash != hash) conflict(LedgerConflict.DUPLICATE_REQUEST)
    }

    private fun conflict(kind: LedgerConflict): Nothing = throw LedgerConflictException(kind)
}
