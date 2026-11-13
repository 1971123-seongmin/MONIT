package com.monit.feature.transactions.entry

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.common.draft.DraftEditorState
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*

/** 복구·폐기는 사용자 선택으로만 요청하며 다른 호출 문맥의 초안은 적용하지 않는다. */
@Composable
internal fun TransactionDraftRecovery(
    state: DraftEditorState,
    matches: (FormDraft) -> Boolean,
    onNew: () -> Unit,
    onRestore: (FormDraft) -> Unit,
    onDiscard: (FormDraft) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    var discard by remember { mutableStateOf<FormDraft?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
    ) {
        item("title") {
            Text("미제출 초안 확인")
        }

        when (state.phase) {
            DraftEditorPhase.LOADING -> item("loading") {
                Text("보관한 초안을 확인하고 있어요.")
            }

            DraftEditorPhase.CLOSED -> item("closed") {
                Text("이 초안은 이미 제출되었거나 폐기되었어요. 목록으로 돌아가 저장 결과를 확인해 주세요.")
            }

            DraftEditorPhase.FAILED -> item("failure") {
                Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content)) {
                    Text("초안을 확인하지 못했어요. 기존 기록은 보존됩니다. 제출 복구가 남았다면 목록으로 돌아가 먼저 확인해 주세요.")

                    MonitButton(
                        text = "초안 다시 확인",
                        onClick = onRetry,
                    )
                }
            }

            DraftEditorPhase.CHOOSING -> {
                item("explanation") {
                    Text("복구할 초안을 직접 선택해 주세요. 새 입력을 시작해도 기존 초안은 보존돼요.")
                }

                items(
                    items = state.choices,
                    key = { it.reference.id },
                ) { draft ->
                    MonitCard(Modifier.fillMaxWidth()) {
                        val summary = when (val content = draft.content) {
                            is DraftContent.Anchor -> "기준액 확인 초안"
                            is DraftContent.Payment -> "월별 납입 초안"
                            is DraftContent.Account -> content.name.ifBlank { "이름 미입력" } + " · " + content.anchorDate
                            is DraftContent.Transaction -> content.date + " · " + content.amount.ifBlank { "금액 미입력" }
                        }

                        Text(summary)

                        Text("확인된 초안 저장본 ${draft.reference.revision}")

                        if (!matches(draft)) {
                            Text("다른 진입 화면 또는 계좌 선택 요청의 초안이에요. 원래 입력에서 복구해 주세요.")
                        }

                        MonitButton(
                            text = "이 초안 복구",
                            enabled = matches(draft) && !state.saving,
                            onClick = { onRestore(draft) },
                        )

                        MonitButton(
                            text = "이 초안 폐기",
                            enabled = !state.saving,
                            type = MonitButtonType.Secondary,
                            onClick = { discard = draft },
                        )
                    }
                }

                item("new") {
                    Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content)) {
                        if (state.failed) {
                            Text("초안을 처리하지 못했어요. 내용을 변경하지 않았으니 다시 확인해 주세요.")
                        }

                        MonitButton(
                            text = "새 입력 시작",
                            enabled = !state.saving,
                            onClick = onNew,
                        )
                    }
                }
            }

            DraftEditorPhase.EDITING -> Unit
        }

        item("close") {
            MonitButton(
                text = "돌아가기",
                type = MonitButtonType.Secondary,
                onClick = onClose,
            )
        }
    }

    discard?.let { draft ->
        MonitDialog(
            onDismissRequest = { discard = null },
            title = {
                Text("이 미제출 초안을 폐기할까요?")
            },
            actions = {
                MonitButton(
                    text = "보관하기",
                    onClick = { discard = null },
                )

                MonitButton(
                    text = "초안 폐기",
                    type = MonitButtonType.Secondary,
                    onClick = {
                        discard = null
                        onDiscard(draft)
                    },
                )
            },
        ) {
            Text("이 초안만 폐기해요. 제출한 기록과 다른 초안은 보존됩니다.")
        }
    }
}

@Preview(name = "초안 명시 복구", widthDp = 360, heightDp = 708)
@Preview(name = "초안 복구 큰 글꼴", widthDp = 320, heightDp = 708, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DraftRecoveryPreview() {
    val context = LedgerContext("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
    val choice = FormDraft(context, DraftReference("00000000-0000-0000-0000-000000000003", 2), DraftScope(DraftKind.TRANSACTION, DraftOrigin.HOME), DraftContent.Transaction(TransactionKind.EXPENSE, "20000", "2026-11-02", null, null, "합성 초안"))

    MonitTheme {
        Surface(
            color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface,
        ) {
            TransactionDraftRecovery(
                state = DraftEditorState(DraftEditorPhase.CHOOSING, listOf(choice)),
                matches = { true },
                onNew = {},
                onRestore = {},
                onDiscard = {},
                onRetry = {},
                onClose = {},
            )
        }
    }
}
