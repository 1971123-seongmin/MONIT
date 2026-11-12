package com.monit.feature.accounts.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 미리보기는 VM 메모리에만 있다. Repository에는 snapshot 읽기 외의 호출을 하지 않는다. */
@HiltViewModel
class ImportPreviewViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val reader: ImportDocumentReader,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) : ViewModel() {
    private val mutable = MutableStateFlow(ImportPreviewState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var invalidBinding = false
    private var selectedUri: Uri? = null
    private var request = 0L
    private var job: Job? = null

    fun bind(ledger: LedgerContext) {
        if (context == null) context = ledger
        else if (context != ledger) {
            invalidBinding = true
            request++
            job?.cancel()
            mutable.value = ImportPreviewState(ImportPreviewPhase.BLOCKED, problem = "다른 장부로 바뀌었어요. 계좌 화면으로 돌아가 다시 열어 주세요.")
        }
    }

    fun select(uri: Uri) {
        val ledger = context ?: return
        if (invalidBinding) return
        val own = ++request
        job?.cancel()
        selectedUri = uri
        mutable.value = ImportPreviewState(ImportPreviewPhase.LOADING)
        job = viewModelScope.launch {
            try {
                require(uri.scheme == "content")
                val bytes = reader.read(uri, ImportPreviewParser.MAX_INPUT_BYTES)
                val input = withContext(contexts.default) { ImportPreviewParser.parse(bytes) }
                if (input.target != null && input.target != ledger) throw ImportFileException(ImportFileProblem.WRONG_LEDGER)
                val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                val snapshot = repository.snapshot(ledger, today)
                val report = withContext(contexts.default) { ImportPreviewEvaluator.evaluate(input, ledger, snapshot, today) }
                val ui = withContext(contexts.default) { report.toUiState() }
                if (own == request && !invalidBinding) mutable.value = ImportPreviewState(ImportPreviewPhase.READY, ui, report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (own == request && !invalidBinding) mutable.value = ImportPreviewState(ImportPreviewPhase.FAILED, problem = problem(error))
            }
        }
    }

    /** 파일과 로컬 revision을 재확인한다. 기존 결과를 조용히 새 내용으로 바꾸지 않는다. */
    fun checkRevision() {
        val previous = state.value.report ?: return
        val ledger = context ?: return
        val uri = selectedUri ?: return
        if (invalidBinding || state.value.checking || state.value.phase == ImportPreviewPhase.LOADING) return
        val own = ++request
        job?.cancel()
        mutable.value = state.value.copy(checking = true, problem = null)
        job = viewModelScope.launch {
            try {
                val bytes = reader.read(uri, ImportPreviewParser.MAX_INPUT_BYTES)
                val digest = withContext(contexts.default) { ImportPreviewParser.digest(bytes) }
                val snapshot = repository.snapshot(ledger, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))
                if (own != request || invalidBinding) return@launch
                val stale = digest != previous.input.inputSha256 || snapshot.revision != previous.ledgerRevision
                mutable.value = state.value.copy(phase = if (stale) ImportPreviewPhase.STALE else ImportPreviewPhase.READY, checking = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (own == request && !invalidBinding) mutable.value = state.value.copy(phase = ImportPreviewPhase.STALE, checking = false, problem = problem(error))
            }
        }
    }

    fun reviewAgain() {
        selectedUri?.let(::select)
    }

    private fun problem(error: Exception): String = when {
        error is ImportInputTooLargeException -> "검토 파일은 8 MiB까지 읽을 수 있어요. 일부만 잘라서 처리하지 않았어요."

        error is ImportFileException -> when (error.problem) {
            ImportFileProblem.TOO_LARGE -> "검토 파일은 8 MiB까지 읽을 수 있어요. 일부만 잘라서 처리하지 않았어요."
            ImportFileProblem.INVALID_UTF8 -> "UTF-8로 읽을 수 없는 파일이에요. 원문은 변경하지 않았어요."
            ImportFileProblem.INVALID_DIGEST -> "제공된 원문·매핑과 digest가 맞지 않아요. 파일을 다시 확인해 주세요."
            ImportFileProblem.UNSUPPORTED_VERSION -> "지원하지 않는 검토 파일 또는 변환 버전이에요."
            ImportFileProblem.INVALID_MAPPING -> "명시 매핑이 모호하거나 지원 형식과 맞지 않아요."
            ImportFileProblem.TOO_MANY_ROWS -> "검토 파일은 원본 10,000행까지 지원해요. 일부만 처리하지 않았어요."
            ImportFileProblem.WRONG_LEDGER -> "이 파일의 계좌 매핑은 다른 장부 또는 저장 세대를 대상으로 해요."
            ImportFileProblem.INVALID_JSON -> "검토 JSON의 형식·중복 키·깊이·크기 제한을 확인해 주세요."
            ImportFileProblem.UNAVAILABLE -> "파일 또는 장부를 읽지 못했어요. 원본과 기존 기록은 보존됩니다."
        }

        else -> "파일 또는 장부를 읽지 못했어요. 원본과 기존 기록은 보존됩니다."
    }
}
