package com.monit.app.backup

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.model.LedgerContext
import com.monit.feature.home.backup.*
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun BackupContent(
    context: LedgerContext,
    onBack: () -> Unit,
    onRestored: () -> Unit = {},
    exportOnly: Boolean = false,
    onExportVerified: (String) -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val window = LocalActivity.current?.window

    LaunchedEffect(context, exportOnly) {
        viewModel.bind(context)
        if (exportOnly) viewModel.entry()
    }

    // 암호 입력·복원 미리보기는 최근 앱/스크린샷에 남기지 않는다. 원래 secure flag는 보존한다.
    DisposableEffect(window) {
        val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            password = ""
            confirmation = ""
            if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val create = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = viewModel::selected,
    )
    val open = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = viewModel::selected,
    )
    val cancel: () -> Unit = {
        password = ""
        confirmation = ""
        if (state.phase == BackupPhase.WORKING) viewModel.requestCancel()
        else if (exportOnly && state.phase == BackupPhase.EXPORTED) viewModel.verifiedExportId()?.let(onExportVerified)
        else viewModel.entry()
    }

    BackHandler {
        when (state.phase) {
            BackupPhase.ENTRY -> onBack()
            BackupPhase.RESULT -> viewModel.finish(onRestored)
            BackupPhase.WORKING -> viewModel.requestCancel()
            BackupPhase.UNKNOWN -> Unit
            else -> cancel()
        }
    }

    BackupScreen(
        state = state,
        password = password,
        confirmation = confirmation,
        onPassword = { if (it.length <= 256) password = it },
        onConfirmation = { if (it.length <= 256) confirmation = it },
        onExport = {
            if (viewModel.picking(true)) try {
                create.launch("MONIT_${LocalDate.now(ZoneId.of("Asia/Seoul"))}.monitbak")
            } catch (_: Exception) {
                viewModel.pickerFailed()
            }
        },
        onOpen = {
            if (viewModel.picking(false)) try {
                open.launch(arrayOf("application/octet-stream", "application/*"))
            } catch (_: Exception) {
                viewModel.pickerFailed()
            }
        },
        onSubmit = {
            val secret = password.toCharArray()
            val repeated = confirmation.toCharArray()
            password = ""
            confirmation = ""
            viewModel.submit(secret, repeated)
        },
        onRestore = viewModel::restore,
        onCancel = cancel,
        onBack = onBack,
        onResolve = viewModel::resolve,
        onNotifications = viewModel::retryNotifications,
        onDone = { viewModel.finish(onRestored) },
        exportOnly = exportOnly,
    )
}
