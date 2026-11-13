package com.monit.feature.home.payments

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.DraftContent
import com.monit.core.model.FormDraft
import com.monit.core.model.LedgerContext

@Composable
fun PaymentsRoute(
    context: LedgerContext,
    entryId: String,
    onClose: () -> Unit,
    onEditTransaction: (String) -> Unit,
    viewModel: PaymentsViewModel = hiltViewModel(),
    initialMonth: String? = null,
    initialItem: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SideEffect { viewModel.bind(context, entryId, initialMonth, initialItem) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }

    LifecycleResumeEffect(context) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    BackHandler { viewModel.back(onClose) }

    val persistence = state.draft

    if (persistence != null && persistence.phase != DraftEditorPhase.EDITING && state.phase == PaymentsPhase.READY) {
        PaymentDraftRecovery(
            persistence.phase,
            persistence.choices,
            viewModel::matchesDraft,
            viewModel::freshDraft,
            viewModel::restoreDraft,
            viewModel::discardChoice,
            viewModel::retryDrafts,
            { viewModel.leaveForm(false) },
        )
    } else PaymentsScreen(
        state = state,
        onBack = { viewModel.back(onClose) },
        onReload = viewModel::reload,
        onMonth = viewModel::changeMonth,
        onSelect = viewModel::selectItem,
        onRegister = viewModel::registerRule,
        onRule = viewModel::changeRule,
        onNewPayment = viewModel::newPayment,
        onChooseExisting = viewModel::chooseExisting,
        onTransaction = viewModel::useTransaction,
        onEditTransaction = { viewModel.linkedTransactionId()?.let(onEditTransaction) },
        onAction = viewModel::itemAction,
        onEdit = viewModel::edit,
        onPicker = viewModel::picker,
        onReview = viewModel::review,
        onSubmit = viewModel::submit,
        onCancel = { viewModel.leaveForm(false) },
        onCheck = { viewModel.check() },
        onRetrySubmit = { viewModel.check(true) },
        onRelease = viewModel::releaseForEditing,
        onFinish = viewModel::finish,
        onRecover = viewModel::recover,
        onFlush = viewModel::flush,
        onDismissMessage = viewModel::dismissMessage,
    )

    if (state.showExit) MonitDialog(
        onDismissRequest = viewModel::keepEditing,
        title = {
            Text("납입 입력에서 나갈까요?")
        },
        actions = {
            MonitButton(
                "계속 입력하기",
                onClick = viewModel::keepEditing,
            )

            MonitButton(
                "보관하고 목록으로",
                onClick = { viewModel.leaveForm(false) },
            )
            if (persistence?.phase == DraftEditorPhase.EDITING && !persistence.frozen) MonitButton(
                "초안 폐기하고 목록으로",
                type = MonitButtonType.Secondary,
                onClick = { viewModel.leaveForm(true) },
            )
        },
    ) {
        Text("보관한 원문 초안은 직접 복구할 수 있어요. 이미 제출한 요청이나 금융 기록을 취소하지 않아요.")
    }
}

@Composable
private fun PaymentDraftRecovery(
    phase: DraftEditorPhase,
    choices: List<FormDraft>,
    matches: (FormDraft) -> Boolean,
    onNew: () -> Unit,
    onRestore: (FormDraft) -> Unit,
    onDiscard: (FormDraft) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    var discard by remember { mutableStateOf<FormDraft?>(null) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        item(
            "title",
            "header",
        ) {
            Text("미제출 납입 초안 확인")
        }
        if (phase == DraftEditorPhase.CHOOSING) {
            item(
                "new",
                "action",
            ) {
                MonitButton(
                    "새 입력 시작",
                    onClick = onNew,
                )
            }
            items(
                choices,
                key = { it.reference.id },
                contentType = { "draft" },
            ) { draft ->
                MonitCard(Modifier.fillMaxWidth()) {
                    val fields = (draft.content as? DraftContent.Payment)?.fields
                    Text(fields?.get("name") ?: fields?.get("plannedName") ?: "월별 납입 초안")

                    Text("확인된 저장본 ${draft.reference.revision}")

                    if (!matches(draft)) Text("다른 규칙·회차 또는 revision의 초안이에요. 원래 입력에서 확인해 주세요.")
                    MonitButton(
                        "이 초안 복구",
                        enabled = matches(draft),
                        onClick = { onRestore(draft) },
                    )

                    MonitButton(
                        "이 초안 폐기",
                        type = MonitButtonType.Secondary,
                        onClick = { discard = draft },
                    )
                }
            }
        } else item(
            "status",
            "status",
        ) {
            Text(if (phase == DraftEditorPhase.LOADING) "보관한 초안을 확인하고 있어요." else "이 초안은 제출됐거나 현재 복구할 수 없어요. 원본과 결과는 보존됩니다.")

            if (phase == DraftEditorPhase.FAILED) MonitButton(
                "초안 다시 확인",
                onClick = onReload,
            )
        }
        item(
            "close",
            "action",
        ) {
            MonitButton(
                "월별 납입 목록으로",
                onClick = onClose,
            )
        }
    }

    discard?.let { draft ->
        MonitDialog(
            onDismissRequest = { discard = null },
            title = {
                Text("이 초안을 폐기할까요?")
            },
            actions = {
                MonitButton(
                    "유지",
                    onClick = { discard = null },
                )

                MonitButton("초안 폐기 확인", onClick = {
                    discard = null
                    onDiscard(draft)
                })
            },
        ) {
            Text("선택한 미제출 입력만 폐기합니다. 금융 기록·다른 초안은 바꾸지 않아요.")
        }
    }
}
