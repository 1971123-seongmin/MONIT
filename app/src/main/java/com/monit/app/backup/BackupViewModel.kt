package com.monit.app.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.*
import com.monit.feature.home.backup.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var document: Uri? = null
    private var picker: Boolean? = null
    private var preview: RestorePreview? = null
    private var result: RestoreResult? = null
    private var job: Job? = null

    fun bind(value: LedgerContext) {
        if (context == value) return
        check(job?.isActive != true)
        context = value
        entry()
        job = viewModelScope.launch { refreshStatus() }
    }

    fun picking(export: Boolean): Boolean {
        if (job?.isActive == true || state.value.phase != BackupPhase.ENTRY || picker != null) return false
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
        if (job?.isActive == true) return
        preview?.let { repository.discardPreview(it.id) }
        preview = null
        document = null
        picker = null
        mutable.value = BackupState(ui = state.value.ui.copy(message = message))
    }

    fun submit(password: CharArray, confirmation: CharArray) {
        val phase = state.value.phase
        val target = document
        if (job?.isActive == true || target == null || phase !in setOf(BackupPhase.EXPORT_PASSWORD, BackupPhase.RESTORE_PASSWORD)) {
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
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(message = null, progress = if (phase == BackupPhase.EXPORT_PASSWORD) "암호화한 파일 저장·재검증 중" else "백업 파일 인증·내용 검증 중"))
        job = viewModelScope.launch {
            var exported = false
            var encrypted: ByteArray? = null
            val verifyPassword = password.copyOf()
            try {
                val current = checkNotNull(context)
                if (phase == BackupPhase.EXPORT_PASSWORD) {
                    val file = repository.create(current, password)
                    encrypted = file.encrypted
                    // provider 쓰기를 시작한 뒤의 실패는 저장 성공 여부를 추정하지 않는다.
                    exported = true
                    val readBack = documents.writeAndRead(target, file.encrypted)
                    try {
                        repository.verifyExport(current, file.id, readBack, verifyPassword)
                    } finally {
                        readBack.fill(0)
                    }
                    refreshStatus()
                    mutable.value = state.value.copy(phase = BackupPhase.EXPORTED)
                } else {
                    val file = documents.read(target)
                    encrypted = file
                    preview = repository.inspect(current, file, password)
                    mutable.value = BackupState(BackupPhase.PREVIEW, previewUi(checkNotNull(preview), state.value.ui))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = state.value.copy(phase = if (exported) BackupPhase.EXPORTED_UNVERIFIED else phase, ui = state.value.ui.copy(message = problem(error)))
            } finally {
                password.fill('\u0000')
                verifyPassword.fill('\u0000')
                encrypted?.fill(0)
            }
        }.also { pending ->
            pending.invokeOnCompletion {
                password.fill('\u0000')
                confirmation.fill('\u0000')
            }
        }
    }

    /** 마지막 확인 버튼에서만 호출한다. 실패 응답만으로 이전/새 상태를 추정하지 않는다. */
    fun restore() {
        val value = preview ?: return
        if (job?.isActive == true || state.value.phase != BackupPhase.PREVIEW) return
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(progress = "복구용 사본 확인·장부 전체 교체 중", message = null))
        job = viewModelScope.launch {
            try {
                showResult(repository.restore(checkNotNull(context), value.id, recovery))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(phase = BackupPhase.UNKNOWN)
            }
        }
    }

    fun resolve() {
        val id = preview?.id ?: return
        if (job?.isActive == true) return
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
        if (job?.isActive == true) return
        mutable.value = state.value.copy(phase = BackupPhase.WORKING, ui = state.value.ui.copy(progress = "알림만 재설정 중"))
        job = viewModelScope.launch { showResult(value) }
    }

    fun finish(onFinished: () -> Unit) {
        val value = result ?: return
        if (job?.isActive == true) return
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
}

internal fun previewUi(value: RestorePreview, prior: BackupUiState): BackupUiState {
    val labels = linkedMapOf("accounts" to "계좌", "transactions" to "거래", "payment_rules" to "반복 일정", "payment_items" to "월 항목")
    return prior.copy(message = null, sourceDate = date(value.sourceCreatedAt), summary = "현재 ${value.currentCounts.values.sum()}개 → 백업 ${value.recordTotal}개로 전체 교체", counts = labels.map { (key, label) -> BackupCountUiState(label, (value.currentCounts[key] ?: 0).toString(), (value.sourceCounts[key] ?: 0).toString()) }, originals = "포함 원문 ${value.originalCount}개", anchorSummary = "복원 기준일 · " + value.anchorDates.toSortedMap().entries.joinToString { "${it.key} 계좌 ${it.value}개" }.ifEmpty { "계좌 없음" }, legacyNotice = if (value.legacyEvidenceMissing) "이전 형식은 제출 이력·알림 선호의 기록 여부를 알 수 없어요. 없는 것으로 판단하거나 이전 요청을 다시 실행하지 않아요." else null)
}

internal fun outcomeUi(value: RestoreResult, prior: BackupUiState = BackupUiState()): BackupUiState = prior.copy(sourceDate = date(value.sourceCreatedAt), summary = "전체 ${value.recordTotal}개 · 현재 장부에 반영 완료", notificationResetNeeded = value.notificationResetNeeded, message = null)
private fun date(value: String): String = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(Instant.parse(value))

private fun problem(error: Exception): String = when ((error as? BackupException)?.problem) {
    BackupProblem.PASSWORD -> "비밀번호는 15~128자, UTF-8 512바이트 이하여야 해요. 제어 문자는 사용할 수 없어요."
    BackupProblem.AUTHENTICATION -> "비밀번호가 다르거나 파일이 손상됐어요. 현재 장부는 바뀌지 않았어요."
    BackupProblem.UNSUPPORTED -> "지원하지 않는 형식이나 보존할 수 없는 이전 자료가 포함돼 있어요. 현재 장부는 바뀌지 않았어요."
    BackupProblem.LIMIT -> "허용된 파일 크기나 기록 수를 넘었어요. 현재 장부는 바뀌지 않았어요."
    else -> "파일을 처리하지 못했어요. 파일 접근 상태와 내용을 확인해주세요."
}
