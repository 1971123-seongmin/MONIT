package com.monit.feature.home.payments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.PaymentAction

@Composable
fun PaymentsScreen(
    state: PaymentsState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onMonth: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onRegister: () -> Unit,
    onRule: (String, Boolean) -> Unit,
    onNewPayment: () -> Unit,
    onChooseExisting: () -> Unit,
    onTransaction: (String) -> Unit,
    onEditTransaction: () -> Unit,
    onAction: (PaymentAction) -> Unit,
    onEdit: (String, String) -> Unit,
    onPicker: (String?) -> Unit,
    onReview: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onRetrySubmit: () -> Unit,
    onRelease: () -> Unit,
    onFinish: () -> Unit,
    onRecover: (String) -> Unit,
    onFlush: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val ui = state.ui
    val enabled = state.phase == PaymentsPhase.READY

    if (state.picker != null && enabled) {
        PaymentAccountPicker(
            state,
            onEdit,
            { onPicker(null) },
        )
        return
    }

    if (state.phase == PaymentsPhase.LOADING || state.phase == PaymentsPhase.BLOCKED) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            PaymentTopBar(
                "월별 납입",
                onBack,
            )

            MonitStatusPanel(
                if (state.phase == PaymentsPhase.LOADING) "불러오는 중" else "납입을 확인하지 못했어요",
                "기존 거래·납입·초안은 그대로 보존됩니다.",
                error = state.phase == PaymentsPhase.BLOCKED,
                action = {
                    if (state.phase == PaymentsPhase.BLOCKED) MonitButton(
                        "다시 확인",
                        onClick = onReload,
                    )
                },
            )
        }
        return
    }

    when (state.mode) {
        PaymentsMode.MONTH -> LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            item(
                "title",
                "header",
            ) {
                PaymentTopBar(
                    "월별 납입",
                    onBack,
                )
            }

            item(
                "month",
                "month",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
                ) {
                    MonitButton(
                        "이전 월",
                        Modifier.weight(1f),
                        type = MonitButtonType.Secondary,
                        onClick = { onMonth(-1) },
                    )

                    MonitButton(
                        "다음 월",
                        Modifier.weight(1f),
                        type = MonitButtonType.Secondary,
                        onClick = { onMonth(1) },
                    )
                }

                Text(
                    ui.monthLabel,
                    style = MaterialTheme.typography.titleLarge,
                )

                Text(ui.summary)

                Text("수동 확인 기록이에요. 은행 거래·송금을 자동 확인하지 않아요.")
            }

            item(
                "register",
                "action",
            ) {
                MonitButton(
                    "반복 납입 등록",
                    Modifier.fillMaxWidth(),
                    onClick = onRegister,
                )
            }

            items(
                ui.requests,
                key = { "request:${it.id}" },
                contentType = { "request" },
            ) { request ->
                MonitListItem(
                    headline = {
                        Text(request.title)
                    },
                    supportingContent = {
                        Text("원래 요청의 결과를 확인하거나 명시 해제할 수 있어요.")
                    },
                    onClick = { onRecover(request.id) },
                )
            }

            if (ui.items.isEmpty()) item(
                "empty",
                "status",
            ) {
                MonitStatusPanel(
                    "예정된 납입이 없어요",
                    "반복 납입의 시작·종료 월과 선택한 월을 확인해 주세요.",
                )
            }

            items(
                ui.items,
                key = { "payment:${it.id}" },
                contentType = { "payment" },
            ) { item ->
                MonitListItem(
                    modifier = Modifier.testTag("payment-row:${item.id}"),
                    headline = {
                        Text(item.name)
                    },
                    supportingContent = {
                        Text(item.schedule)
                    },
                    detailContent = {
                        Text(item.status)
                    },
                    onClick = { onSelect(item.id) },
                )
            }

            if (ui.rules.isNotEmpty()) item(
                "rules-title",
                "header",
            ) {
                Text(
                    "반복 규칙",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(
                ui.rules,
                key = { "rule:${it.id}" },
                contentType = { "rule" },
            ) { rule ->
                MonitCard(Modifier.fillMaxWidth()) {
                    Text(
                        rule.name,
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Text(rule.summary)

                    MonitButton(
                        "${rule.name} 규칙 변경",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        onClick = { onRule(rule.id, false) },
                    )

                    MonitButton(
                        "${rule.name} 일정 종료",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        onClick = { onRule(rule.id, true) },
                    )
                }
            }
        }

        PaymentsMode.DETAIL -> PaymentContent(
            ui.selected?.name?.let { "$it 납입 확인" } ?: "납입 확인",
            onBack,
        ) {
            ui.selected?.let { item ->
                PaymentStatus(item)

                Text("새 납입 내용을 입력하거나\n이미 기록한 거래를 연결해 주세요.\n은행 거래를 자동 확인하지 않아요.")

                if (item.hasTransaction) {
                    item.confirmed?.let { Text(it) }

                    MonitButton(
                        "연결 거래 확인·수정",
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = onEditTransaction,
                    )

                    if (item.needsReconfirmation) MonitButton(
                        "연결 거래 재확인",
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = onChooseExisting,
                    )

                    MonitButton(
                        "완료 연결 해제",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        enabled = enabled,
                        onClick = { onAction(PaymentAction.UNLINK) },
                    )
                } else if (item.skipped && item.active) MonitButton(
                    "건너뜀 해제",
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                    onClick = { onAction(PaymentAction.UNSKIP) },
                ) else if (item.active) {
                    MonitButton(
                        "새 납입 내용 입력",
                        Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = onNewPayment,
                    )

                    MonitButton(
                        "기존 거래 연결",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        enabled = enabled,
                        onClick = onChooseExisting,
                    )

                    MonitButton(
                        "이번 달 건너뛰기",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        enabled = enabled,
                        onClick = { onAction(PaymentAction.SKIP) },
                    )
                }
            }
        }

        PaymentsMode.CHOOSE_TRANSACTION -> LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            item(
                "title",
                "header",
            ) {
                PaymentTopBar(
                    "기존 거래 선택",
                    onBack,
                )
            }
            item(
                "help",
                "text",
            ) {
                Text("선택만으로 연결하지 않아요. 실제 날짜·금액·계좌와 전액 여부를 검토한 뒤 최종 확인해 주세요.")
            }
            items(
                ui.transactions,
                key = { it.id },
                contentType = { "transaction" },
            ) { tx ->
                MonitListItem(
                    headline = {
                        Text(tx.headline)
                    },
                    supportingContent = {
                        Text(tx.detail)
                    },
                    onClick = { onTransaction(tx.id) },
                )
            }
            if (ui.transactions.isEmpty()) item(
                "empty",
                "status",
            ) {
                Text("연결할 거래가 없어요. 새 납입 내용을 직접 입력해 주세요.")
            }
        }

        PaymentsMode.RULE_FORM, PaymentsMode.TRANSACTION_FORM -> PaymentForm(
            state,
            onBack,
            onEdit,
            onPicker,
            onReview,
            onFlush,
        )

        PaymentsMode.REVIEW -> PaymentContent(
            ui.review.title,
            onBack,
        ) {
            Text(
                ui.review.schedule,
                color = MonitTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )

            MonitListItem(
                headline = {
                    Text(ui.review.transaction)
                },
                supportingContent = {
                    Text(ui.review.detail)
                },
            )

            Text(ui.review.explanation)

            MonitButton(
                ui.review.action,
                Modifier.fillMaxWidth(),
                enabled = enabled,
                loading = state.phase == PaymentsPhase.SAVING,
                onClick = onSubmit,
            )

            MonitButton(
                "납입 내용 수정",
                Modifier.fillMaxWidth(),
                type = MonitButtonType.Secondary,
                enabled = enabled,
                onClick = onBack,
            )

            MonitButton(
                "취소",
                Modifier.fillMaxWidth(),
                type = MonitButtonType.Secondary,
                enabled = enabled,
                onClick = onCancel,
            )

            Text(
                "아직 저장 전이에요. 취소하면 새 거래와 납입 완료를 남기지 않아요.",
                color = MonitTheme.colors.muted,
            )
        }

        PaymentsMode.RESULT -> PaymentContent(
            "납입 결과 확인",
            onBack,
        ) {
            MonitListItem(
                headline = {
                    Text(ui.review.transaction)
                },
                supportingContent = {
                    Text(ui.review.detail)
                },
            )

            when (state.phase) {
                PaymentsPhase.SAVED -> {
                    MonitStatusPanel(
                        if (ui.form.values["mode"] == "CONFIRM" && ui.form.values["full"] == "false") "거래만 보존했어요 · 납입은 미완료예요" else "확인한 요청을 저장했어요",
                        "같은 요청을 다시 저장하지 않아요. 실제 거래 월의 금액과 회차의 대상 월은 구분합니다.",
                    )

                    MonitButton(
                        "결과 확인 마치기",
                        Modifier.fillMaxWidth(),
                        loading = state.finishing,
                        onClick = onFinish,
                    )
                }

                PaymentsPhase.REJECTED -> {
                    MonitStatusPanel(
                        "이 요청은 반영되지 않았어요",
                        "검토한 내용이 다른 변경으로 바뀌었어요. 원문·초안·충돌 결과는 보존됩니다. 확인 후 최신 내용으로 다시 시작해 주세요.",
                        error = true,
                    )

                    MonitButton(
                        "납입 충돌 결과 확인 마치기",
                        Modifier.fillMaxWidth(),
                        loading = state.finishing,
                        onClick = onFinish,
                    )
                }

                PaymentsPhase.UNCONFIRMED -> {
                    Text("저장 결과를 확인해 주세요. 결과를 조회하기 전에는 실패나 완료로 단정하지 않아요.")

                    MonitButton(
                        "원래 요청 결과 확인",
                        Modifier.fillMaxWidth(),
                        onClick = onCheck,
                    )

                    if (state.resultAbsent) {
                        Text("원래 요청의 확정 결과가 없음을 확인했어요. 같은 내용으로 재시도하거나 요청을 명시 해제하고 입력을 확인할 수 있어요.")

                        MonitButton(
                            "같은 요청 다시 시도",
                            Modifier.fillMaxWidth(),
                            onClick = onRetrySubmit,
                        )

                        MonitButton(
                            "요청 해제하고 입력 확인",
                            Modifier.fillMaxWidth(),
                            type = MonitButtonType.Secondary,
                            onClick = onRelease,
                        )
                    }
                }

                else -> Text("원래 요청의 결과를 확인하고 있어요.")
            }
        }
    }

    ui.message?.let { message ->
        MonitDialog(
            onDismissRequest = onDismissMessage,
            title = {
                Text("입력·결과 확인")
            },
            actions = {
                MonitButton(
                    "확인",
                    onClick = onDismissMessage,
                )
            },
        ) {
            Text(message)
        }
    }
}

@Composable
internal fun PaymentTopBar(
    title: String,
    onBack: () -> Unit,
) {
    MonitTopBar(
        title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                )
            }
        },
    )
}

@Composable
internal fun PaymentStatus(value: MonthlyPaymentUiState) {
    MonitListItem(
        headline = {
            Text(value.name)
        },
        supportingContent = {
            Text(value.schedule)
        },
        detailContent = {
            Text(value.status)
        },
    )
}

@Composable
internal fun PaymentContent(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
    ) {
        PaymentTopBar(
            title,
            onBack,
        )

        content()
    }
}
