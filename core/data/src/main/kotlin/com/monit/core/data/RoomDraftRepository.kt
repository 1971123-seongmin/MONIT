package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class RoomDraftRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val ledger: RoomLedgerRepository,
    private val contexts: CoroutineContextProvider,
) : DraftRepository {
    private val dao = database.formDraftDao()

    override suspend fun drafts(context: LedgerContext): List<DraftSnapshot> = database.withTransaction {
        ledger.requireContext(context)
        dao.drafts(context.ledgerId).map { snapshot(context, it) }
    }

    override suspend fun draft(context: LedgerContext, id: String): DraftSnapshot = database.withTransaction {
        requireLedgerId(id)
        ledger.requireContext(context)
        dao.draft(context.ledgerId, id)?.let { snapshot(context, it) } ?: DraftSnapshot.Missing
    }

    override suspend fun saveDraft(context: LedgerContext, id: String, expectedRevision: Long, scope: DraftScope, content: DraftContent): FormDraft = withContext(contexts.default) {
        require(expectedRevision >= 0)
        val payload = captureDraft(context, id, scope, content)
        database.withTransaction {
            database.requireDurableWriter()
            ledger.requireContext(context)
            require(scope.parentDraftId != id)
            dao.requireCaller(context, scope)
            val row = dao.draft(context.ledgerId, id)
            if (row == null) {
                if (expectedRevision != 0L) conflict(DraftProblem.STALE_REVISION)
                dao.insert(FormDraftEntity(context.ledgerId, context.storageGeneration, id, 1, captureDraftScope(scope), payload, requestHash(payload), "EDITING", null))
            } else {
                if (row.status != "EDITING") conflict(DraftProblem.CLOSED)
                val current = row.editable(context)
                if (current.scope != scope) conflict(DraftProblem.WRONG_CONTEXT)
                if (expectedRevision > row.revision) conflict(DraftProblem.STALE_REVISION)
                // 응답 유실 뒤 동일 내용의 재저장은 새 revision을 만들지 않는다.
                if (current.content != content) {
                    if (row.revision != expectedRevision) conflict(DraftProblem.STALE_REVISION)
                    Math.addExact(row.revision, 1)
                    check(dao.update(context.ledgerId, context.storageGeneration, id, expectedRevision, payload, requestHash(payload)) == 1)
                }
            }
            currentCoroutineContext().ensureActive()
            checkNotNull(dao.draft(context.ledgerId, id)).editable(context)
        }
    }

    override suspend fun discardDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope) = database.withTransaction {
        database.requireDurableWriter()
        ledger.requireContext(context)
        val row = dao.draft(context.ledgerId, reference.id) ?: conflict(DraftProblem.STALE_REVISION)
        if (row.storageGeneration != context.storageGeneration) conflict(DraftProblem.WRONG_CONTEXT)
        if (decodeDraftScope(row.scopeJson) != scope) conflict(DraftProblem.WRONG_CONTEXT)
        if (row.status == "DISCARDED") return@withTransaction
        if (row.status != "EDITING") conflict(DraftProblem.CLOSED)
        row.editable(context)
        if (row.revision != reference.revision) conflict(DraftProblem.STALE_REVISION)
        Math.addExact(row.revision, 1)
        check(dao.close(context.ledgerId, context.storageGeneration, reference.id, reference.revision, "EDITING", "DISCARDED") == 1)
        currentCoroutineContext().ensureActive()
    }

    override suspend fun createAccountFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateAccount) = ledger.submitFromDraft(context, reference, scope, operationId, WriteCommand.Account(value))

    override suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction) = ledger.submitFromDraft(context, reference, scope, operationId, WriteCommand.Transaction(value))

    private fun snapshot(context: LedgerContext, row: FormDraftEntity): DraftSnapshot = try {
        if (row.storageGeneration != context.storageGeneration) conflict(DraftProblem.WRONG_CONTEXT)
        val ref = DraftReference(row.id, row.revision)
        decodeDraftScope(row.scopeJson)
        when (row.status) {
            "EDITING" -> {
                check(row.operationId == null)
                DraftSnapshot.Editable(row.editable(context))
            }

            "SUBMITTING" -> {
                row.editable(context)
                DraftSnapshot.Submitted(ref, requireLedgerId(checkNotNull(row.operationId)))
            }

            "CONSUMED", "DISCARDED" -> {
                check(row.payloadJson == null && row.payloadSha256 == null)
                if (row.status == "CONSUMED") requireLedgerId(checkNotNull(row.operationId)) else check(row.operationId == null)
                DraftSnapshot.Closed(ref, row.operationId)
            }

            else -> conflict(DraftProblem.CORRUPTED)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: DraftConflictException) {
        DraftSnapshot.Blocked(row.id, problem.problem)
    } catch (_: Exception) {
        DraftSnapshot.Blocked(row.id, DraftProblem.CORRUPTED)
    }

    private fun conflict(problem: DraftProblem): Nothing = throw DraftConflictException(problem)
}
