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
import com.monit.core.model.LedgerContext

@Composable
fun AccountRegistrationRoute(
    context: LedgerContext,
    onClose: () -> Unit,
    onRegistered: (String) -> Unit,
    viewModel: AccountRegistrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var exit by rememberSaveable { mutableStateOf(false) }

    SideEffect { viewModel.bind(context) }

    BackHandler { exit = true }

    AccountRegistrationScreen(
        state,
        viewModel::onAction,
        { exit = true },
    ) {
        viewModel.finish(onRegistered)
    }

    if (exit) MonitDialog(
        onDismissRequest = { exit = false },
        title = {
            Text("계좌 등록에서 나갈까요?")
        },
        actions = {
            MonitButton(
                "계속 확인하기",
                onClick = { exit = false },
            )

            MonitButton(
                "나가기",
                type = MonitButtonType.Secondary,
                onClick = onClose,
            )
        },
    ) {
        Text("나가기는 저장 취소가 아니에요. 제출한 등록은 보존되며 결과를 다시 확인할 수 있어요.")
    }
}
