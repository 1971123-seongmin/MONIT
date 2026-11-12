package com.monit.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.model.LedgerContext

@Composable
fun HomeRoute(
    context: LedgerContext,
    viewModel: HomeViewModel = hiltViewModel(),
    onNewTransaction: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(context) {
        viewModel.refresh(context)

        onPauseOrDispose {}
    }

    HomeScreen(
        state,
        onRetry = { viewModel.refresh(context) },
        onNewTransaction = onNewTransaction,
    )
}
