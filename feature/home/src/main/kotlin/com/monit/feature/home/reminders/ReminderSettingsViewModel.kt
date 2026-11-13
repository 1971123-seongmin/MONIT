package com.monit.feature.home.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val repository: ReminderRepository,
    private val access: ReminderAccessSource,
    private val contexts: CoroutineContextProvider,
) : ViewModel() {
    private val mutable = MutableStateFlow(ReminderSettingsState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var configuration: ReminderConfiguration? = null
    private var invalid = false
    private var readJob: Job? = null
    private var generation = 0L

    fun bind(value: LedgerContext) {
        if (context != null) {
            if (context != value) {
                invalid = true
                generation++
                readJob?.cancel()
                mutable.value = state.value.copy(phase = ReminderSettingsPhase.FAILED)
            }
            return
        }
        context = value
        refresh()
        viewModelScope.launch {
            try {
                repository.changes().collect { refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(phase = ReminderSettingsPhase.FAILED)
            }
        }
    }

    fun refresh() {
        val current = context ?: return
        if (invalid || state.value.phase == ReminderSettingsPhase.SAVING) return
        generation++
        mutable.value = state.value.copy(phase = ReminderSettingsPhase.LOADING)
        // 권한 응답/복귀/DB 관찰은 진행 중 조회를 취소하지 않고 최신 재조회 한 번으로 모은다.
        if (readJob?.isCompleted == false) return
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            while (!invalid) {
                val request = generation
                try {
                    val result = withContext(contexts.default) {
                        val config = repository.configuration(current)
                        config to reminderSettingsProjection(config, access.read())
                    }
                    currentCoroutineContext().ensureActive()
                    require(result.first.context == current)
                    if (invalid) return@launch
                    if (request != generation) continue
                    configuration = result.first
                    mutable.value = ReminderSettingsState(ReminderSettingsPhase.READY, result.second.copy(message = state.value.ui.message))
                } catch (cancelled: CancellationException) {
                    // 부모/VM 종료는 전파한다. 조회 자체 취소는 성공으로 추정하지 않고 실패를 표시한다.
                    currentCoroutineContext().ensureActive()
                    if (invalid) throw cancelled
                    if (request != generation) continue
                    mutable.value = state.value.copy(phase = ReminderSettingsPhase.FAILED)
                    throw cancelled
                } catch (_: Exception) {
                    if (invalid) return@launch
                    if (request != generation) continue
                    mutable.value = state.value.copy(phase = ReminderSettingsPhase.FAILED)
                }
                if (request == generation) return@launch
            }
        }
        readJob = task
        task.start()
    }

    override fun onCleared() {
        invalid = true
        generation++
    }

    fun setEnabled(value: Boolean) = change { config -> repository.setEnabled(config.context, config.revision, value) }
    fun setRuleEnabled(id: String, value: Boolean) = change { config ->
        val rule = config.rules.find { it.ruleId == id } ?: error("Unknown rule")
        repository.setRuleEnabled(config.context, id, rule.revision, value)
    }

    private fun change(write: suspend (ReminderConfiguration) -> ReminderConfiguration) {
        val config = configuration ?: return
        if (invalid || state.value.phase != ReminderSettingsPhase.READY) return
        readJob?.cancel()
        ++generation
        mutable.value = state.value.copy(phase = ReminderSettingsPhase.SAVING, ui = state.value.ui.copy(message = null))
        viewModelScope.launch {
            try {
                val result = write(config)
                require(result.context == context)
                configuration = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(ui = state.value.ui.copy(message = "알림 설정 저장 결과를 다시 확인해 주세요. 금융 기록과 납입 상태는 그대로예요."))
            }
            if (!invalid) {
                mutable.value = state.value.copy(phase = ReminderSettingsPhase.READY)
                refresh()
            }
        }
    }
}

internal fun reminderSettingsProjection(config: ReminderConfiguration, access: ReminderAccess): ReminderSettingsUiState {
    val status = when {
        !config.enabled -> "전체 알림 꺼짐"
        !access.allowed -> "알림 불가"
        else -> "알림 켜짐"
    }
    val explanation = when {
        !access.permissionGranted -> "알림 권한이 꺼져 있어요.\n기록과 수동 납입 확인은 계속 이용할 수 있어요."
        !access.appEnabled -> "기기 설정에서 MONIT 앱 알림이 꺼져 있어요.\n기록과 수동 납입 확인은 계속 가능해요."
        !access.channelEnabled -> "앱 알림은 켜져 있지만 납입 알림 채널이 꺼져 있어요.\n기록과 수동 납입 확인은 계속 가능해요."
        !config.enabled -> "전체 알림을 꺼둔 상태예요.\n켜면 아래 일정별 선택에 따라 기본 시점으로 요청해요."
        else -> "권한 · 앱 알림 · 납입 알림 채널 허용\n서울 기준 예정일 전날·당일·다음 날\n오전 9시 이후 · 하루 요약 1회"
    }
    return ReminderSettingsUiState(
        enabled = config.enabled,
        allowed = access.allowed,
        status = status,
        explanation = explanation,
        permission = if (access.permissionGranted) "허용" else "거부·철회",
        app = if (access.appEnabled) "허용" else "차단",
        channel = if (access.channelEnabled) "허용" else "차단·확인 필요",
        permissionDenied = !access.permissionGranted,
        appBlocked = !access.appEnabled,
        channelBlocked = !access.channelEnabled,
        dirty = config.dirty,
        failed = config.failed,
        next = config.nextAt?.takeIf { config.enabled && access.allowed }?.atZone(reminderZone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm '이후 요청 (서울)'")) ?: "예약할 알림 없음",
        rules = config.rules.map { ReminderRuleUiState(it.ruleId, it.name, it.enabled, (if (it.isDefault) "기본값" else "개별 설정") + if (it.enabled) " · 켜짐" else " · 꺼짐") },
    )
}
