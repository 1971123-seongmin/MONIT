package com.monit.feature.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.DraftKind
import com.monit.core.model.DraftOrigin
import com.monit.core.model.DraftScope
import com.monit.core.model.LedgerContext

@Composable
fun AccountRegistrationRoute(
    context: LedgerContext,
    onClose: () -> Unit,
    onRegistered: (String) -> Unit,
    viewModel: AccountRegistrationViewModel = hiltViewModel(),
    draftScope: DraftScope? = DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var exit by rememberSaveable { mutableStateOf(false) }

    SideEffect { viewModel.bind(context, draftScope) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushDraft() }

    BackHandler { exit = true }

    val persistence = state.draftPersistence

    if (persistence != null && persistence.phase != DraftEditorPhase.EDITING) {
        AccountDraftRecovery(
            persistence,
            viewModel::matchesDraft,
            viewModel::startNewDraft,
            viewModel::restoreDraft,
            viewModel::discardDraft,
            viewModel::reloadDrafts,
            onClose,
        )
    } else AccountRegistrationScreen(
        state,
        viewModel::onAction,
        { exit = true },
        onDone = { viewModel.finish(onRegistered) },
        onFlush = viewModel::flushDraft,
    )

    if (exit) MonitDialog(
        onDismissRequest = { exit = false },
        title = {
            Text("계좌 등록에서 나갈까요?")
        },
        actions = {
            MonitButton(
                "계속 확인하기",
                onClick = { exit = false },
            )

            MonitButton(
                "보관하고 나가기",
                type = MonitButtonType.Secondary,
                onClick = { viewModel.leave(onFinished = onClose) },
            )

            if (persistence?.phase == DraftEditorPhase.EDITING && !persistence.frozen) MonitButton(
                "초안 폐기하고 나가기",
                type = MonitButtonType.Secondary,
                enabled = !persistence.saving,
                onClick = { viewModel.leave(discard = true, onFinished = onClose) },
            )

            if (persistence?.failed == true) MonitButton(
                text = "저장 없이 나가기",
                onClick = onClose,
            )
        },
    ) {
        Text(
            if (persistence?.failed == true) "최근 입력을 저장하지 못했어요. 저장 없이 나가면 마지막 저장본 이후 입력은 사라질 수 있어요."
            else "보관한 미제출 초안은 다음에 직접 복구할 수 있어요. 제출한 등록은 취소되지 않아요.",
        )
    }
}
