package com.monit.app.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.BackupRepository
import com.monit.core.model.RestoreResult
import com.monit.feature.home.backup.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RestoreCompletionState(val loading: Boolean = true, val failed: Boolean = false, val result: RestoreResult? = null)

/** 프로세스가 commit 직후 종료돼도 완료 표식을 읽기 전 제품 입력/이전 back stack을 열지 않는다. */
@HiltViewModel
internal class RestoreCompletionViewModel @Inject constructor(private val repository: BackupRepository, private val notifications: BackupNotifications) : ViewModel() {
    private val mutable = MutableStateFlow(RestoreCompletionState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init {
        refresh()
    }

    fun refresh() {
        if (job?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true, failed = false)
        job = viewModelScope.launch {
            try {
                mutable.value = RestoreCompletionState(loading = false, result = repository.status().unacknowledgedRestore)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = RestoreCompletionState(loading = false, failed = true)
            }
        }
    }

    fun retryNotifications() {
        val result = state.value.result ?: return
        if (job?.isActive == true) return
        mutable.value = state.value.copy(loading = true)
        job = viewModelScope.launch {
            val updated = try {
                notifications.reconcile(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                result
            }
            mutable.value = RestoreCompletionState(loading = false, result = updated)
        }
    }

    fun finish(onFinished: () -> Unit) {
        val result = state.value.result ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                repository.acknowledgeRestore(result.id)
                onFinished()
                mutable.value = RestoreCompletionState(loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(failed = true)
            }
        }
    }
}

@Composable
internal fun RestoreCompletionGate(
    onCompleted: () -> Unit,
    viewModel: RestoreCompletionViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.result

    BackHandler(enabled = state.loading || state.failed || result != null) {
        if (!state.loading && result != null) viewModel.finish(onCompleted)
    }

    when {
        state.loading || state.failed -> Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(MonitTheme.spacing.content),
        ) {
            MonitStatusPanel(
                title = if (state.failed) "복원 결과 확인이 필요해요" else "복원 완료 상태 확인 중",
                description = "같은 복원을 다시 실행하지 마세요. 완료 표식을 확인한 뒤 장부를 열어요.",
                error = state.failed,
                action = if (state.failed) (
                    {
                        MonitButton(
                            text = "다시 확인",
                            onClick = viewModel::refresh,
                        )
                    }
                    ) else null,
            )
        }

        result != null -> Box(Modifier.safeDrawingPadding()) {
            BackupScreen(
                state = BackupState(BackupPhase.RESULT, outcomeUi(result)),
                password = "",
                confirmation = "",
                onPassword = {},
                onConfirmation = {},
                onExport = {},
                onOpen = {},
                onSubmit = {},
                onRestore = {},
                onCancel = {},
                onBack = {},
                onResolve = viewModel::refresh,
                onNotifications = viewModel::retryNotifications,
                onDone = { viewModel.finish(onCompleted) },
            )
        }

        else -> content()
    }
}
