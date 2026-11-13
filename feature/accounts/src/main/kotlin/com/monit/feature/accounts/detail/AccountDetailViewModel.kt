package com.monit.feature.accounts.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.draft.*
import com.monit.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import java.time.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val anchors: AnchorRepository,
    private val drafts: DraftRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) : ViewModel() {
    private val mutable = MutableStateFlow(AccountDetailState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var accountId: String? = null
    private var entryId: String? = null
    private var invalid = false
    private var reading = false
    private var snapshot: LedgerSnapshot? = null
    private var account: LedgerAccount? = null
    private var editor: DraftEditor? = null
    private var editorObserver: Job? = null
    private var scope: DraftScope? = null
    private var command: ConfirmAnchor? = null
    private var operation: String? = null
    private var submission: DraftSubmission? = null
    private var month = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private var histories: List<AnchorChange> = emptyList()
    private var asOf = today()
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))

    fun bind(value: LedgerContext, id: String, entry: String) {
        if (context != null) {
            if (context != value || accountId != id || entryId != entry) {
                invalid = true
                mutable.value = AccountDetailState(AccountDetailPhase.BLOCKED)
            }
            return
        }
        try {
            requireLedgerId(id)
            requireLedgerId(entry)
        } catch (_: IllegalArgumentException) {
            invalid = true
            mutable.value = AccountDetailState(AccountDetailPhase.BLOCKED)
            return
        }
        context = value
        accountId = id
        entryId = entry
        reload()
    }

    fun reload() {
        val ledger = context ?: return
        if (invalid || reading || operation != null || state.value.phase !in setOf(AccountDetailPhase.LOADING, AccountDetailPhase.READY, AccountDetailPhase.FAILED, AccountDetailPhase.RESULT)) return
        reading = true
        mutable.value = state.value.copy(phase = AccountDetailPhase.LOADING)
        viewModelScope.launch {
            try {
                val date = today()
                val data = repository.snapshot(ledger, date)
                val selected = data.accounts.single { it.id == accountId }
                val history = anchors.anchorHistory(ledger, selected.id)
                val ui = withContext(contexts.default) { project(data, selected, history, date) }
                if (invalid) return@launch
                snapshot = data
                account = selected
                histories = history
                asOf = date
                mutable.value = AccountDetailState(AccountDetailPhase.READY, ui)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.FAILED)
            } finally {
                reading = false
            }
        }
    }

    fun moveMonth(delta: Long) {
        if (state.value.phase != AccountDetailPhase.READY || invalid || reading) return
        val next = month.plusMonths(delta)
        if (next.year !in 1900..9999) return
        month = next
        val data = snapshot ?: return
        val selected = account ?: return
        reading = true
        viewModelScope.launch {
            try {
                val ui = withContext(contexts.default) { project(data, selected, histories, asOf) }
                if (!invalid) mutable.value = state.value.copy(ui = ui)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.FAILED)
            } finally {
                reading = false
            }
        }
    }

    fun edit() {
        val ledger = context ?: return
        val selected = account ?: return
        if (invalid || reading || state.value.phase != AccountDetailPhase.READY || operation != null) return
        val target = DraftScope(DraftKind.ANCHOR, DraftOrigin.ACCOUNTS, accountId = selected.id, anchorId = selected.anchor.id, anchorRevision = selected.anchor.revision, anchorDate = selected.anchor.date)
        scope = target
        mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING, ui = state.value.ui.copy(form = AnchorFormUiState(), message = ""))
        val current = DraftEditor(drafts, ledger, target, viewModelScope, DraftContent.Anchor("", ""), { saved["anchorDraftId"] = it }, { content ->
            if (!invalid && content is DraftContent.Anchor) mutable.value = state.value.copy(ui = state.value.ui.copy(form = AnchorFormUiState(content.amount, content.reason), message = ""))
        })
        editorObserver?.cancel()
        editor = current
        editorObserver = viewModelScope.launch { current.state.collectLatest { if (editor === current && !invalid) mutable.value = state.value.copy(draft = it) } }
        current.start(saved["anchorDraftId"])
    }

    fun newDraft() = editor?.fresh()
    fun retryDraft() = editor?.reload()
    fun restore(value: FormDraft) {
        if (value.scope == scope && !invalid) editor?.restore(value)
    }
    fun matchesDraft(value: FormDraft): Boolean = !invalid && value.context == context && value.scope == scope
    fun discardChoice(value: FormDraft) = editor?.discardChoice(value)
    fun change(amount: String, reason: String) {
        if (invalid || state.value.phase != AccountDetailPhase.EDITING || editor?.state?.value?.phase != DraftEditorPhase.EDITING || editor?.state?.value?.frozen == true) return
        mutable.value = state.value.copy(ui = state.value.ui.copy(form = AnchorFormUiState(amount, reason), message = ""))
        editor?.changed(DraftContent.Anchor(amount, reason))
    }
    fun flush() = editor?.requestFlush()

    fun preview() {
        val selected = account ?: return
        val data = snapshot ?: return
        if (invalid || state.value.phase != AccountDetailPhase.EDITING || editor?.state?.value?.phase != DraftEditorPhase.EDITING) return
        val form = state.value.ui.form
        mutable.value = state.value.copy(phase = AccountDetailPhase.REVIEWING)
        viewModelScope.launch {
            try {
                val (value, preview) = withContext(contexts.default) {
                    val value = anchorCommandFromDraft(checkNotNull(scope), DraftContent.Anchor(form.amount, form.reason))
                    requireLedgerText(value.reason, allowBlank = selected.anchor.amount == null)
                    val replacement = selected.anchor.copy(amount = value.amount, confirmedAt = clock.instant(), unknownReason = null)
                    requireBalanceHistory(replacement, selected.id, data.transactions, asOf)
                    val balance = accountBalance(replacement, selected.id, data.transactions, asOf)
                    val active = data.accounts.filter { it.status == AccountStatus.ACTIVE }
                    val beforeAmounts = active.mapNotNull { data.balances[it.id] }
                    val afterAmounts = active.mapNotNull { if (it.id == selected.id) balance else data.balances[it.id] }
                    fun subtotal(values: List<Long>) = if (values.isEmpty()) "확인된 금액 없음" else money(sumSignedAmounts(values))
                    value to AnchorPreviewUiState(state.value.ui.balance, money(balance), money(value.amount), value.reason, subtotal(beforeAmounts), subtotal(afterAmounts), beforeAmounts.size, afterAmounts.size)
                }
                if (invalid) return@launch
                command = value
                mutable.value = state.value.copy(phase = AccountDetailPhase.PREVIEW, ui = state.value.ui.copy(preview = preview, message = ""))
            } catch (cancelled: CancellationException) {
                if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING)
                throw cancelled
            } catch (_: IllegalArgumentException) {
                if (!invalid) {
                    mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING)
                    message("기준일 마감액(±999,999,999,999원 이내)과 확인·변경 사유를 입력해 주세요.")
                }
            } catch (_: ArithmeticException) {
                if (!invalid) {
                    mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING)
                    message("이 기준액은 장부 잔액의 합산 한도를 벗어납니다. 금액을 다시 확인해 주세요.")
                }
            }
        }
    }

    fun backToEdit() {
        if (!invalid && state.value.phase == AccountDetailPhase.PREVIEW && operation == null) mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING)
    }

    fun confirm() {
        val value = command ?: return
        val ledger = context ?: return
        val current = editor ?: return
        if (invalid || state.value.phase != AccountDetailPhase.PREVIEW || operation != null) return
        mutable.value = state.value.copy(phase = AccountDetailPhase.SAVING)
        viewModelScope.launch {
            try {
                val form = current.flush(force = true)
                if (invalid) return@launch
                if (form == null || form.scope != scope || anchorCommandFromDraft(form.scope, form.content as DraftContent.Anchor) != value) {
                    mutable.value = state.value.copy(phase = AccountDetailPhase.EDITING)
                    message("초안 저장을 확인하지 못했어요. 다시 검토해 주세요.")
                    return@launch
                }
                submission = DraftSubmission(form.reference, form.scope)
                operation = UUID.randomUUID().toString()
                current.freeze()
                submit(ledger, checkNotNull(operation), value)
            } catch (cancelled: CancellationException) {
                if (!invalid) mutable.value = state.value.copy(phase = if (operation == null) AccountDetailPhase.EDITING else AccountDetailPhase.UNCERTAIN)
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(phase = if (operation == null) AccountDetailPhase.EDITING else AccountDetailPhase.UNCERTAIN)
            }
        }
    }

    private suspend fun submit(ledger: LedgerContext, id: String, value: ConfirmAnchor) {
        try {
            val receipt = repository.receipt(ledger, id) ?: anchors.confirmAnchor(ledger, id, value, submission)
            require(receipt.operationId == id && receipt.entities == listOf(EntityRef("anchors", value.anchorId, Math.addExact(value.expectedRevision, 1))))
            if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.RESULT)
        } catch (rejected: AnchorRejectedException) {
            require(rejected.rejection.context == ledger && rejected.rejection.operationId == id)
            if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.REJECTED)
        }
    }

    fun checkResult() {
        val id = operation ?: return
        val value = command ?: return
        val ledger = context ?: return
        if (invalid || state.value.phase != AccountDetailPhase.UNCERTAIN) return
        mutable.value = state.value.copy(phase = AccountDetailPhase.SAVING)
        viewModelScope.launch {
            try {
                submit(ledger, id, value)
            } catch (cancelled: CancellationException) {
                if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.UNCERTAIN)
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) mutable.value = state.value.copy(phase = AccountDetailPhase.UNCERTAIN)
            }
        }
    }

    fun finish() {
        val id = operation ?: return
        val ledger = context ?: return
        if (invalid || state.value.phase !in setOf(AccountDetailPhase.RESULT, AccountDetailPhase.REJECTED)) return
        val phase = state.value.phase
        mutable.value = state.value.copy(phase = AccountDetailPhase.SAVING)
        viewModelScope.launch {
            try {
                check(repository.finishSubmissionRecovery(ledger, id) !is FinishSubmissionRecoveryResult.NotReady)
                if (invalid) return@launch
                operation = null
                command = null
                submission = null
                editorObserver?.cancel()
                editorObserver = null
                editor = null
                saved.remove<String>("anchorDraftId")
                mutable.value = state.value.copy(phase = AccountDetailPhase.READY)
                reload()
            } catch (cancelled: CancellationException) {
                if (!invalid) mutable.value = state.value.copy(phase = phase)
                throw cancelled
            } catch (_: Exception) {
                if (!invalid) {
                    mutable.value = state.value.copy(phase = phase)
                    message("결과 확인을 마치지 못했어요. 다시 확인해 주세요.")
                }
            }
        }
    }

    private fun message(value: String) {
        mutable.value = state.value.copy(ui = state.value.ui.copy(message = value))
    }
    private fun money(value: Long?) = value?.let { NumberFormat.getIntegerInstance(Locale.KOREA).format(it) + "원" } ?: "미확인"
    fun keepDraftAndClose(onClose: () -> Unit) {
        val current = editor
        if (invalid || state.value.phase in setOf(AccountDetailPhase.SAVING, AccountDetailPhase.REVIEWING)) return
        if (state.value.phase !in setOf(AccountDetailPhase.EDITING, AccountDetailPhase.PREVIEW) || current == null || current.state.value.phase != DraftEditorPhase.EDITING) {
            onClose()
            return
        }
        val phase = state.value.phase
        mutable.value = state.value.copy(phase = AccountDetailPhase.SAVING)
        viewModelScope.launch {
            try {
                val form = current.flush(force = true)
                if (invalid) return@launch
                mutable.value = state.value.copy(phase = phase)
                if (form != null && form.content == DraftContent.Anchor(state.value.ui.form.amount, state.value.ui.form.reason)) onClose() else message("초안 보관을 확인하지 못했어요. 다시 시도해 주세요.")
            } catch (cancelled: CancellationException) {
                if (!invalid) mutable.value = state.value.copy(phase = phase)
                throw cancelled
            }
        }
    }

    private fun project(data: LedgerSnapshot, selected: LedgerAccount, history: List<AnchorChange>, date: LocalDate): AccountDetailUiState {
        val flows = assetFlows(data.transactions, month, selected.id)
        val change = try {
            selected.anchor.amount?.let { base -> data.balances[selected.id]?.let { money(sumSignedAmounts(listOf(it, -base))) } } ?: "미확인"
        } catch (_: ArithmeticException) {
            "변동 합계가 표시 범위를 벗어나요"
        }
        return AccountDetailUiState(
            title = selected.name, balance = money(data.balances[selected.id]), asOf = date.toString(), anchorDate = selected.anchor.date.toString(), anchorAmount = money(selected.anchor.amount),
            anchorConfirmed = selected.anchor.amount != null,
            balanceChange = change,
            inclusion = when {
                selected.status == AccountStatus.ARCHIVED -> "보관된 계좌 · 현재 잔액 합계 제외"
                data.balances[selected.id] == null -> "기준액 미확인 · 현재 잔액 합계 제외"
                else -> "활성 KRW 계좌 · 확인된 장부 잔액 합계 포함"
            },
            month = month.toString(), deposits = money(flows.deposits), withdrawals = money(flows.withdrawals), net = money(flows.net),
            history = history.map { AnchorHistoryUiState(it.operationId, "${if (it.kind == AnchorChangeKind.INITIAL_CONFIRMATION) "최초 확인" else "기준액 변경"} · ${it.confirmedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime()} · ${money(it.before.amount)} → ${money(it.amount)} · 기준액 ${it.before.revision} → ${it.revision}" + if (it.reason.isNotBlank()) "\n${it.reason}" else "") },
        )
    }
}
