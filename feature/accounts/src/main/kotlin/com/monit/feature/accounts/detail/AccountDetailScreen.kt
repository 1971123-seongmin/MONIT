package com.monit.feature.accounts.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme

@Composable
fun AccountDetailScreen(
    state: AccountDetailState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onMonthChange: (Long) -> Unit,
    onChange: (String, String) -> Unit,
    onPreview: () -> Unit,
    onBackToEdit: () -> Unit,
    onConfirm: () -> Unit,
    onCheckResult: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onTransactions: (() -> Unit)? = null,
) {
    val ui = state.ui
    val busy = state.phase == AccountDetailPhase.SAVING || state.phase == AccountDetailPhase.REVIEWING

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("account-detail-scroll")
            .imePadding()
            .padding(horizontal = MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
        contentPadding = PaddingValues(bottom = MonitTheme.spacing.content),
    ) {
        item("title") {
            MonitTopBar(
                title = when (state.phase) {
                    AccountDetailPhase.EDITING -> "기준 잔액 확인"
                    AccountDetailPhase.PREVIEW -> "기준액 영향 검토"
                    else -> ui.title + " 상세"
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !busy,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "계좌 목록으로",
                        )
                    }
                },
            )
        }

        when (state.phase) {
            AccountDetailPhase.LOADING, AccountDetailPhase.REVIEWING, AccountDetailPhase.SAVING -> item("busy") {
                MonitStatusPanel(
                    title = if (state.phase == AccountDetailPhase.REVIEWING) "변경 영향 확인 중" else "확인 중",
                    description = "결과를 확인할 때까지 기다려 주세요.",
                )
            }

            AccountDetailPhase.FAILED, AccountDetailPhase.BLOCKED -> item("failed") {
                MonitStatusPanel(
                    title = "계좌를 확인하지 못했어요",
                    description = "기존 금융 기록은 그대로 보존됩니다.",
                    error = true,
                    action = {
                        if (state.phase == AccountDetailPhase.FAILED) MonitButton(
                            text = "다시 불러오기",
                            onClick = onRetry,
                        )
                    },
                )
            }

            AccountDetailPhase.READY -> {
                item("balance") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text("${ui.asOf} 기준 · KRW 장부 잔액")

                        Text(
                            text = ui.balance,
                            style = MaterialTheme.typography.titleLarge,
                        )

                        Text(ui.inclusion)

                        Text("은행 확인 잔액·시장 평가액·수익률이 아니에요.")
                    }
                }

                item("anchor") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text("기준일 ${ui.anchorDate} 마감액")

                        Text(ui.anchorAmount)

                        Text("기준일 다음 날 이후 변동 ${ui.balanceChange}\n기준일 당일·이전 거래는 다시 더하지 않아요.")

                        Text("미확인과 확인한 0원은 달라요. 기준액을 몰라도 거래·수동 납입을 기록할 수 있어요.")

                        MonitButton(
                            text = "기준액 확인·변경",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onEdit,
                        )

                        if (onTransactions != null) MonitButton(
                            text = "거래 기록",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onTransactions,
                        )

                        if (onTransactions != null) Text("거래 탭에서 계좌 필터로 이 계좌를 선택할 수 있어요.")
                    }
                }

                item("flows") {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
                            MonitButton(
                                text = "이전 달",
                                onClick = { onMonthChange(-1) },
                            )

                            MonitButton(
                                text = "다음 달",
                                onClick = { onMonthChange(1) },
                            )
                        }

                        Text("${ui.month} 저축·투자 측 이체 현황")

                        Text("납입 ${ui.deposits}")

                        Text("인출 ${ui.withdrawals}")

                        Text("순납입 ${ui.net}")

                        Text("일반 계좌와 저축·투자 계좌 사이 이체 기준 · 이 계좌가 저축·투자 측인 실제 거래를 집계해요. 당시 계좌 성격을 사용하며 재배치·보험·직접 수입/지출은 제외해요.")
                    }
                }

                item("history-title") {
                    Text("기준액 확인·변경 이력")

                    if (ui.history.isEmpty()) Text("아직 확인된 기준액 이력이 없어요.")
                }

                items(
                    items = ui.history,
                    key = { it.id },
                    contentType = { "anchor-history" },
                ) {
                    MonitCard(Modifier.fillMaxWidth()) {
                        Text(it.description)
                    }
                }
            }

            AccountDetailPhase.EDITING -> item("form") {
                Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
                    MonitTextField(
                        value = ui.anchorDate,
                        onValueChange = {},
                        label = "저장된 기준일 · 변경 불가",
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                    )

                    MonitTextField(
                        value = ui.form.amount,
                        onValueChange = { onChange(it, ui.form.reason) },
                        label = "기준일 마감액 (원)",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = "음수와 확인한 0원도 입력할 수 있어요. ±999,999,999,999원 이내",
                    )

                    Text("현재 은행 잔액이 아닌, 저장된 기준일의 실제 마감액을 입력해 주세요. 0원·음수도 직접 확인한 경우에만 입력해요.")

                    MonitTextField(
                        value = ui.form.reason,
                        onValueChange = { onChange(ui.form.amount, it) },
                        label = "확인·변경 사유",
                        supportingText = if (ui.anchorConfirmed) "기존 기준액 변경 시 사유를 꼭 입력해 주세요." else "최초 확인 사유는 선택 입력이에요.",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                    )

                    Text(if (state.draft.failed) "초안 보관을 확인하지 못했어요. 닫기 전에 다시 확인해 주세요." else "마지막으로 저장된 초안만 다시 열 수 있어요.")

                    MonitButton(
                        text = "변경 영향 확인",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.draft.saving,
                        onClick = onPreview,
                    )

                    MonitButton(
                        text = "모르겠어요 · 확인하지 않고 돌아가기",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBack,
                    )
                }
            }

            AccountDetailPhase.PREVIEW -> item("preview") {
                Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
                    Text("최종 확인 전 변경 영향")

                    Text("계좌: ${ui.title}\n기준일: ${ui.anchorDate} 마감 · 변경 불가\n이전 기준액: ${ui.anchorAmount}\n확인할 기준액: ${ui.preview.amount}\n사유: ${ui.preview.reason}")

                    Text("${ui.asOf} 장부 잔액\n${ui.preview.before} → ${ui.preview.after}")

                    Text("확인된 활성 KRW 계좌 소계\n${ui.preview.subtotalBefore} → ${ui.preview.subtotalAfter}\n포함 ${ui.preview.inclusionBefore}개 → ${ui.preview.inclusionAfter}개 · 미확인/보관 계좌 제외")

                    Text("마감일 당일 거래는 기준액에 포함되어 다시 더하지 않아요. 이후 실제 거래만 더해요. 거래·수입/지출·월별 납입액은 바뀌지 않고, 장부 잔액과 포함 소계에만 반영돼요.")

                    Text("다른 변경이 있으면 저장 시 다시 검증해요. 아래에서 확정해야 기준액이 저장되며, 이전 값·사유·확인 시각은 이력에 남아요.")

                    MonitButton(
                        text = "이 기준일 마감액을 확인하고 확정",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onConfirm,
                    )

                    MonitButton(
                        text = "입력으로 돌아가기",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBackToEdit,
                    )

                    MonitButton(
                        text = "취소",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBack,
                    )
                }
            }

            AccountDetailPhase.RESULT -> item("result") {
                MonitStatusPanel(
                    title = "기준액 저장 완료",
                    description = "저장 결과가 확인됐어요. 이전 기준액과 확인 사유는 이력에서 볼 수 있어요.",
                    action = {
                        MonitButton(
                            text = "결과 확인 마치기",
                            onClick = onFinish,
                        )
                    },
                )
            }

            AccountDetailPhase.REJECTED -> item("rejected") {
                MonitStatusPanel(
                    title = "다른 기준액 변경이 있어요",
                    description = "이 요청은 반영되지 않았어요. 원문과 초안은 보존됩니다. 최신 기준액을 확인한 뒤 새로 검토해 주세요.",
                    error = true,
                    action = {
                        MonitButton(
                            text = "충돌 결과 확인 마치기",
                            onClick = onFinish,
                        )
                    },
                )
            }

            AccountDetailPhase.UNCERTAIN -> item("uncertain") {
                MonitStatusPanel(
                    title = "저장 결과 확인 필요",
                    description = "성공이나 실패를 아직 판단할 수 없어요. 원래 요청의 결과를 먼저 조회하고, 결과가 없을 때만 같은 요청을 다시 시도해요.",
                    action = {
                        MonitButton(
                            text = "원래 결과 확인·같은 요청 재시도",
                            onClick = onCheckResult,
                        )
                    },
                )
            }
        }

        if (ui.message.isNotBlank()) item("message") {
            Text(
                text = ui.message,
                color = MonitTheme.colors.error,
            )
        }
    }
}
