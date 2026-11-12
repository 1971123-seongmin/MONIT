package com.monit.feature.transactions.entry

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.model.DraftSlot
import com.monit.core.model.LedgerContext
import com.monit.feature.transactions.navigation.RegisteredAccountResultEffect
import com.monit.feature.transactions.navigation.TransactionEntryDestination

@Composable
fun TransactionEntryRoute(
    context: LedgerContext,
    destination: TransactionEntryDestination,
    entry: NavBackStackEntry,
    onRegisterAccount: (String, String, DraftSlot) -> Unit,
    onClose: () -> Unit,
    viewModel: TransactionEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var exit by rememberSaveable { mutableStateOf(false) }
    var returnProblem by rememberSaveable { mutableStateOf(false) }

    SideEffect {
        viewModel.bind(
            context,
            destination,
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flushDraft() }

    BackHandler {
        if (state.slot != null && state.phase == EntryPhase.EDITING) viewModel.dismissPicker()
        else if (state.viewing) onClose() else exit = true
    }

    state.selectionRequestId?.let { request ->
        RegisteredAccountResultEffect(
            entry,
            request,
            viewModel::receive,
            {
                viewModel.dismissPicker()

                returnProblem = true
            },
        )
    }

    val persistence = state.draftPersistence

    if (persistence != null && persistence.phase != DraftEditorPhase.EDITING) {
        TransactionDraftRecovery(
            persistence,
            viewModel::matchesDraft,
            viewModel::newDraft,
            viewModel::restoreDraft,
            viewModel::discardDraft,
            viewModel::reloadDrafts,
            onClose,
        )
    } else TransactionEntryScreen(
        state = state,
        onEdit = viewModel::edit,
        onChoose = viewModel::choose,
        onCandidate = viewModel::selectCandidate,
        onApply = viewModel::applyCandidate,
        onDismissPicker = viewModel::dismissPicker,
        onAddAccount = { viewModel.openRegistration(onRegisterAccount) },
        onSubmit = viewModel::submit,
        onCheck = { viewModel.check() },
        onRetrySave = { viewModel.check(allowWrite = true) },
        onReload = viewModel::reload,
        onDone = { viewModel.finish(onClose) },
        onBack = { if (state.viewing) onClose() else exit = true },
        onBeginEdit = viewModel::beginEdit,
        onDelete = { deleteConfirmation = true },
        onFlush = viewModel::flushDraft,
    )

    if (deleteConfirmation) MonitDialog(
        onDismissRequest = { deleteConfirmation = false },
        title = { Text("거래를 삭제할까요?") },
        actions = {
            MonitButton(
                "취소",
                onClick = { deleteConfirmation = false },
            )

            MonitButton(
                "삭제 확인",
                onClick = {
                    deleteConfirmation = false
                    viewModel.delete()
                },
            )
        },
    ) {
        Text("거래가 달력·목록·월 합계와 해당 계좌 잔액 계산에서 제외됩니다. 이체라면 양쪽 계좌에 함께 반영돼요. 원본과 변경 이력은 보존하며 납입 기록과는 연동하지 않습니다.")
    }

    if (returnProblem) MonitDialog(
        onDismissRequest = { returnProblem = false },
        title = {
            Text("계좌 반환을 확인해 주세요")
        },
        actions = {
            MonitButton(
                "입력 내용 확인",
                onClick = { returnProblem = false },
            )
        },
    ) {
        Text("원래 선택 요청과 맞지 않아 계좌를 적용하지 않았어요. 기존 입력을 확인하고 계좌를 다시 선택해 주세요.")
    }

    if (exit) MonitDialog(
        onDismissRequest = { exit = false },
        title = {
            Text("거래 입력에서 나갈까요?")
        },
        actions = {
            MonitButton(
                "계속 입력하기",
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
            else "보관한 미제출 초안은 다음에 직접 복구할 수 있어요. 이미 제출한 기록은 취소되지 않아요.",
        )
    }
}
