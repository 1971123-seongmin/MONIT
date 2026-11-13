package com.monit.feature.accounts.importing

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 원문/URI/판정은 메모리, 프로세스 복구에는 조회용 context/batch ID만 남긴다. */
@HiltViewModel
class ImportPreviewViewModel @Inject constructor(
    private val repository: ImportRepository,
    private val reader: ImportDocumentReader,
    private val contexts: CoroutineContextProvider,
    private val workGate: LocalDataWorkGate,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(ImportPreviewState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var invalidBinding = false
    private var selectedUri: Uri? = null
    private var job: Job? = null
    private var version = 0L
    private var anchorDrafts: Map<String, ImportAnchorDraftUiState> = emptyMap()
    private val running get() = job?.isCompleted == false

    fun bind(value: LedgerContext) {
        if (context == value) return
        if (context != null || (
                saved.get<String>("import-ledger") != null &&
                    (saved.get<String>("import-ledger") != value.ledgerId || saved.get<String>("import-generation") != value.storageGeneration)
                )
        ) {
            invalidBinding = true
            version++
            job?.cancel()
            mutable.value = state.value.copy(phase = ImportPreviewPhase.BLOCKED, problem = "장부가 바뀌었어요. 계좌로 돌아가 다시 열어 주세요.")
            return
        }
        context = value
        saved["import-ledger"] = value.ledgerId
        saved["import-generation"] = value.storageGeneration
        start(ImportPreviewPhase.LOADING) {
            val batch = saved.get<String>("import-batch")
            if (batch != null) {
                val attempt = repository.attempt(value, batch)
                currentCoroutineContext().ensureActive()
                showAttempt(attempt)
            } else {
                val pending = repository.pending(value)
                currentCoroutineContext().ensureActive()
                mutable.value = ImportPreviewState(if (pending.isEmpty()) ImportPreviewPhase.EMPTY else ImportPreviewPhase.RECOVERY, pending = pending)
            }
        }
    }

    fun select(uri: Uri) {
        if (running || invalidBinding) return
        start(ImportPreviewPhase.LOADING) {
            clearReview()
            selectedUri = uri
            require(uri.scheme == "content")
            val bytes = reader.read(uri, ImportLimits.MAX_INPUT_BYTES)
            try {
                currentCoroutineContext().ensureActive()
                show(repository.review(checkNotNull(context), bytes))
            } finally {
                bytes.fill(0)
            }
        }
    }
    fun reviewAgain() {
        selectedUri?.let(::select)
    }
    fun confirmTarget() = revise { it.copy(targetConfirmed = true) }
    fun mapAccount(namespace: String, sourceId: String, accountId: String) = revise(after = { anchorDrafts = emptyMap() }) { options ->
        val before = state.value.review?.report?.input?.accounts.orEmpty()
        options.copy(accounts = options.accounts.ifEmpty { before }.filterNot { it.walletNamespace == namespace && it.sourceId == sourceId } + ImportAccountMapping(namespace, sourceId, accountId, true), anchors = emptyList())
    }
    fun keepAnchor(accountId: String) {
        val account = state.value.review?.accounts?.singleOrNull { it.id == accountId } ?: return
        chooseAnchor(ImportAnchorChoice(account.id, account.anchor.id, account.anchor.revision, account.anchor.date.toString(), ImportAnchorAction.KEEP, account.anchor.amount, "", true))
    }
    fun confirmAnchor(accountId: String, amount: String, reason: String) {
        val account = state.value.review?.accounts?.singleOrNull { it.id == accountId } ?: return
        val parsed = amount.takeIf { it.matches(Regex("-?[0-9]{1,12}")) }?.toLongOrNull()
        if (parsed == null) {
            mutable.value = state.value.copy(problem = "기준액은 지원 범위의 원화 정수로 입력해 주세요.")
            return
        }
        chooseAnchor(ImportAnchorChoice(account.id, account.anchor.id, account.anchor.revision, account.anchor.date.toString(), ImportAnchorAction.CONFIRM, parsed, reason, true))
    }
    private fun chooseAnchor(choice: ImportAnchorChoice) = revise(after = {
        anchorDrafts = anchorDrafts + (choice.accountId to if (choice.action == ImportAnchorAction.KEEP) ImportAnchorDraftUiState() else (anchorDrafts[choice.accountId] ?: ImportAnchorDraftUiState()).copy(dirty = false))
    }) { it.copy(anchors = it.anchors.filterNot { anchor -> anchor.accountId == choice.accountId } + choice) }
    fun anchorAmount(accountId: String, amount: String) = anchorDraft(accountId, amount = amount)
    fun anchorReason(accountId: String, reason: String) = anchorDraft(accountId, reason = reason)
    private fun anchorDraft(accountId: String, amount: String? = null, reason: String? = null) {
        if (running || state.value.phase != ImportPreviewPhase.READY) return
        val before = anchorDrafts[accountId] ?: ImportAnchorDraftUiState()
        if ((amount != null && amount.length > 16 * 1024) || (reason != null && reason.length > 16 * 1024)) return
        anchorDrafts = anchorDrafts + (accountId to before.copy(amount = amount ?: before.amount, reason = reason ?: before.reason, dirty = true))
        mutable.value = state.value.copy(ui = drafts(state.value.ui))
    }
    private fun drafts(ui: ImportPreviewUiState): ImportPreviewUiState = ui.copy(workflow = ui.workflow.copy(canApply = state.value.review?.canApply == true && anchorDrafts.values.none { it.dirty }, accounts = ui.workflow.accounts.map { it.copy(draft = anchorDrafts[it.id] ?: ImportAnchorDraftUiState()) }))
    fun confirmRow(locator: String) = revise { it.copy(confirmedRows = it.confirmedRows + locator, excludedRows = it.excludedRows - locator) }
    fun distinctRow(locator: String) = revise { it.copy(distinctRows = it.distinctRows + locator, confirmedRows = it.confirmedRows + locator, excludedRows = it.excludedRows - locator) }
    fun excludeRow(locator: String) = revise { it.copy(excludedRows = it.excludedRows + locator, confirmedRows = it.confirmedRows - locator) }
    fun selectGroup(locator: String) {
        if (running || state.value.phase != ImportPreviewPhase.READY) return
        val before = state.value.selectedGroup
        mutable.value = state.value.copy(selectedGroup = if (locator in before) before - locator else (before + locator).takeIf { it.size <= 2 } ?: before)
    }
    fun confirmGroup() {
        val selected = state.value.selectedGroup
        if (selected.size != 2) return
        revise { it.copy(groups = it.groups.filter { group -> group.locators.none { locator -> locator in selected } } + ImportTransferGroup(selected.sorted(), true), confirmedRows = it.confirmedRows + selected) }
    }
    private fun revise(after: () -> Unit = {}, change: (ImportReviewOptions) -> ImportReviewOptions) {
        val review = state.value.review ?: return
        if (running || state.value.phase != ImportPreviewPhase.READY) return
        val options = change(review.options)
        start(ImportPreviewPhase.LOADING) {
            val revised = repository.revise(checkNotNull(context), review.token, options)
            currentCoroutineContext().ensureActive()
            after()
            show(revised)
        }
    }

    fun checkRevision() {
        val review = state.value.review ?: return
        val uri = selectedUri ?: return
        if (running || state.value.phase != ImportPreviewPhase.READY) return
        start(ImportPreviewPhase.LOADING) {
            val bytes = reader.read(uri, ImportLimits.MAX_INPUT_BYTES)
            try {
                currentCoroutineContext().ensureActive()
                repository.checkSource(checkNotNull(context), review.token, bytes)
                currentCoroutineContext().ensureActive()
            } finally {
                bytes.fill(0)
            }
            mutable.value = state.value.copy(phase = ImportPreviewPhase.READY, checking = false)
        }
    }
    fun backupReturned(exportId: String) {
        val review = state.value.review ?: return
        start(ImportPreviewPhase.LOADING) { show(repository.bindBackup(checkNotNull(context), review.token, exportId)) }
    }

    /** 최종 확인 뒤에만 실행한다. 요청/금융을 자동 재실행하는 경로는 없다. */
    fun apply() {
        val review = state.value.review ?: return
        val uri = selectedUri ?: return
        if (!review.canApply || anchorDrafts.values.any { it.dirty } || review.verifiedBackupId == null || state.value.phase != ImportPreviewPhase.READY) return
        start(ImportPreviewPhase.APPLYING) {
            val target = checkNotNull(context)
            val bytes = reader.read(uri, ImportLimits.MAX_INPUT_BYTES)
            try {
                currentCoroutineContext().ensureActive()
                repository.checkSource(target, review.token, bytes)
                currentCoroutineContext().ensureActive()
            } finally {
                bytes.fill(0)
            }
            saved["import-batch"] = review.batchId
            val result = repository.apply(target, review.token)
            currentCoroutineContext().ensureActive()
            mutable.value = state.value.copy(phase = ImportPreviewPhase.RESULT, ui = result.toUiState(), result = result, problem = null)
        }
    }
    fun resolve(batchId: String? = saved["import-batch"]) {
        val target = context ?: return
        start(ImportPreviewPhase.LOADING) {
            if (batchId != null) {
                saved["import-batch"] = batchId
                val attempt = repository.attempt(target, batchId)
                currentCoroutineContext().ensureActive()
                showAttempt(attempt)
            } else {
                val pending = repository.pending(target)
                currentCoroutineContext().ensureActive()
                mutable.value = ImportPreviewState(if (pending.isEmpty()) ImportPreviewPhase.EMPTY else ImportPreviewPhase.RECOVERY, pending = pending)
            }
        }
    }
    fun acknowledge() {
        val batch = state.value.result?.batchId ?: saved.get<String>("import-batch") ?: return
        start(ImportPreviewPhase.LOADING) {
            if (!state.value.missingVerified) repository.acknowledge(checkNotNull(context), batch)
            currentCoroutineContext().ensureActive()
            saved.remove<String>("import-batch")
            clearReview()
            val pending = repository.pending(checkNotNull(context))
            currentCoroutineContext().ensureActive()
            mutable.value = ImportPreviewState(if (pending.isEmpty()) ImportPreviewPhase.EMPTY else ImportPreviewPhase.RECOVERY, pending = pending)
        }
    }
    fun requestCancel() {
        if (!running) return
        mutable.value = state.value.copy(phase = ImportPreviewPhase.STOPPING, problem = "취소를 요청했어요. 파일 작업과 정리가 실제로 끝날 때까지 기다려 주세요.")
        job?.cancel()
    }
    fun canLeave(): Boolean {
        if (running) {
            requestCancel()
            return false
        }
        return true
    }
    private suspend fun show(review: ImportReview) {
        currentCoroutineContext().ensureActive()
        val ui = withContext(contexts.default) { review.toUiState() }
        currentCoroutineContext().ensureActive()
        mutable.value = ImportPreviewState(ImportPreviewPhase.READY, ui.copy(workflow = ui.workflow.copy(canApply = review.canApply && anchorDrafts.values.none { it.dirty }, accounts = ui.workflow.accounts.map { it.copy(draft = anchorDrafts[it.id] ?: ImportAnchorDraftUiState()) })), review.report, review = review)
    }
    private fun showAttempt(attempt: ImportAttempt) {
        mutable.value = when (attempt) {
            is ImportAttempt.Committed -> ImportPreviewState(phase = ImportPreviewPhase.RESULT, ui = attempt.result.toUiState(), result = attempt.result)
            is ImportAttempt.Requested -> ImportPreviewState(phase = ImportPreviewPhase.RECOVERY, ui = ImportPreviewUiState(canAcknowledge = true), problem = "미반영 요청이 보관되어 있어요. 자동 재실행하지 않아요. 확인을 마친 뒤 파일부터 다시 검토해 주세요.", pending = listOf(attempt))
            is ImportAttempt.Evidence -> ImportPreviewState(phase = ImportPreviewPhase.RECOVERY, result = attempt.result, problem = "복원된 실행 근거예요. 현재 실행 대기 항목이 아니에요.")
            ImportAttempt.Missing -> ImportPreviewState(phase = ImportPreviewPhase.RECOVERY, ui = ImportPreviewUiState(canAcknowledge = true), problem = "현재 장부에서 보관된 요청·결과를 찾지 못했어요. 성공으로 표시하지 않았어요. 이 조회 확인을 마친 뒤 새로 검토할 수 있어요.", missingVerified = true)
        }
    }
    private fun start(phase: ImportPreviewPhase, action: suspend () -> Unit) {
        if (running || invalidBinding) return
        val lease = workGate.acquire() ?: run {
            mutable.value = state.value.copy(phase = ImportPreviewPhase.RECOVERY, problem = "이전 파일 작업의 실제 종료를 기다리고 있어요. 종료 후 결과 조회를 다시 눌러 주세요.")
            return
        }
        val own = ++version
        mutable.value = state.value.copy(phase = phase, problem = null)
        val task = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (own == version && !invalidBinding) mutable.value = state.value.copy(phase = if (saved.get<String>("import-batch") != null) ImportPreviewPhase.RECOVERY else if (state.value.review != null && error is IllegalArgumentException && error !is ImportFileException) ImportPreviewPhase.READY else if (state.value.review != null) ImportPreviewPhase.STALE else ImportPreviewPhase.FAILED, problem = problem(error))
            }
        }
        job = task
        task.invokeOnCompletion {
            workGate.release(lease)
            if (own == version && !invalidBinding && state.value.phase == ImportPreviewPhase.STOPPING) mutable.value = state.value.copy(phase = if (saved.get<String>("import-batch") != null) ImportPreviewPhase.RECOVERY else ImportPreviewPhase.STALE, problem = "취소 작업이 끝났어요. 결과를 조회하거나 파일부터 다시 검토해 주세요.")
        }
        task.start()
    }
    private fun clearReview() {
        state.value.review?.token?.let(repository::discard)
        anchorDrafts = emptyMap()
    }
    override fun onCleared() {
        clearReview()
    }
    private fun problem(error: Exception): String = when {
        error is ImportInputTooLargeException || (error is ImportFileException && error.problem in setOf(ImportFileProblem.TOO_LARGE, ImportFileProblem.LIMIT)) -> "지원 용량을 넘었어요. 일부를 잘라서 반영하지 않았어요."
        error is ImportFileException && error.problem == ImportFileProblem.BACKUP_REQUIRED -> "현재 장부와 같은 기준의 검증된 백업이 필요해요."
        error is ImportFileException && error.problem == ImportFileProblem.STALE_REVIEW -> "파일·매핑·판정 또는 장부가 바뀌었어요. 다시 검토해 주세요."
        error is ImportFileException && error.problem == ImportFileProblem.WRONG_LEDGER -> "파일의 대상 장부 또는 저장 세대가 달라요. 자동으로 대응하지 않았어요."
        else -> "처리를 완료하지 못했어요. 확정 여부는 저장된 결과에서 확인하고, 필요한 경우 다시 검토해 주세요."
    }
}
