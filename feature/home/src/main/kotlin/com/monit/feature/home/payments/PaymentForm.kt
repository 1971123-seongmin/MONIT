package com.monit.feature.home.payments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme

@Composable
internal fun PaymentForm(
    state: PaymentsState,
    onBack: () -> Unit,
    onEdit: (String, String) -> Unit,
    onPicker: (String?) -> Unit,
    onReview: () -> Unit,
    onFlush: () -> Unit,
) {
    val form = state.ui.form
    val fields = form.values
    val enabled = state.phase == PaymentsPhase.READY && state.draft?.phase == DraftEditorPhase.EDITING && state.draft.frozen != true
    val rule = state.mode == PaymentsMode.RULE_FORM
    val end = fields["mode"] == "END"
    val existing = fields["financialRevision"]?.isNotEmpty() == true

    PaymentContent(
        form.title,
        onBack,
    ) {
        state.draft?.let { draft ->
            Text(draft.committed?.let { "확인된 초안 저장본 ${it.reference.revision}" } ?: "아직 보관한 초안이 없어요")

            if (draft.saving || draft.dirty) Text("최근 입력 저장 대기")
            if (draft.failed) {
                Text("최근 입력을 보관하지 못했어요. 마지막 저장본은 유지됩니다.")

                MonitButton(
                    "초안 저장 다시 시도",
                    onClick = onFlush,
                )
            }
        }

        if (rule && end) {
            PaymentInput(
                "end",
                "종료 월 (YYYY-MM)",
                fields,
                enabled,
                onEdit,
                onFlush,
            )

            Text("종료 월까지 포함합니다. 이후 미완료는 비활성화하고 거래·완료·건너뜀·재확인 이력은 보존해요.")
        } else {
            if (rule) {
                PaymentInput(
                    "name",
                    "납입 이름",
                    fields,
                    enabled,
                    onEdit,
                    onFlush,
                )

                MonitSegmentedControl(
                    listOf("일반", "보험", "저축", "투자"),
                    listOf("GENERAL", "INSURANCE", "SAVINGS", "INVESTMENT").indexOf(fields["purpose"]),
                    { index -> onEdit("purpose", listOf("GENERAL", "INSURANCE", "SAVINGS", "INVESTMENT")[index]) },
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                )
            } else {
                Text("${fields["plannedName"] ?: "월별 납입"} · 예정 ${fields["plannedAmount"] ?: ""}원 · ${fields["plannedDate"] ?: ""}")

                if (existing) Text("이미 기록한 거래를 복제하지 않아요. 금융값을 바꾸려면 거래 목록에서 해당 기록을 수정해 주세요.")
            }

            PaymentInput(
                "amount",
                if (rule) "예정 금액 (원)" else "실제 금액 (원)",
                fields,
                enabled && !existing,
                onEdit,
                onFlush,
                true,
            )

            if (rule) {
                PaymentInput(
                    "day",
                    "매월 납입일 (1~31)",
                    fields,
                    enabled,
                    onEdit,
                    onFlush,
                    true,
                )

                if (fields["mode"] == "REGISTER") {
                    PaymentInput(
                        "start",
                        "시작 월 (YYYY-MM)",
                        fields,
                        enabled,
                        onEdit,
                        onFlush,
                    )

                    PaymentInput(
                        "end",
                        "종료 월 (선택, YYYY-MM)",
                        fields,
                        enabled,
                        onEdit,
                        onFlush,
                    )
                } else PaymentInput(
                    "effective",
                    "변경 적용 시작 월 (YYYY-MM)",
                    fields,
                    enabled,
                    onEdit,
                    onFlush,
                )

                Text("월에 해당 일이 없으면 말일을 사용해요. 규칙 변경은 다음 달 이후 미래 미완료에만 적용해요.")
            } else PaymentInput(
                "date",
                "실제 납입일 (YYYY-MM-DD)",
                fields,
                enabled && !existing,
                onEdit,
                onFlush,
            )

            PaymentAccountField(
                "출금 계좌",
                fields["debit"],
                state.ui.accounts,
                enabled && !existing,
                { onPicker("debit") },
            )

            if (fields["purpose"] in listOf("SAVINGS", "INVESTMENT") || fields["transactionKind"] == "TRANSFER") PaymentAccountField(
                "지정 입금 계좌",
                fields["credit"],
                state.ui.accounts,
                enabled && rule,
                { onPicker("credit") },
            )

            Text("계좌가 없다면 계좌 탭에서 성격(일반·저축·투자)을 선택해 등록해 주세요.")

            if (!rule) {
                PaymentInput(
                    "memo",
                    "메모 (선택)",
                    fields,
                    enabled && !existing,
                    onEdit,
                    onFlush,
                )

                Text("이번 회차에 낼 금액을 모두 냈나요?")

                MonitSelectableCard(
                    fields["full"] == "true",
                    { onEdit("full", "true") },
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                ) {
                    Text("이번 회차 전액을 냈음을 확인해요")
                }

                MonitSelectableCard(
                    fields["full"] == "false",
                    { onEdit("full", "false") },
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                ) {
                    Text("일부만 기록하고 미완료로 유지해요")
                }

                PaymentInput(
                    "reason",
                    "차이·일부 기록 사유",
                    fields,
                    enabled,
                    onEdit,
                    onFlush,
                )

                Text("예정액과 다르거나 일부만 기록하면 사유가 필요해요. 분할 합산과 잔여 거래 자동 생성은 하지 않아요.")
            }
        }

        MonitButton(
            "납입 내용 검토",
            Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = onReview,
        )

        Text("입력·뒤로 이동은 금융 기록이나 납입 완료를 만들지 않아요. 최종 확인에서만 저장합니다.")
    }
}

@Composable
private fun PaymentInput(
    key: String,
    label: String,
    fields: Map<String, String>,
    enabled: Boolean,
    onEdit: (String, String) -> Unit,
    onFlush: () -> Unit,
    numeric: Boolean = false,
) {
    MonitTextField(
        fields[key] ?: "",
        { onEdit(key, it) },
        Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) onFlush() },
        label = label,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
    )
}

@Composable
private fun PaymentAccountField(
    label: String,
    id: String?,
    accounts: List<PaymentAccountUiState>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MonitButton(
        "$label · ${accounts.find { it.id == id }?.name ?: "계좌 선택"}",
        Modifier.fillMaxWidth(),
        type = MonitButtonType.Secondary,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun PaymentAccountPicker(
    state: PaymentsState,
    onEdit: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val field = state.picker ?: return
    val purpose = state.ui.form.values["purpose"]
    val accounts = remember(
        state.ui.accounts,
        purpose,
        field,
    ) {
        state.ui.accounts.filter {
            when {
                field == "credit" -> it.nature == if (purpose == "SAVINGS") "SAVINGS" else "INVESTMENT"
                purpose in listOf("SAVINGS", "INVESTMENT") -> it.nature == "GENERAL"
                else -> true
            }
        }
    }

    LazyColumn(
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
                "계좌 선택",
                onBack,
            )
        }
        items(
            accounts,
            key = { it.id },
            contentType = { "account" },
        ) { account ->
            MonitListItem(
                headline = {
                    Text(account.name)
                },
                onClick = { onEdit(field, account.id) },
            )
        }
        if (accounts.isEmpty()) item(
            "empty",
            "status",
        ) {
            Text("선택할 활성 계좌가 없어요. 계좌 탭에서 필요한 성격으로 등록해 주세요.")
        }
    }
}
