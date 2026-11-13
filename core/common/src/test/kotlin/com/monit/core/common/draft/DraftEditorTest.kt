package com.monit.core.common.draft

import com.monit.core.model.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftEditorTest {
    private val context = LedgerContext(id(1), id(2))
    private val scope = DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS)
    private fun content(name: String = "") = DraftContent.Account(name, AccountNature.GENERAL, "날짜 원문", " 미확인 ")
    private fun TestScope.editor(repo: MemoryDrafts, initial: DraftContent = content(), onRestore: (DraftContent) -> Unit = {}) = DraftEditor(repo, context, scope, this, initial, {}, onRestore)

    /** 가상 시간 499ms에는 쓰지 않고 마지막 편집 후 500ms에 원문 하나만 commit한다. */
    @Test fun debounceWaitsForLatestInputAndAcknowledgesOnlyCommit() = runTest {
        val repo = MemoryDrafts()
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        editor.changed(content("첫 입력"))
        advanceTimeBy(400)
        editor.changed(content("  최신 입력  "))
        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, repo.writes)
        assertNull(editor.state.value.committed)
        assertTrue(editor.state.value.dirty)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, repo.writes)
        assertEquals(content("  최신 입력  "), editor.state.value.committed?.content)
        assertEquals(1, editor.state.value.committed?.reference?.revision)
        assertFalse(editor.state.value.dirty)
    }

    /**
     * 목적: autosave 진행 중 새 편집이 기존 DB 작업을 취소하거나 최신 저장으로 오인되지 않는다.
     * 조건: 첫 저장을 중단 가능한 gate에 고정한다.
     * 절차: 500ms 저장 진입 → 두 번째 편집 → 첫 응답 → 두 번째 debounce 완료.
     * 기대: 첫 commit은 dirty, 두 번째 commit만 최신 입력 확인이며 취소되지 않는다.
     */
    @Test fun editDuringSaveKeepsCommittedRevisionAndSerializesLatest() = runTest {
        val repo = MemoryDrafts()
        val gate = CompletableDeferred<Unit>()
        repo.beforeSave = { gate.await() }
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        editor.changed(content("첫 입력"))
        advanceTimeBy(500)
        runCurrent()
        assertTrue(editor.state.value.saving)
        editor.changed(content("둘째 입력"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(content("첫 입력"), editor.state.value.committed?.content)
        assertTrue(editor.state.value.dirty)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(content("둘째 입력"), editor.state.value.committed?.content)
        assertEquals(2, editor.state.value.committed?.reference?.revision)
        assertFalse(editor.state.value.dirty)
    }

    /** 강제 flush는 debounce를 기다리지 않으며 저장 실패가 마지막 확인 revision을 올리지 않는다. */
    @Test fun failedFlushPreservesLastCommitAndRetriesLatestRawInput() = runTest {
        val repo = MemoryDrafts()
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        editor.changed(content("보관본"))
        val first = assertNotNull(editor.flush(force = true))
        repo.fail = true
        editor.changed(content("저장 실패 원문"))
        assertNull(editor.flush(force = true))
        assertEquals(first, editor.state.value.committed)
        assertTrue(editor.state.value.dirty && editor.state.value.failed)
        repo.fail = false
        assertEquals(content("저장 실패 원문"), editor.flush()?.content)
        assertEquals(2, editor.state.value.committed?.reference?.revision)
    }

    /** 선택 목록의 내용은 명시 복구 전 입력에 적용되지 않으며, 다른 호출 문맥은 적용하지 않는다. */
    @Test fun recoveryIsExplicitAndCallerScoped() = runTest {
        val repo = MemoryDrafts()
        val matching = repo.saveDraft(context, id(3), 0, scope, content("복구 원문"))
        val foreign = repo.saveDraft(context, id(4), 0, DraftScope(DraftKind.ACCOUNT, DraftOrigin.TRANSACTION_ENTRY, id(8), id(9), DraftSlot.CREDIT), content("다른 폼"))
        val restored = mutableListOf<DraftContent>()
        val editor = editor(repo, onRestore = restored::add)
        editor.start(null)
        runCurrent()
        assertEquals(DraftEditorPhase.CHOOSING, editor.state.value.phase)
        assertTrue(restored.isEmpty())
        editor.restore(foreign)
        runCurrent()
        assertTrue(restored.isEmpty())
        editor.restore(matching)
        runCurrent()
        assertEquals(listOf<DraftContent>(content("복구 원문")), restored)
        assertEquals(matching, editor.state.value.committed)
    }

    /** 새 입력 선택은 기존 초안 두 개를 보존하고 별도 ID로 저장한다. */
    @Test fun freshDraftNeverDeletesOtherChoices() = runTest {
        val repo = MemoryDrafts()
        val first = repo.saveDraft(context, id(3), 0, scope, content("기존"))
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        editor.fresh()
        val second = assertNotNull(editor.flush(force = true))
        assertNotEquals(first.reference.id, second.reference.id)
        assertEquals(2, repo.rows.size)
        assertEquals(DraftSnapshot.Editable(first), repo.rows[first.reference.id])
    }

    /** 폐기한 초안의 debounce/추가 변경은 fence를 넘어 새 쓰기를 만들지 않는다. */
    @Test fun discardCancelsDelayedSaveAndClosesEditing() = runTest {
        val repo = MemoryDrafts()
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        editor.changed(content("폐기할 입력"))
        assertTrue(editor.discardCurrent())
        val writes = repo.writes
        editor.changed(content("늦은 입력"))
        editor.requestFlush()
        advanceUntilIdle()
        assertEquals(writes, repo.writes)
        assertEquals(DraftEditorPhase.CLOSED, editor.state.value.phase)
        assertTrue(repo.rows.values.single() is DraftSnapshot.Closed)
    }

    /** 취소된 실제 flush는 예외를 전파하고 새 commit을 확인한 것으로 표시하지 않는다. */
    @Test fun cancelledFlushDoesNotAcknowledgeUncommittedInput() = runTest {
        val repo = MemoryDrafts()
        val gate = CompletableDeferred<Unit>()
        val editor = editor(repo)
        editor.start(null)
        runCurrent()
        repo.beforeSave = { gate.await() }
        editor.changed(content("취소 입력"))
        val job = launch { editor.flush(force = true) }
        runCurrent()
        job.cancel()
        job.join()
        assertNull(editor.state.value.committed)
        assertTrue(editor.state.value.failed && editor.state.value.dirty)
        assertEquals(0, repo.writes)
        gate.complete(Unit)
    }

    /** 소비된 저장 ID는 새 빈 입력으로 교체하지 않고 읽기 전용 종료 상태로 남는다. */
    @Test fun closedSavedIdCannotCreateReplacementDraft() = runTest {
        val repo = MemoryDrafts()
        repo.rows[id(3)] = DraftSnapshot.Closed(DraftReference(id(3), 2), id(5))
        val editor = editor(repo)
        editor.start(id(3))
        runCurrent()
        editor.fresh()
        editor.changed(content("새 입력"))
        assertNull(editor.flush(force = true))
        assertEquals(0, repo.writes)
        assertEquals(DraftEditorPhase.CLOSED, editor.state.value.phase)
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}

private class MemoryDrafts : DraftRepository {
    val rows = linkedMapOf<String, DraftSnapshot>()
    var fail = false
    var writes = 0
    var beforeSave: suspend () -> Unit = {}
    override suspend fun drafts(context: LedgerContext) = rows.values.filter { it is DraftSnapshot.Editable || it is DraftSnapshot.Submitted }
    override suspend fun draft(context: LedgerContext, id: String) = rows[id] ?: DraftSnapshot.Missing
    override suspend fun saveDraft(context: LedgerContext, id: String, expectedRevision: Long, scope: DraftScope, content: DraftContent): FormDraft {
        beforeSave()
        if (fail) throw java.io.IOException("Synthetic failure")
        val existing = (rows[id] as? DraftSnapshot.Editable)?.draft
        require(rows[id] == null || existing != null)
        require((existing?.reference?.revision ?: 0) == expectedRevision)
        writes++
        return FormDraft(context, DraftReference(id, expectedRevision + 1), scope, content).also { rows[id] = DraftSnapshot.Editable(it) }
    }
    override suspend fun discardDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope) {
        require((rows[reference.id] as DraftSnapshot.Editable).draft.reference == reference)
        rows[reference.id] = DraftSnapshot.Closed(reference.copy(revision = reference.revision + 1), null)
    }
    override suspend fun createAccountFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateAccount): WriteReceipt = error("금융 writer는 data 시험의 책임")
    override suspend fun updateTransactionFromDraft(context: com.monit.core.model.LedgerContext, reference: com.monit.core.model.DraftReference, scope: com.monit.core.model.DraftScope, operationId: String, value: com.monit.core.model.UpdateTransaction): com.monit.core.model.WriteReceipt = error("Unexpected update")

    override suspend fun createTransactionFromDraft(context: LedgerContext, reference: DraftReference, scope: DraftScope, operationId: String, value: CreateTransaction): WriteReceipt = error("금융 writer는 data 시험의 책임")
}
