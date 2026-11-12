package com.monit.feature.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.AccountNature
import com.monit.core.model.LedgerContext

@Composable
fun AccountRegistrationScreen(
    state: AccountRegistrationState,
    onAction: (AccountRegistrationAction) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val draft = state.uiState.draft
    val nameFocus = remember {
        FocusRequester()
    }
    val dateFocus = remember {
        FocusRequester()
    }
    val nameBring = remember {
        BringIntoViewRequester()
    }
    val dateBring = remember {
        BringIntoViewRequester()
    }

    LaunchedEffect(state.validationAttempt) {
        when (state.inputError) {
            AccountInputError.NAME -> {
                nameFocus.requestFocus()

                nameBring.bringIntoView()
            }

            AccountInputError.ANCHOR_DATE -> {
                dateFocus.requestFocus()

                dateBring.bringIntoView()
            }

            else -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
    ) {
        MonitTopBar(
            "계좌 추가",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로",
                    )
                }
            },
        )

        MonitTextField(
            draft.name,
            { onAction(AccountRegistrationAction.NameChanged(it)) },
            Modifier
                .fillMaxWidth()
                .focusRequester(nameFocus)
                .bringIntoViewRequester(nameBring),
            label = "계좌 이름",
            enabled = state.editable,
            errorMessage = if (state.inputError == AccountInputError.NAME) "계좌 이름을 확인해 주세요." else null,
        )

        MonitTextField(
            draft.anchorDate,
            { onAction(AccountRegistrationAction.AnchorDateChanged(it)) },
            Modifier
                .fillMaxWidth()
                .focusRequester(dateFocus)
                .bringIntoViewRequester(dateBring),
            label = "기준일",
            placeholder = {
                Text("YYYY-MM-DD")
            },
            enabled = state.editable,
            errorMessage = if (state.inputError == AccountInputError.ANCHOR_DATE) "1900년부터 어제까지의 날짜를 입력해 주세요." else null,
        )

        MonitCard(Modifier.fillMaxWidth()) {
            Text(
                draft.name.ifBlank { "새 계좌" } + " · " + when (draft.nature) {
                    AccountNature.GENERAL -> "일반"
                    AccountNature.SAVINGS -> "저축"
                    AccountNature.INVESTMENT -> "투자"
                },
                style = MaterialTheme.typography.labelLarge,
            )

            Text(
                "잔액 미확인",
                style = MaterialTheme.typography.bodyLarge,
            )

            Text(
                "현재 잔액 합계에서 제외",
                color = MonitTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Text(
            "잔액을 몰라도 거래를 기록할 수 있어요.\n미확인 잔액은 0원으로 계산하지 않아요.",
            color = MonitTheme.colors.muted,
        )

        when (state.phase) {
            AccountSavePhase.EDITING -> MonitButton(
                "계좌 저장",
                Modifier.fillMaxWidth(),
                onClick = { onAction(AccountRegistrationAction.Submit) },
            )

            AccountSavePhase.INITIALIZING, AccountSavePhase.SAVING, AccountSavePhase.CHECKING_RESULT ->
                MonitStatusPanel(
                    if (state.phase == AccountSavePhase.SAVING) "저장 중" else "결과 확인 중",
                    "같은 등록을 다시 저장하지 않고 확인하고 있어요.",
                )

            AccountSavePhase.RETRY_AVAILABLE -> {
                if (state.saveState == AccountSaveState.InitializationFailed) {
                    MonitButton(
                        "다시 확인",
                        onClick = { onAction(AccountRegistrationAction.RetryInitialization) },
                    )
                } else {
                    MonitStatusPanel(
                        "저장 여부를 확인해 주세요",
                        "결과가 불명확해요. 기존 제출의 결과부터 확인해 주세요.",
                    )

                    MonitButton(
                        "저장 결과 확인",
                        Modifier.fillMaxWidth(),
                        onClick = { onAction(AccountRegistrationAction.CheckResult) },
                    )

                    if (state.resultAbsent) MonitButton(
                        "같은 등록 다시 시도",
                        Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        onClick = { onAction(AccountRegistrationAction.RetrySave) },
                    )
                }
            }

            AccountSavePhase.SAVED -> {
                MonitStatusPanel(
                    "계좌 저장 완료",
                    "확인된 등록 결과예요. 거래 계좌로는 돌아간 뒤 직접 적용해 주세요.",
                )

                if (state.readPhase == AccountReadPhase.FAILED) MonitButton(
                    "계좌 다시 불러오기",
                    onClick = { onAction(AccountRegistrationAction.ReloadSavedAccount) },
                )

                MonitButton(
                    "등록 확인",
                    Modifier.fillMaxWidth(),
                    loading = state.finishing,
                    onClick = onDone,
                )
            }

            AccountSavePhase.UNAVAILABLE -> MonitStatusPanel(
                "등록을 진행할 수 없어요",
                "장부 또는 이전 제출을 확인해 주세요. 기존 기록은 보존됩니다.",
                error = true,
            )
        }

        if (state.completionError) Text(
            "결과 확인을 마치지 못했어요. 기록은 보존됩니다.",
            color = MonitTheme.colors.error,
        )

        MonitButton(
            "취소",
            Modifier.fillMaxWidth(),
            type = MonitButtonType.Secondary,
            onClick = onBack,
        )
    }
}
