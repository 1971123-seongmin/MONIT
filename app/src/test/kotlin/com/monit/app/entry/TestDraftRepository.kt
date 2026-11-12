package com.monit.app.entry

import com.monit.core.model.*

/** VM/UI 시험의 합성 저장소. 실제 Room 원자성/파일 재열기는 data 시험에서 확인한다. */
internal class TestDraftRepository(private val ledger: LedgerRepository) : DraftRepository {
    private val rows = linkedMapOf<String, DraftSnapshot>()
    var beforeSave: suspend () -> Unit = {}
    var failSave = false
    var saves = 0
    override suspend fun drafts(context: LedgerContext): List<DraftSnapshot> = rows.values.filter { it is DraftSnapshot.Editable || it is DraftSnapshot.Submitted }
    override suspend fun draft(context: LedgerContext, id: String): DraftSnapshot = rows[id] ?: DraftSnapshot.Missing
    override suspend fun saveDraft(context: LedgerContext, id: String, expectedRevision: Long, scope: DraftScope, content: DraftContent): FormDraft {
        beforeSave()
        if (failSave) throw java.io.IOException("Synthetic draft failure")
        val previous = rows[id]
        val row = (previous as? DraftSnapshot.Editable)?.draft
        require(previous == null || row != null)
        require(row?.context == null || row.context == context)
        require(row?.scope == null || row.scope == scope)
        if (row != null && row.content == content) return row
        require((row?.reference?.revision ?: 0) == expectedRevision)
        saves++
        return FormDraft(context, DraftReference(id, expectedRevision + 1), scope, content).also { rows[id] = DraftSnapshot.Editable(it) }
    }
    override suspend fun discardDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope) {
        val row = (rows[reference.id] as DraftSnapshot.Editable).draft
        require(row.context == context && row.reference == reference && row.scope == scope)
        rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), null)
    }
    private fun lock(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String) {
        when (val row = rows[reference.id]) {
            is DraftSnapshot.Editable -> {
                require(row.draft.context == context && row.draft.reference == reference && row.draft.scope == scope)
                rows[reference.id] = DraftSnapshot.Submitted(reference, operationId)
            }

            is DraftSnapshot.Submitted -> require(row.reference == reference && row.operationId == operationId)

            is DraftSnapshot.Closed -> require(row.operationId == operationId)

            else -> error("Missing test draft")
        }
    }
    override suspend fun createAccountFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateAccount): WriteReceipt {
        lock(context, reference, scope, operationId)
        return ledger.createAccount(context, operationId, value).also { rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), operationId) }
    }
    override suspend fun updateTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: UpdateTransaction): WriteReceipt {
        lock(context, reference, scope, operationId)
        return ledger.updateTransaction(context, operationId, value).also { rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), operationId) }
    }

    override suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction): WriteReceipt {
        lock(context, reference, scope, operationId)
        return ledger.createTransaction(context, operationId, value).also { rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), operationId) }
    }
}
