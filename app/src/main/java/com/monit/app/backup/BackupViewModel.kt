package com.monit.app.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.model.*
import com.monit.feature.home.backup.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 파일/암호는 이 프로세스의 메모리 수명에만 묶는다. SavedStateHandle/초안/로그에 저장하지 않는다. */
@HiltViewModel
internal class BackupViewModel @Inject constructor(
    private val repository: BackupRepository,
    private val documents: BackupDocumentAccess,
    private val recovery: RecoveryCopyStore,
    private val notifications: BackupNotifications,
    private val workGate: LocalDataWorkGate,
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var document: Uri? = null
    private var picker: Boolean? = null
    private var preview: RestorePreview? = null
    private var result: RestoreResult? = null
    private var verifiedExport: BackupExportReceipt? = null
    private var job: Job? = null
    private var monitor: Job? = null
    private var workVersion = 0L

    // 취소 요청으로 isActive가 false여도 동기 JCA/provider와 정리 child가 아직 실행 중일 수 있다.
    private val isRunning get() = job?.isCompleted == false

    fun bind(value: LedgerContext) {
        if (context == value) return
        check(!isRunning)
        context = value
        entry()
        job = viewModelScope.launch { refreshStatus() }
    }

    fun picking(export: Boolean): Boolean {
        if (workGate.held) {
            mutable.value = state.value.copy(ui = state.value.ui.copy(message = "이전 파일 작업의 실제 종료를 기다리고 있어요. 종료 확인 뒤 다시 시도해주세요."))
            return false
        }
        if (isRunning || state.value.phase != BackupPhase.ENTRY || picker != null) return false
        picker = export
        return true
    }

    fun selected(uri: Uri?) {
        val export = picker ?: return
        picker = null
        if (uri == null) return
        document = uri
        mutable.value = BackupState(if (export) BackupPhase.EXPORT_PASSWORD else BackupPhase.RESTORE_PASSWORD, state.value.ui.copy(message = null))
    }

    fun pickerFailed() {
        picker = null
        entry("파일 선택기를 열지 못했어요. 현재 자료는 그대로예요.")
    }

    fun entry(message: String? = null) {
        if (isRunning) return
        verifiedExport = null
        preview?.let { repository.discardPreview(it.id) }
        preview = null
        document = null
        picker = null
        mutable.value = BackupState(ui = state.value.ui.copy(message = message))
    }

    fun submit(password: CharArray, confirmation: CharArray) {
        val phase = state.value.phase
        val target = document
        if (isRunning || target == null || phase !in setOf(BackupPhase.EXPORT_PASSWORD, BackupPhase.RESTORE_PASSWORD)) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            return
        }
        if (phase == BackupPhase.EXPORT_PASSWORD && !password.contentEquals(confirmation)) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            mutable.value = state.value.copy(ui = state.value.ui.copy(message = "두 비밀번호가 일치하지 않아요."))
            return
        }
        confirmation.fill('\u0000')
        val lease = workGate.acquire() ?: run {
            password.fill('\u0000')
            mutable.value = state.value.copy(ui = state.value.ui.copy(message = "이전 파일 작업의 실제 종료를 기다리고 있어요."))
            return
        }
        val token = ++workVersion
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(message = null, cancelRequested = false, progress = "백업 작업 시작 중"))
        job = viewModelScope.launch {
            var exported = false
            var encrypted: ByteArray? = null
            val verifyPassword = password.copyOf()
            try {
                val current = checkNotNull(context)
                if (phase == BackupPhase.EXPORT_PASSWORD) {
                    val file = repository.create(current, password) { stage(token, it) }
                    encrypted = file.encrypted
                    currentCoroutineContext().ensureActive()
                    // provider 쓰기를 시작한 뒤의 실패는 저장 성공 여부를 추정하지 않는다.
                    exported = true
                    stage(token, BackupStage.FILE_IO)
                    val readBack = documents.writeAndRead(target, file.encrypted)
                    try {
                        currentCoroutineContext().ensureActive()
                        verifiedExport = repository.verifyExport(current, file.id, readBack, verifyPassword) { stage(token, it) }
                        currentCoroutineContext().ensureActive()
                    } finally {
                        readBack.fill(0)
                    }
                    refreshStatus()
                    mutable.value = state.value.copy(phase = BackupPhase.EXPORTED)
                } else {
                    stage(token, BackupStage.FILE_IO)
                    val file = documents.read(target)
                    encrypted = file
                    currentCoroutineContext().ensureActive()
                    preview = repository.inspect(current, file, password) { stage(token, it) }
                    currentCoroutineContext().ensureActive()
                    mutable.value = BackupState(BackupPhase.PREVIEW, previewUi(checkNotNull(preview), state.value.ui))
                }
            } catch (cancelled: CancellationException) {
                // 이 지점은 구조화된 자식·파일 정리가 실제 종료한 뒤다. 늦은 성공은 반영하지 않는다.
                mutable.value = state.value.copy(phase = if (exported) BackupPhase.EXPORTED_UNVERIFIED else phase, ui = state.value.ui.copy(cancelRequested = false, message = if (exported) "작업 종료를 확인했지만 외부 파일의 검증은 확정하지 못했어요. 현재 장부는 바뀌지 않았어요." else "작업 종료를 확인했어요. 현재 장부는 바뀌지 않았어요. 비밀번호를 다시 입력해 주세요."))
                throw cancelled
            } catch (error: Exception) {
                mutable.value = state.value.copy(phase = if (exported) BackupPhase.EXPORTED_UNVERIFIED else phase, ui = state.value.ui.copy(message = problem(error)))
            } finally {
                password.fill('\u0000')
                verifyPassword.fill('\u0000')
                encrypted?.fill(0)
                finished(token)
            }
        }.also { pending ->
            pending.invokeOnCompletion { cause ->
                password.fill('\u0000')
                confirmation.fill('\u0000')
                if (cause is CancellationException && token == workVersion && state.value.phase == BackupPhase.WORKING) {
                    mutable.value = state.value.copy(phase = phase, ui = state.value.ui.copy(cancelRequested = false, message = "작업 시작 전에 취소했어요. 현재 장부는 바뀌지 않았어요."))
                }
                finished(token)
                workGate.release(lease)
            }
        }
    }

    fun verifiedExportId(): String? = verifiedExport?.id?.takeIf { state.value.phase == BackupPhase.EXPORTED && !isRunning }

    /** 마지막 확인 버튼에서만 호출한다. 실패 응답만으로 이전/새 상태를 추정하지 않는다. */
    fun restore() {
        val value = preview ?: return
        if (isRunning || state.value.phase != BackupPhase.PREVIEW) return
        val lease = workGate.acquire() ?: run {
            mutable.value = state.value.copy(ui = state.value.ui.copy(message = "이전 파일 작업의 실제 종료를 기다리고 있어요."))
            return
        }
        val token = ++workVersion
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(progress = "복구용 사본 확인·장부 전체 교체 중", message = null, cancelRequested = false))
        job = viewModelScope.launch {
            try {
                val committed = repository.restore(checkNotNull(context), value.id, recovery) { stage(token, it) }
                currentCoroutineContext().ensureActive()
                showResult(committed)
            } catch (cancelled: CancellationException) {
                mutable.value = state.value.copy(phase = BackupPhase.UNKNOWN, ui = state.value.ui.copy(cancelRequested = false, message = "작업 종료를 확인했어요. 취소 요청만으로 교체 여부를 추정하지 말고 저장된 복원 결과를 확인해주세요."))
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(phase = BackupPhase.UNKNOWN)
            } finally {
                finished(token)
            }
        }.also { pending ->
            pending.invokeOnCompletion { cause ->
                if (cause is CancellationException && token == workVersion && state.value.phase == BackupPhase.WORKING) {
                    mutable.value = state.value.copy(phase = BackupPhase.UNKNOWN, ui = state.value.ui.copy(cancelRequested = false))
                }
                finished(token)
                workGate.release(lease)
            }
        }
    }

    fun resolve() {
        val id = preview?.id ?: return
        if (isRunning) return
        job = viewModelScope.launch {
            try {
                val committed = repository.result(id)
                if (committed != null) showResult(committed)
                else mutable.value = state.value.copy(phase = BackupPhase.PREVIEW, ui = state.value.ui.copy(message = "복원 완료 표식이 없어 교체되지 않은 상태를 확인했어요. 다시 검증하려면 취소 후 파일을 선택해주세요."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(phase = BackupPhase.UNKNOWN)
            }
        }
    }

    fun retryNotifications() {
        val value = result ?: return
        if (isRunning) return
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(progress = "알림만 재설정 중"))
        job = viewModelScope.launch {
            try {
                showResult(value)
            } catch (cancelled: CancellationException) {
                mutable.value = BackupState(BackupPhase.RESULT, outcomeUi(value, state.value.ui))
                throw cancelled
            }
        }.also { pending ->
            pending.invokeOnCompletion {
                if (state.value.phase == BackupPhase.WORKING && pending.isCancelled) mutable.value = BackupState(BackupPhase.RESULT, outcomeUi(value, state.value.ui))
            }
        }
    }

    fun finish(onFinished: () -> Unit) {
        val value = result ?: return
        if (isRunning) return
        job = viewModelScope.launch {
            try {
                repository.acknowledgeRestore(value.id)
                onFinished()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(ui = state.value.ui.copy(message = "완료 확인을 저장하지 못했어요. 복원은 다시 실행하지 말고 이 화면에서 확인해주세요."))
            }
        }
    }

    private suspend fun showResult(value: RestoreResult) {
        result = value
        document = null
        val updated = try {
            if (value.notificationResetNeeded) notifications.reconcile(value) else value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value
        }
        result = updated
        mutable.value = BackupState(BackupPhase.RESULT, outcomeUi(updated, state.value.ui))
    }

    private suspend fun refreshStatus() {
        try {
            val status = repository.status()
            mutable.value = state.value.copy(ui = state.value.ui.copy(lastBackup = status.lastVerifiedBackupAt?.let { "마지막 검증 성공: " + date(it) } ?: "검증된 백업이 아직 없어요."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = state.value.copy(ui = state.value.ui.copy(message = "마지막 백업 상태를 확인하지 못했어요."))
        }
    }

    fun requestCancel(reason: String = "취소를 요청했어요. 실제 처리와 파일 정리가 끝나는 것을 확인하고 있어요.") {
        if (state.value.phase != BackupPhase.WORKING || !isRunning || state.value.ui.cancelRequested) return
        mutable.value = state.value.copy(ui = state.value.ui.copy(cancelRequested = true, progress = "취소 요청 · 실제 종료 확인 중", progressDetail = reason + " 동기 암호 처리나 파일 제공자가 바로 멈추지 않을 수 있어요. 종료 확인 전에는 새 작업을 시작할 수 없어요."))
        monitor?.cancel()
        job?.cancel(CancellationException("Backup cancellation requested"))
    }

    private fun stage(token: Long, stage: BackupStage) {
        viewModelScope.launch {
            if (token != workVersion || state.value.phase != BackupPhase.WORKING || state.value.ui.cancelRequested) return@launch
            monitor?.cancel()
            mutable.value = state.value.copy(
                ui = state.value.ui.copy(
                    progress = when (stage) {
                        BackupStage.SNAPSHOT -> "일관된 장부 자료 확인 중"
                        BackupStage.CRYPTO -> "비밀번호로 암호 처리 중"
                        BackupStage.VALIDATION -> "파일 형식·금융 이력 검증 중"
                        BackupStage.FILE_IO -> "외부 파일 저장·읽기 중"
                        BackupStage.RECOVERY_COPY -> "기기 보호 복구용 사본 검증 중"
                        BackupStage.REPLACEMENT -> "장부 전체 교체·완료 표식 확인 중"
                    },
                    progressDetail = "취소를 요청해도 실제 처리와 파일 정리가 끝날 때까지 기다려야 해요.",
                ),
            )
            val threshold = when (stage) {
                BackupStage.CRYPTO -> 10_000L
                BackupStage.VALIDATION -> 30_000L
                BackupStage.FILE_IO, BackupStage.RECOVERY_COPY -> 60_000L
                else -> return@launch
            }
            monitor = viewModelScope.launch {
                delay(threshold)
                if (token != workVersion || state.value.phase != BackupPhase.WORKING || state.value.ui.cancelRequested) return@launch
                if (stage == BackupStage.CRYPTO) mutable.value = state.value.copy(ui = state.value.ui.copy(progressDetail = "암호 처리가 10초 넘게 걸리고 있어요. 취소를 요청할 수 있지만, 암호 처리의 실제 종료를 기다려야 해요."))
                else requestCancel(if (stage == BackupStage.VALIDATION) "전체 파싱·검증이 30초를 넘어 중단을 요청했어요." else "파일 처리가 60초를 넘어 중단을 요청했어요.")
            }
        }
    }

    private fun finished(token: Long) {
        if (token != workVersion) return
        monitor?.cancel()
        workVersion++
    }
}

internal fun previewUi(value: RestorePreview, prior: BackupUiState): BackupUiState {
    val labels = linkedMapOf("accounts" to "계좌", "transactions" to "거래", "payment_rules" to "반복 일정", "payment_items" to "월 항목")
    return prior.copy(message = null, sourceDate = date(value.sourceCreatedAt), summary = "현재 ${value.currentCounts.values.sum()}개 → 백업 ${value.recordTotal}개로 전체 교체", counts = labels.map { (key, label) -> BackupCountUiState(label, (value.currentCounts[key] ?: 0).toString(), (value.sourceCounts[key] ?: 0).toString()) }, originals = "포함 원문 ${value.originalCount}개", anchorSummary = "복원 기준일 · " + value.anchorDates.toSortedMap().entries.joinToString { "${it.key} 계좌 ${it.value}개" }.ifEmpty { "계좌 없음" }, legacyNotice = if (value.legacyEvidenceMissing) "이전 형식은 제출 이력·알림 선호의 기록 여부를 알 수 없어요. 없는 것으로 판단하거나 이전 요청을 다시 실행하지 않아요." else null)
}

internal fun outcomeUi(value: RestoreResult, prior: BackupUiState = BackupUiState()): BackupUiState = prior.copy(sourceDate = date(value.sourceCreatedAt), summary = "전체 ${value.recordTotal}개 · 현재 장부에 반영 완료", notificationResetNeeded = value.notificationResetNeeded, message = null, cancelRequested = false)
private fun date(value: String): String = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(Instant.parse(value))

private fun problem(error: Exception): String = when ((error as? BackupException)?.problem) {
    BackupProblem.PASSWORD -> "비밀번호는 15~128자, UTF-8 512바이트 이하여야 해요. 제어 문자는 사용할 수 없어요."
    BackupProblem.AUTHENTICATION -> "비밀번호가 다르거나 파일이 손상됐어요. 현재 장부는 바뀌지 않았어요."
    BackupProblem.UNSUPPORTED -> "지원하지 않는 형식이나 보존할 수 없는 이전 자료가 포함돼 있어요. 현재 장부는 바뀌지 않았어요."
    BackupProblem.LIMIT -> "허용된 파일 크기나 기록 수를 넘었어요. 현재 장부는 바뀌지 않았어요."
    else -> "파일을 처리하지 못했어요. 파일 접근 상태와 내용을 확인해주세요."
}
