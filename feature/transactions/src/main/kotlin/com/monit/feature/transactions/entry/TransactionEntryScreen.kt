package com.monit.feature.transactions.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.TransactionKind
import java.util.Locale

@Composable
fun TransactionEntryScreen(
    state: TransactionEntryState,
    onEdit: (TransactionEntryUiState.() -> TransactionEntryUiState) -> Unit,
    onChoose: (AccountSlot) -> Unit,
    onCandidate: (String) -> Unit,
    onApply: () -> Unit,
    onDismissPicker: () -> Unit,
    onAddAccount: () -> Unit,
    onSubmit: () -> Unit,
    onCheck: () -> Unit,
    onRetrySave: () -> Unit,
    onReload: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onFlush: () -> Unit = {},
    onBeginEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val ui = state.ui
    val editable = !state.viewing && state.phase == EntryPhase.EDITING && (state.draftPersistence == null || state.draftPersistence.phase == DraftEditorPhase.EDITING) && state.draftPersistence?.frozen != true
    val kinds = listOf(
        TransactionKind.EXPENSE,
        TransactionKind.INCOME,
        TransactionKind.TRANSFER,
    )
    val focus = remember {
        TransactionInputError.entries.associateWith {
            FocusRequester()
        }
    }
    val bring = remember {
        TransactionInputError.entries.associateWith {
            BringIntoViewRequester()
        }
    }

    LaunchedEffect(state.validationAttempt) {
        state.inputError?.let { error ->
            focus.getValue(error).requestFocus()

            bring.getValue(error).bringIntoView()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .onFocusChanged { if (!it.hasFocus) onFlush() }
            .verticalScroll(rememberScrollState())
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
    ) {
        MonitTopBar(
            if (state.phase == EntryPhase.SAVED) "처리 완료" else if (state.existing) "거래 상세·수정" else "거래 기록",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로",
                    )
                }
            },
        )

        state.draftPersistence?.takeIf { !it.frozen }?.let { persistence ->
            Text(persistence.committed?.let { "확인된 초안 저장본 ${it.reference.revision}" } ?: "아직 보관한 초안이 없어요")

            if (persistence.dirty || persistence.saving) Text("최근 입력 저장 대기")

            if (persistence.failed) {
                Text(if (persistence.committed == null) "입력한 내용을 아직 보관하지 못했어요." else "최근 입력을 저장하지 못했어요. 마지막 저장본은 보존됩니다.")

                MonitButton(
                    text = "초안 저장 다시 시도",
                    onClick = onFlush,
                )
            }
        }

        if (state.phase in listOf(
                EntryPhase.EDITING,
                EntryPhase.LOADING,
                EntryPhase.BLOCKED,
            )
        ) {
            MonitSegmentedControl(
                listOf(
                    "지출",
                    "수입",
                    "이체",
                ),
                kinds.indexOf(ui.kind),
                { index -> onEdit { copy(kind = kinds[index]) } },
                Modifier.fillMaxWidth(),
                enabled = editable,
            )

            MonitTextField(
                ui.amount,
                { value -> onEdit { copy(amount = value) } },
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus.getValue(TransactionInputError.AMOUNT))
                    .bringIntoViewRequester(bring.getValue(TransactionInputError.AMOUNT))
                    .onFocusChanged { if (!it.isFocused) onFlush() },
                label = "금액 (원)",
                errorMessage = state.inputError?.takeIf { it == TransactionInputError.AMOUNT }?.message,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = editable,
            )

            MonitTextField(
                ui.date,
                { value -> onEdit { copy(date = value) } },
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus.getValue(TransactionInputError.DATE))
                    .bringIntoViewRequester(bring.getValue(TransactionInputError.DATE))
                    .onFocusChanged { if (!it.isFocused) onFlush() },
                label = "거래일",
                errorMessage = state.inputError?.takeIf { it == TransactionInputError.DATE }?.message,
                placeholder = {
                    Text("YYYY-MM-DD")
                },
                enabled = editable,
            )

            if (ui.kind != TransactionKind.INCOME) EntryAccountField(
                if (ui.kind == TransactionKind.TRANSFER) "보내는 계좌" else "출금 계좌",
                ui.debitAccountId,
                ui.accounts,
                editable,
                Modifier
                    .focusRequester(focus.getValue(TransactionInputError.DEBIT_ACCOUNT))
                    .bringIntoViewRequester(bring.getValue(TransactionInputError.DEBIT_ACCOUNT)),
            ) { onChoose(AccountSlot.DEBIT) }

            if (ui.kind != TransactionKind.EXPENSE) EntryAccountField(
                "받는 계좌",
                ui.creditAccountId,
                ui.accounts,
                editable,
                Modifier
                    .focusRequester(focus.getValue(TransactionInputError.CREDIT_ACCOUNT))
                    .bringIntoViewRequester(bring.getValue(TransactionInputError.CREDIT_ACCOUNT)),
            ) { onChoose(AccountSlot.CREDIT) }

            MonitTextField(
                ui.memo,
                { value -> onEdit { copy(memo = value) } },
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus.getValue(TransactionInputError.MEMO))
                    .bringIntoViewRequester(bring.getValue(TransactionInputError.MEMO))
                    .onFocusChanged { if (!it.isFocused) onFlush() },
                label = "메모 (선택)",
                enabled = editable,
                errorMessage = state.inputError?.takeIf { it == TransactionInputError.MEMO }?.message,
            )

            if (ui.kind == TransactionKind.TRANSFER) Text(
                "내부 이체는 수입·지출 합계에 포함되지 않아요.",
                color = MonitTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        } else if (state.phase != EntryPhase.SAVED) EntrySummary(ui)

        ui.error?.let {
            Text(
                it,
                color = MonitTheme.colors.error,
            )
        }

        when (state.phase) {
            EntryPhase.EDITING -> {
                if (state.viewing) {
                    MonitButton(
                        "수정하기",
                        Modifier.fillMaxWidth(),
                        onClick = onBeginEdit,
                    )

                    MonitButton(
                        "거래 삭제",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        onClick = onDelete,
                    )
                } else MonitButton(
                    "저장",
                    Modifier.fillMaxWidth(),
                    enabled = editable,
                    onClick = onSubmit,
                )
            }

            EntryPhase.LOADING -> MonitStatusPanel(
                "불러오는 중",
                "사용할 수 있는 계좌를 확인하고 있어요.",
            )

            EntryPhase.SAVING -> MonitStatusPanel(
                "저장 중",
                "제출한 거래를 처리하고 있어요.",
            )

            EntryPhase.CHECKING -> MonitStatusPanel(
                "결과 확인 중",
                "이전 제출의 저장 결과만 조회하고 있어요.",
            )

            EntryPhase.UNCONFIRMED -> {
                MonitStatusPanel(
                    "저장 여부를 확인해 주세요",
                    "새 거래로 제출하지 않고 이전 결과를 먼저 확인해요.",
                )

                MonitButton(
                    "결과 다시 확인",
                    Modifier.fillMaxWidth(),
                    onClick = onCheck,
                )

                if (state.resultAbsent) MonitButton(
                    "내용 확인 후 다시 저장",
                    Modifier.fillMaxWidth(),
                    type = MonitButtonType.Secondary,
                    onClick = onRetrySave,
                )
            }

            EntryPhase.REJECTED -> MonitStatusPanel(
                "이 요청은 반영되지 않았어요",
                "다른 변경이 있어 수정·삭제하지 않았습니다. 입력 초안과 원본은 보존됩니다. 확인 후 목록에서 최신 거래를 다시 선택해 주세요.",
                error = true,
                action = {
                    MonitButton(
                        "충돌 결과 확인 마치기",
                        loading = state.finishing,
                        onClick = onDone,
                    )
                },
            )

            EntryPhase.SAVED -> {
                MonitStatusPanel(
                    if (state.deleting) "거래를 삭제했어요" else "기록을 저장했어요",
                    if (state.deleting) "목록·합계에서 제외했으며 원본과 변경 이력은 보존했어요." else "확인된 거래를 목록에 반영했어요.",
                    Modifier.fillMaxWidth(),
                    action = {
                        MonitButton(
                            "목록 보기",
                            Modifier.fillMaxWidth(),
                            loading = state.finishing,
                            onClick = onDone,
                        )
                    },
                )

                EntrySummary(ui)
            }

            EntryPhase.BLOCKED -> MonitStatusPanel(
                "입력을 진행할 수 없어요",
                "장부와 이전 제출을 확인해 주세요. 기존 기록은 보존됩니다.",
                error = true,
                action = {
                    if (state.canReload) MonitButton(
                        "다시 불러오기",
                        onClick = onReload,
                    )
                },
            )
        }
    }

    if (state.slot != null) EntryAccountPicker(
        state,
        onCandidate,
        onApply,
        onAddAccount,
        onDismissPicker,
        onReload,
    )
}

@Composable
private fun EntryAccountField(
    label: String,
    id: String?,
    accounts: List<EntryAccountUiState>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChoose: () -> Unit,
) {
    MonitSelectableCard(
        false,
        onChoose,
        modifier,
        enabled = enabled,
        role = Role.Button,
        border = BorderStroke(
            MonitTheme.sizes.outline,
            MonitTheme.colors.outline,
        ),
        contentPadding = PaddingValues(
            horizontal = MonitTheme.spacing.content,
            vertical = MonitTheme.spacing.inline,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MonitTheme.colors.muted,
        )

        Text(accounts.find { it.id == id }?.name ?: if (id == null) "계좌 선택" else "계좌 다시 확인")
    }
}

@Composable
private fun EntrySummary(ui: TransactionEntryUiState) {
    val amount = remember(
        ui.amount,
        ui.kind,
    ) {
        val prefix = when (ui.kind) {
            TransactionKind.INCOME -> "+"
            TransactionKind.EXPENSE -> "−"
            TransactionKind.TRANSFER -> ""
        }

        ui.amount.toLongOrNull()?.let {
            prefix + String.format(
                Locale.KOREA,
                "%,d원",
                it,
            )
        } ?: ui.amount
    }
    val accounts = listOfNotNull(
        ui.accounts.find { it.id == ui.debitAccountId }?.name.takeIf { ui.kind != TransactionKind.INCOME },
        ui.accounts.find { it.id == ui.creditAccountId }?.name.takeIf { ui.kind != TransactionKind.EXPENSE },
    ).joinToString(" → ")

    MonitCard(Modifier.fillMaxWidth()) {
        Text(
            when (ui.kind) {
                TransactionKind.INCOME -> "수입"
                TransactionKind.EXPENSE -> "지출"
                TransactionKind.TRANSFER -> "이체"
            } + if (ui.memo.isBlank()) "" else " · ${ui.memo}",
            style = MaterialTheme.typography.labelLarge,
        )

        Text(amount)

        Text(
            "${accounts.ifBlank { "계좌 정보 확인 필요" }} · ${ui.date}",
            style = MaterialTheme.typography.labelSmall,
            color = MonitTheme.colors.muted,
        )
    }
}

@Composable
private fun EntryAccountPicker(
    state: TransactionEntryState,
    onCandidate: (String) -> Unit,
    onApply: () -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    val ready = state.phase == EntryPhase.EDITING

    MonitBottomSheet(
        onDismissRequest = onDismiss,
        dismissEnabled = ready || state.canReload,
        title = {
            Text(
                "계좌 선택",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            MonitButton(
                "이 계좌 사용",
                Modifier.fillMaxWidth(),
                enabled = ready && state.ui.candidateAccountId != null,
                onClick = onApply,
            )

            MonitButton(
                "새 계좌 추가",
                Modifier.fillMaxWidth(),
                enabled = ready,
                type = MonitButtonType.Secondary,
                onClick = onAddAccount,
            )

            MonitButton(
                "닫기",
                Modifier.fillMaxWidth(),
                enabled = ready || state.canReload,
                type = MonitButtonType.Secondary,
                onClick = onDismiss,
            )
        },
    ) {
        Text("사용할 계좌를 선택해 주세요.")

        if (state.phase == EntryPhase.BLOCKED) {
            Text("계좌를 불러오지 못했어요. 입력은 그대로 보존됩니다.")

            if (state.canReload) MonitButton(
                "계좌 다시 불러오기",
                onClick = onReload,
            )
        } else if (!ready) Text("계좌를 확인하고 있어요.")
        else if (state.ui.accounts.isEmpty()) Text("등록된 활성 계좌가 없어요. 새 계좌를 추가해 주세요.")

        state.ui.accounts.forEach { account ->
            val opposite = if (state.slot == AccountSlot.DEBIT) state.ui.creditAccountId else state.ui.debitAccountId
            val allowed = state.ui.kind != TransactionKind.TRANSFER || account.id != opposite

            MonitSelectableCard(
                state.ui.candidateAccountId == account.id,
                { onCandidate(account.id) },
                enabled = ready && allowed,
            ) {
                Text(account.name)

                if (!allowed) Text("이체 반대편 계좌와 같아요.")
            }
        }

        state.ui.error?.let {
            Text(
                it,
                color = MonitTheme.colors.error,
            )
        }
    }
}
