package com.monit.core.common.draft

import com.monit.core.model.FormDraft

enum class DraftEditorPhase { LOADING, CHOOSING, EDITING, CLOSED, FAILED }

data class DraftEditorState(
    val phase: DraftEditorPhase = DraftEditorPhase.LOADING,
    val choices: List<FormDraft> = emptyList(),
    val committed: FormDraft? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val frozen: Boolean = false,
)
