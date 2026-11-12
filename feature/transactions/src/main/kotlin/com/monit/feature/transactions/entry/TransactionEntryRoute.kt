package com.monit.feature.transactions.entry

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.model.LedgerContext
import com.monit.feature.transactions.navigation.RegisteredAccountResultEffect
import com.monit.feature.transactions.navigation.TransactionEntryDestination

@Composable
fun TransactionEntryRoute(
    context: LedgerContext,
    destination: TransactionEntryDestination,
    entry: NavBackStackEntry,
    onRegisterAccount: (String) -> Unit,
    onClose: () -> Unit,
    viewModel: TransactionEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var exit by rememberSaveable { mutableStateOf(false) }
    var returnProblem by rememberSaveable { mutableStateOf(false) }

    SideEffect {
        viewModel.bind(
            context,
            destination,
        )
    }

    BackHandler {
        if (state.slot != null && state.phase == EntryPhase.EDITING) viewModel.dismissPicker()
        else exit = true
    }

    state.selectionRequestId?.let { request ->
        RegisteredAccountResultEffect(
            entry,
            request,
            viewModel::receive,
            {
                viewModel.dismissPicker()

                returnProblem = true
            },
        )
    }

    TransactionEntryScreen(
        state = state,
        onEdit = viewModel::edit,
        onChoose = viewModel::choose,
        onCandidate = viewModel::selectCandidate,
        onApply = viewModel::applyCandidate,
        onDismissPicker = viewModel::dismissPicker,
        onAddAccount = { viewModel.beginRegistration()?.let(onRegisterAccount) },
        onSubmit = viewModel::submit,
        onCheck = { viewModel.check() },
        onRetrySave = { viewModel.check(allowWrite = true) },
        onReload = viewModel::reload,
        onDone = { viewModel.finish(onClose) },
        onBack = { exit = true },
    )

    if (returnProblem) MonitDialog(
        onDismissRequest = { returnProblem = false },
        title = {
            Text("계좌 반환을 확인해 주세요")
        },
        actions = {
            MonitButton(
                "입력 내용 확인",
                onClick = { returnProblem = false },
            )
        },
    ) {
        Text("원래 선택 요청과 맞지 않아 계좌를 적용하지 않았어요. 기존 입력을 확인하고 계좌를 다시 선택해 주세요.")
    }

    if (exit) MonitDialog(
        onDismissRequest = { exit = false },
        title = {
            Text("거래 입력에서 나갈까요?")
        },
        actions = {
            MonitButton(
                "계속 입력하기",
                onClick = { exit = false },
            )

            MonitButton(
                "나가기",
                type = MonitButtonType.Secondary,
                onClick = onClose,
            )
        },
    ) {
        Text("아직 제출하지 않은 입력은 사라질 수 있어요. 이미 제출한 기록은 취소되지 않으며 결과를 다시 확인할 수 있어요.")
    }
}
