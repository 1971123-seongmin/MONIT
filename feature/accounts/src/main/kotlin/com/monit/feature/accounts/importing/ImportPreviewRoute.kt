package com.monit.feature.accounts.importing

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.model.LedgerContext

@Composable
fun ImportPreviewRoute(
    context: LedgerContext,
    onBack: () -> Unit,
    viewModel: ImportPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::select)
    }

    SideEffect { viewModel.bind(context) }

    LifecycleResumeEffect(context) {
        viewModel.checkRevision()

        onPauseOrDispose {}
    }

    BackHandler(onBack = onBack)

    ImportPreviewScreen(
        phase = state.phase,
        ui = state.ui,
        onSelectFile = { picker.launch(arrayOf("application/json", "text/plain")) },
        onCheckRevision = viewModel::checkRevision,
        onReviewAgain = viewModel::reviewAgain,
        onBack = onBack,
        problem = state.problem,
        checking = state.checking,
    )
}
