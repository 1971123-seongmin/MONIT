package com.monit.feature.accounts.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme

@Composable
fun ImportPreviewScreen(
    phase: ImportPreviewPhase,
    ui: ImportPreviewUiState,
    onSelectFile: () -> Unit,
    onCheckRevision: () -> Unit,
    onReviewAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    problem: String? = null,
    checking: Boolean = false,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
    ) {
        item("title") {
            MonitTopBar(
                title = "이관 미리보기",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "계좌로 돌아가기",
                        )
                    }
                },
            )
        }

        item("boundary") {
            Text("원본과 앱 기록을 변경하지 않는 검토 화면이에요. 실제 반영 0건")
        }

        item("file") {
            MonitButton(
                text = "검토 파일 선택",
                modifier = Modifier.fillMaxWidth(),
                enabled = phase != ImportPreviewPhase.LOADING && phase != ImportPreviewPhase.BLOCKED,
                onClick = onSelectFile,
            )
        }

        when (phase) {
            ImportPreviewPhase.EMPTY -> item("empty") {
                Text("정의된 검토 JSON 파일을 선택해 주세요. 일반 Notion CSV나 웹 페이지는 직접 읽지 않아요.")
            }

            ImportPreviewPhase.LOADING -> item("loading") {
                MonitStatusPanel(
                    title = "파일과 장부를 확인 중이에요",
                    description = "읽기와 비교만 진행해요. 금융 기록은 저장하지 않아요.",
                )
            }

            ImportPreviewPhase.FAILED, ImportPreviewPhase.BLOCKED -> item("error") {
                MonitStatusPanel(
                    title = "미리보기를 만들지 못했어요",
                    description = problem ?: "입력 파일과 접근 권한을 확인해 주세요.",
                    error = true,
                )

                Text("전체 원본 행 수: 확인 불가 · 금융 반영 0건")
            }

            ImportPreviewPhase.READY, ImportPreviewPhase.STALE -> {
                if (phase == ImportPreviewPhase.STALE) item("stale") {
                    MonitStatusPanel(
                        title = "원본·매핑·장부 재검토가 필요해요",
                        description = problem ?: "아래는 이전 기준의 결과예요. 현재 파일과 장부로 다시 검토해 주세요.",
                        error = true,
                    )

                    MonitButton(
                        text = "현재 장부로 다시 검토",
                        enabled = !checking,
                        onClick = onReviewAgain,
                    )
                }

                item("scope") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text(ui.sourceSummary)

                        Text(ui.completeness)

                        Text(ui.comparison)

                        Text(ui.reconciliation)
                    }
                }

                item("counts") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text("읽은 원본 행 ${ui.sourceRowCount}")

                        Text("반영예정 후보 ${ui.candidateCount} · 기반영 ${ui.existingCount}")

                        Text("보류 ${ui.heldCount} · 제외 ${ui.excludedCount}")

                        Text("예상 거래 ${ui.transactionCount} · 실제 생성 0건")

                        Text("후보는 신규 확정이나 반영 승인이 아니에요. 유사 중복은 보류로 표시해요.")
                    }
                }

                items(
                    items = ui.totals,
                    key = { "total:${it.key}" },
                ) { total ->
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text(total.title)

                        Text(total.value)
                    }
                }

                item("revision") {
                    Text(ui.evidence)

                    MonitButton(
                        text = if (checking) "장부 기준 확인 중" else "장부 변경 여부 확인",
                        enabled = !checking,
                        type = MonitButtonType.Secondary,
                        onClick = onCheckRevision,
                    )
                }

                items(
                    items = ui.rows,
                    key = { "row:${it.key}" },
                ) { row ->
                    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }

                    MonitCard(Modifier.fillMaxWidth()) {
                        Text(row.title)

                        Text(row.decision)

                        Text(row.detail)

                        MonitButton(
                            text = if (expanded) "원문 근거 접기" else "원문 근거 보기",
                            type = MonitButtonType.Secondary,
                            onClick = { expanded = !expanded },
                        )

                        if (expanded) {
                            Text(row.evidence)

                            Text(row.rawExcerpt)
                        }
                    }
                }
            }
        }
    }
}
