package com.monit.core.common.draft

import com.monit.core.model.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** VM 수명 안에서만 동작한다. 영속 복구의 근거는 Repository의 마지막 commit이다. */
class DraftEditor(
    private val repository: DraftRepository,
    private val context: LedgerContext,
    val scope: DraftScope,
    private val coroutineScope: CoroutineScope,
    private val initial: DraftContent,
    private val onId: (String) -> Unit,
    private val onRestore: (DraftContent) -> Unit,
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DraftEditorState())
    val state = mutable.asStateFlow()
    private var id: String? = null
    private var content = initial
    private var version = 0L
    private var debounce: Job? = null
    private var started = false

    fun start(savedId: String?) {
        if (started) return
        started = true
        coroutineScope.launch { discover(savedId) }
    }

    private suspend fun discover(savedId: String?) {
        try {
            if (savedId != null) {
                when (val snapshot = repository.draft(context, savedId)) {
                    is DraftSnapshot.Closed, is DraftSnapshot.Submitted -> {
                        mutable.value = DraftEditorState(DraftEditorPhase.CLOSED)
                        return
                    }

                    is DraftSnapshot.Blocked -> {
                        mutable.value = DraftEditorState(DraftEditorPhase.FAILED)
                        return
                    }

                    else -> Unit
                }
            }
            val snapshots = repository.drafts(context)
            if (snapshots.any { it is DraftSnapshot.Blocked || it is DraftSnapshot.Submitted }) {
                mutable.value = DraftEditorState(DraftEditorPhase.FAILED)
                return
            }
            val choices = snapshots.filterIsInstance<DraftSnapshot.Editable>().map { it.draft }.filter { it.scope.kind == scope.kind }
            if (choices.isEmpty()) fresh() else mutable.value = DraftEditorState(DraftEditorPhase.CHOOSING, choices)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = DraftEditorState(DraftEditorPhase.FAILED)
        }
    }

    fun reload() {
        if (state.value.phase != DraftEditorPhase.FAILED) return
        mutable.value = DraftEditorState()
        coroutineScope.launch { discover(id) }
    }

    fun fresh() {
        if (state.value.saving) return
        if (state.value.phase != DraftEditorPhase.LOADING && state.value.phase != DraftEditorPhase.CHOOSING) return
        id = UUID.randomUUID().toString()
        content = initial
        version = 0
        mutable.value = DraftEditorState(DraftEditorPhase.EDITING)
        onId(checkNotNull(id))
        onRestore(initial)
    }

    fun restore(choice: FormDraft) {
        if (state.value.phase != DraftEditorPhase.CHOOSING || state.value.saving || choice.context != context || choice.scope != scope) return
        mutable.value = state.value.copy(saving = true, failed = false)
        coroutineScope.launch {
            try {
                val latest = repository.draft(context, choice.reference.id) as? DraftSnapshot.Editable ?: error("Draft not editable")
                require(latest.draft.context == context && latest.draft.scope == scope)
                id = latest.draft.reference.id
                content = latest.draft.content
                version = 0
                mutable.value = DraftEditorState(DraftEditorPhase.EDITING, committed = latest.draft)
                onId(checkNotNull(id))
                onRestore(content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(saving = false, failed = true)
            }
        }
    }

    fun discardChoice(choice: FormDraft) {
        if (state.value.phase != DraftEditorPhase.CHOOSING || state.value.saving || choice.context != context || choice !in state.value.choices) return
        mutable.value = state.value.copy(saving = true, failed = false)
        coroutineScope.launch {
            try {
                repository.discardDraft(context, choice.reference, choice.scope)
                mutable.value = DraftEditorState()
                discover(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(saving = false, failed = true)
            }
        }
    }

    fun changed(value: DraftContent) {
        if (state.value.phase != DraftEditorPhase.EDITING || state.value.frozen || content == value) return
        content = value
        version = Math.addExact(version, 1)
        mutable.value = state.value.copy(dirty = true, failed = false)
        debounce?.cancel()
        debounce = coroutineScope.launch {
            delay(500)
            // 예약 Job만 취소한다. 이미 시작한 저장은 다음 입력으로 취소하지 않는다.
            debounce = null
            flush()
        }
    }

    fun requestFlush() {
        if (state.value.phase == DraftEditorPhase.EDITING && !state.value.frozen) coroutineScope.launch { flush() }
    }

    suspend fun flush(force: Boolean = false): FormDraft? {
        debounce?.cancel()
        debounce = null
        return mutex.withLock {
            val before = state.value
            if (before.phase != DraftEditorPhase.EDITING || before.frozen) return@withLock before.committed
            if (!before.dirty && (!force || before.committed != null)) return@withLock before.committed
            val ownVersion = version
            val ownContent = content
            mutable.value = before.copy(saving = true, failed = false)
            try {
                val saved = repository.saveDraft(context, checkNotNull(id), before.committed?.reference?.revision ?: 0, scope, ownContent)
                require(saved.context == context && saved.scope == scope && saved.reference.id == id && saved.content == ownContent)
                mutable.value = state.value.copy(committed = saved, dirty = version != ownVersion, saving = false, failed = false)
                saved
            } catch (cancelled: CancellationException) {
                mutable.value = state.value.copy(saving = false, failed = true)
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(saving = false, failed = true)
                null
            }
        }
    }

    fun freeze() {
        debounce?.cancel()
        debounce = null
        mutable.value = state.value.copy(frozen = true)
    }

    suspend fun discardCurrent(): Boolean {
        val saved = flush(force = true) ?: return false
        if (state.value.frozen) return false
        return mutex.withLock {
            mutable.value = state.value.copy(saving = true)
            try {
                repository.discardDraft(context, saved.reference, scope)
                freeze()
                mutable.value = state.value.copy(phase = DraftEditorPhase.CLOSED, saving = false, dirty = false)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(saving = false, failed = true)
                false
            }
        }
    }
}
