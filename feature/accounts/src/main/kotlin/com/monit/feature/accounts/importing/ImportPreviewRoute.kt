package com.monit.feature.accounts.importing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.model.*

@Composable
fun ImportPreviewRoute(
    context: LedgerContext,
    onBack: () -> Unit,
    backupContent: @Composable (onBack: () -> Unit, onVerified: (String) -> Unit) -> Unit,
    viewModel: ImportPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showBackup by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::select)
    }

    SideEffect { viewModel.bind(context) }

    if (showBackup) {
        backupContent(
            { showBackup = false },
            { exportId ->
                showBackup = false
                viewModel.backupReturned(exportId)
            },
        )
        return
    }

    LifecycleResumeEffect(context) {
        viewModel.checkRevision()

        onPauseOrDispose {}
    }

    val leave = { if (viewModel.canLeave()) onBack() }

    BackHandler(onBack = leave)

    ImportPreviewScreen(
        phase = state.phase,
        ui = state.ui,
        onSelectFile = { picker.launch(arrayOf("application/json", "text/plain")) },
        onCheckRevision = viewModel::checkRevision,
        onReviewAgain = viewModel::reviewAgain,
        onBack = leave,
        problem = state.problem,
        checking = state.checking,
        controls = {
            when (state.phase) {
                ImportPreviewPhase.READY -> {
                    state.problem?.let { problem -> item("import-problem") { Text(problem) } }

                    importReviewControls(
                        ui = state.ui.workflow,
                        selectedGroupCount = state.selectedGroup.size,
                        onConfirmTarget = viewModel::confirmTarget,
                        onMapAccount = viewModel::mapAccount,
                        onKeepAnchor = viewModel::keepAnchor,
                        onConfirmAnchor = viewModel::confirmAnchor,
                        onAnchorAmount = viewModel::anchorAmount,
                        onAnchorReason = viewModel::anchorReason,
                        onConfirmGroup = viewModel::confirmGroup,
                        onBackup = { if (viewModel.canLeave()) showBackup = true },
                        onApply = { confirmation = state.review?.token },
                    )
                }

                ImportPreviewPhase.LOADING, ImportPreviewPhase.APPLYING -> item("import-cancel") {
                    MonitButton(
                        text = "취소 요청",
                        onClick = viewModel::requestCancel,
                    )
                }

                ImportPreviewPhase.RECOVERY -> {
                    val batches = state.pending.mapNotNull { attempt ->
                        when (attempt) {
                            is ImportAttempt.Requested -> attempt.batchId
                            is ImportAttempt.Committed -> attempt.result.batchId
                            is ImportAttempt.Evidence -> attempt.batchId
                            ImportAttempt.Missing -> null
                        }
                    }

                    items(
                        items = batches,
                        key = { "import-pending:$it" },
                    ) { batch ->
                        MonitButton(
                            text = "보관된 요청 결과 확인 · ${batch.take(8)}",
                            onClick = { viewModel.resolve(batch) },
                        )
                    }

                    item("import-resolve") {
                        MonitButton(
                            text = "현재 요청의 결과 다시 조회",
                            onClick = { viewModel.resolve() },
                        )
                    }

                    item("import-acknowledge") {
                        MonitButton(
                            text = "확인 기록 후 새 검토로",
                            enabled = state.ui.canAcknowledge,
                            onClick = viewModel::acknowledge,
                        )
                    }
                }

                ImportPreviewPhase.RESULT -> item("import-result-acknowledge") {
                    MonitButton(
                        text = "결과 확인 완료",
                        onClick = viewModel::acknowledge,
                    )
                }

                else -> Unit
            }
        },
        onConfirmRow = viewModel::confirmRow,
        onDistinctRow = viewModel::distinctRow,
        onExcludeRow = viewModel::excludeRow,
        onSelectGroup = viewModel::selectGroup,
        selectedGroup = state.selectedGroup,
    )

    if (confirmation != null && confirmation == state.review?.token) MonitDialog(
        onDismissRequest = { confirmation = null },
        title = { Text("검토한 집합을 반영할까요?") },
        actions = {
            MonitButton(
                text = "검토한 집합 반영",
                onClick = {
                    confirmation = null
                    viewModel.apply()
                },
            )

            MonitButton(
                text = "계속 검토",
                onClick = { confirmation = null },
            )
        },
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp / 3)) {
            item("planned") {
                Text("실제 생성 예정 거래 ${state.ui.transactionCount}")
            }

            item("counts") {
                Text("원본 ${state.ui.sourceRowCount} · 반영 후보 ${state.ui.candidateCount}\n기반영 ${state.ui.existingCount} · 보류 ${state.ui.heldCount} · 제외 ${state.ui.excludedCount}")
            }

            itemsIndexed(
                items = state.ui.workflow.basisSummary,
                key = { index, _ -> "basis:$index" },
            ) { _, summary -> Text(summary) }

            item("boundary") {
                Text("계좌마다 선택한 기준 유지·명시 확정도 함께 반영해요. 보류·제외 원문은 보관하며 자동 실행하지 않아요. 파일과 장부가 바뀌면 다시 검토해야 해요.")
            }
        }
    }
}
