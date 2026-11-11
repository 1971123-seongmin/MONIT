package com.monit.feature.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.model.LedgerContext

@Composable
fun TransactionsRoute(
    context: LedgerContext,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(context) {
        viewModel.refresh(context)

        onPauseOrDispose {}
    }

    TransactionsScreen(
        state = state,
        onRetry = viewModel::refresh,
        onMonthChange = viewModel::changeMonth,
        onDateSelected = viewModel::selectDate,
        onToday = viewModel::goToToday,
        onCalendarSelected = viewModel::selectCalendar,
        onKindSelected = viewModel::selectKind,
        onAccountSelected = viewModel::selectAccount,
    )
}
