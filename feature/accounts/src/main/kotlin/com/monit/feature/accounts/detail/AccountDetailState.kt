package com.monit.feature.accounts.detail

import com.monit.core.common.draft.DraftEditorState

enum class AccountDetailPhase { LOADING, READY, FAILED, EDITING, REVIEWING, PREVIEW, SAVING, RESULT, UNCERTAIN, REJECTED, BLOCKED }
data class AccountDetailState(val phase: AccountDetailPhase = AccountDetailPhase.LOADING, val ui: AccountDetailUiState = AccountDetailUiState(), val draft: DraftEditorState = DraftEditorState())
