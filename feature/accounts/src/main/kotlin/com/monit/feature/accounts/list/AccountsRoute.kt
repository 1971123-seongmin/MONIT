package com.monit.feature.accounts.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.model.LedgerContext

@Composable
fun AccountsRoute(
    context: LedgerContext,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(context) {
        viewModel.refresh(context)

        onPauseOrDispose {}
    }

    AccountsScreen(
        state,
        onRetry = { viewModel.refresh(context) },
    )
}
