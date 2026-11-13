package com.monit.feature.accounts.detail

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.model.LedgerContext
import com.monit.feature.accounts.AccountDraftRecovery

@Composable
fun AccountDetailRoute(
    context: LedgerContext,
    accountId: String,
    entryId: String,
    onClose: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
    onTransactions: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askClose by remember { mutableStateOf(false) }
    val busy = state.phase == AccountDetailPhase.SAVING || state.phase == AccountDetailPhase.REVIEWING
    val requestClose: () -> Unit = {
        if (!busy) {
            if (state.phase == AccountDetailPhase.EDITING || state.phase == AccountDetailPhase.PREVIEW) askClose = true else onClose()
        }
    }

    SideEffect { viewModel.bind(context, accountId, entryId) }

    LifecycleResumeEffect(
        context,
        accountId,
        entryId,
    ) {
        viewModel.reload()

        onPauseOrDispose { viewModel.flush() }
    }

    BackHandler(onBack = requestClose)

    if (state.phase == AccountDetailPhase.EDITING && state.draft.phase != DraftEditorPhase.EDITING) {
        AccountDraftRecovery(
            state = state.draft,
            matches = viewModel::matchesDraft,
            onNew = viewModel::newDraft,
            onRestore = viewModel::restore,
            onDiscard = viewModel::discardChoice,
            onRetry = viewModel::retryDraft,
            onClose = requestClose,
        )
    } else AccountDetailScreen(
        state = state,
        onBack = requestClose,
        onRetry = viewModel::reload,
        onEdit = viewModel::edit,
        onMonthChange = viewModel::moveMonth,
        onChange = viewModel::change,
        onPreview = viewModel::preview,
        onBackToEdit = viewModel::backToEdit,
        onConfirm = viewModel::confirm,
        onCheckResult = viewModel::checkResult,
        onFinish = viewModel::finish,
        onTransactions = onTransactions,
    )

    if (askClose) MonitDialog(
        onDismissRequest = { if (!busy) askClose = false },
        title = { Text("기준액 입력을 닫을까요?") },
        actions = {
            MonitButton(
                text = "계속 입력",
                enabled = !busy,
                onClick = { askClose = false },
            )

            MonitButton(
                text = "초안 보관하고 닫기",
                enabled = !busy,
                onClick = { viewModel.keepDraftAndClose(onClose) },
            )
        },
    ) {
        Text("확정하지 않은 기준액은 장부에 반영되지 않아요. 저장된 초안은 다음 입력에서 직접 선택해 복구할 수 있어요.")
    }
}
