package com.monit.app.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.designsystem.component.MonitSelectableCard
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.LedgerContext
import com.monit.core.model.PendingRecoveryItem
import com.monit.core.model.SubmissionRecoveryItem

@Composable
fun EntryRecoveryGate(
    context: LedgerContext,
    onClose: () -> Unit,
    viewModel: EntryRecoveryViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SideEffect { viewModel.bind(context) }

    if (state.ready) content()
    else {
        BackHandler(onBack = onClose)

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
        ) {
            item("title") {
                Text("이전 저장 결과 확인")
            }

            item("explanation") {
                Text("확인할 제출이 남아 있어요. 한 건을 선택해 결과를 확인해 주세요. 새 입력은 확인을 마친 뒤 시작해요.")
            }

            itemsIndexed(state.items) { index, item ->
                MonitSelectableCard(
                    state.selected == index,
                    { viewModel.select(index) },
                    enabled = !state.loading && item !is SubmissionRecoveryItem.Blocked,
                ) {
                    Text("${index + 1}. ${state.ui.rows[index].title}")

                    Text(state.ui.rows[index].detail)
                }
            }

            item("status") {
                when {
                    state.loading -> MonitStatusPanel(
                        "확인 중",
                        "기존 제출과 저장 결과를 확인하고 있어요.",
                    )

                    state.failed -> MonitStatusPanel(
                        "확인하지 못했어요",
                        "기존 기록은 그대로 보존됩니다.",
                        error = true,
                        action = {
                            MonitButton(
                                "결과 다시 확인",
                                onClick = { state.selected?.let(viewModel::select) ?: viewModel.reload() },
                            )
                        },
                    )

                    state.paymentRejection != null -> {
                        Text("검토한 납입 내용이 변경되어 이 요청은 반영되지 않았습니다. 원문과 초안은 보존됩니다. 확인 후 최신 내용으로 다시 시작해 주세요.")
                        MonitButton("납입 충돌 결과 확인 마치기", onClick = viewModel::finish)
                    }

                    state.rejection != null -> {
                        Text("다른 변경이 있어 이 요청은 반영되지 않았습니다. 원본·입력 초안·충돌 결과는 보존됩니다. 확인 후 최신 거래를 다시 선택해 주세요.")
                        MonitButton("충돌 결과 확인 마치기", onClick = viewModel::finish)
                    }

                    state.receipt != null -> {
                        Text("저장 완료가 확인됐어요. 확인을 마쳐도 금융 기록은 그대로 보존됩니다.")

                        MonitButton(
                            "이 결과 확인 마치기",
                            onClick = viewModel::finish,
                        )
                    }

                    state.resultAbsent && (state.items.getOrNull(state.selected ?: -1) is SubmissionRecoveryItem.Pending || state.items.getOrNull(state.selected ?: -1) is SubmissionRecoveryItem.LegacyPending) -> {
                        Text("이 제출의 저장 결과가 아직 없어요. 선택한 제출만 같은 내용으로 다시 시도할 수 있어요.")

                        MonitButton(
                            "선택한 내용 확인 후 다시 저장",
                            onClick = viewModel::retry,
                        )
                    }
                }
            }

            item("close") {
                MonitButton(
                    "기존 목록으로",
                    onClick = onClose,
                )
            }
        }
    }
}
