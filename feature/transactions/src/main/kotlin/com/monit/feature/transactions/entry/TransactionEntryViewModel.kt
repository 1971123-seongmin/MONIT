package com.monit.feature.transactions.entry

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.draft.*
import com.monit.core.model.*
import com.monit.feature.transactions.navigation.RegisteredAccountResult
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 신규 입력의 소유자. 제출 캡처는 saved state, 제출 후 복구 원본은 Repository다. */
@HiltViewModel
class TransactionEntryViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    private val drafts: DraftRepository,
) : ViewModel() {
    private var context: LedgerContext? = null
    private var entryId: String? = null
    private var boundOrigin: com.monit.feature.transactions.navigation.TransactionEntryOrigin? = null
    private var request: CreateTransaction? = null
    private var operationId: String? = null
    private var invalidBinding = false
    private var editor: DraftEditor? = null
    private var draftSubmission: DraftSubmission? = null
    private var transitioning = false
    private val mutable = MutableStateFlow(TransactionEntryState())
    val state = mutable.asStateFlow()

    fun bind(ledger: LedgerContext, route: TransactionEntryDestination) {
        if (context != null) {
            if (context != ledger || entryId != route.entryId || boundOrigin != route.origin) {
                invalidBinding = true
                block()
            }
            return
        }
        context = ledger
        entryId = route.entryId
        boundOrigin = route.origin
        try {
            val bundle = saved.get<Bundle>(KEY)
            if (bundle == null) {
                require(!saved.contains(DRAFT_ID))
                mutable.value = state.value.copy(
                    ui = TransactionEntryUiState(
                        kind = TransactionKind.valueOf(route.initialKind.name),
                        date = route.initialDate ?: today().toString(),
                    ),
                )
                persist()
            } else {
                require(
                    bundle.getString("ledger") == ledger.ledgerId &&
                        bundle.getString("generation") == ledger.storageGeneration &&
                        bundle.getString("entry") == route.entryId,
                )
                if (bundle.containsKey("origin")) require(bundle.getString("origin") == route.origin?.name)
                val ui = TransactionEntryUiState(
                    kind = TransactionKind.valueOf(requireNotNull(bundle.getString("kind"))),
                    amount = requireNotNull(bundle.getString("amount")),
                    date = requireNotNull(bundle.getString("date")),
                    memo = requireNotNull(bundle.getString("memo")),
                    debitAccountId = bundle.getString("debit"),
                    creditAccountId = bundle.getString("credit"),
                )
                mutable.value = state.value.copy(
                    ui = ui,
                    slot = bundle.getString("slot")?.let(AccountSlot::valueOf),
                    selectionRequestId = bundle.getString("selection"),
                )
                operationId = bundle.getString("operation")?.also(::requireLedgerId)
                request = operationId?.let { ui.toRequest(requireNotNull(bundle.getString("transaction"))) }
                draftSubmission = bundle.getBundle("draftSubmission")?.let(DraftSavedState::submission)
                require(draftSubmission == null || request != null)
                draftSubmission?.let { require(it.scope == DraftScope(DraftKind.TRANSACTION, DraftOrigin.valueOf(requireNotNull(route.origin).name))) }
            }
        } catch (_: Exception) {
            invalidBinding = true
            block()
            return
        }
        viewModelScope.launch {
            if (request != null) checkResult(false) else {
                val origin = route.origin
                if (origin == null) {
                    block()
                    return@launch
                }
                val scope = DraftScope(DraftKind.TRANSACTION, DraftOrigin.valueOf(origin.name))
                val initial = draftContent()
                val coordinator = DraftEditor(
                    drafts,
                    ledger,
                    scope,
                    viewModelScope,
                    initial,
                    onId = { saved[DRAFT_ID] = it },
                    onRestore = { content ->
                        val restored = content as DraftContent.Transaction
                        mutable.value = state.value.copy(
                            ui = state.value.ui.copy(
                                kind = restored.kind,
                                amount = restored.amount,
                                date = restored.date,
                                debitAccountId = restored.debitAccountId,
                                creditAccountId = restored.creditAccountId,
                                memo = restored.memo,
                                candidateAccountId = restored.candidateAccountId,
                                error = null,
                            ),
                            slot = restored.selectionSlot?.let { AccountSlot.valueOf(it.name) },
                            selectionRequestId = restored.selectionRequestId,
                        )
                        persist()
                        viewModelScope.launch { loadAccounts() }
                    },
                )
                editor = coordinator
                viewModelScope.launch { coordinator.state.collect { mutable.value = state.value.copy(draftPersistence = it) } }
                coordinator.start(saved[DRAFT_ID])
            }
        }
    }

    fun newDraft() {
        editor?.fresh()
    }
    fun restoreDraft(value: FormDraft) {
        editor?.restore(value)
    }
    fun discardDraft(value: FormDraft) {
        editor?.discardChoice(value)
    }
    fun reloadDrafts() {
        editor?.reload()
    }
    fun flushDraft() {
        editor?.requestFlush()
    }
    fun matchesDraft(value: FormDraft) = value.scope == editor?.scope

    fun leave(discard: Boolean = false, onFinished: () -> Unit) {
        if (transitioning) return
        if (request != null || editor?.state?.value?.phase != DraftEditorPhase.EDITING) {
            onFinished()
            return
        }
        transitioning = true
        viewModelScope.launch {
            try {
                val success = if (discard) editor?.discardCurrent() == true else editor?.flush(force = true) != null
                if (success) onFinished()
            } finally {
                transitioning = false
            }
        }
    }

    fun openRegistration(onReady: (String, String, DraftSlot) -> Unit) {
        val request = beginRegistration() ?: return
        val slot = state.value.slot ?: return
        transitioning = true
        viewModelScope.launch {
            try {
                val committed = editor?.flush(force = true) ?: return@launch
                if (!invalidBinding) onReady(request, committed.reference.id, DraftSlot.valueOf(slot.name))
            } finally {
                transitioning = false
            }
        }
    }

    private fun canEdit() = !invalidBinding && !transitioning && state.value.phase == EntryPhase.EDITING && editor?.state?.value?.phase == DraftEditorPhase.EDITING && editor?.state?.value?.frozen == false

    fun edit(change: TransactionEntryUiState.() -> TransactionEntryUiState) {
        if (!canEdit()) return
        mutable.value = state.value.copy(
            ui = state.value.ui.change().copy(error = null),
            inputError = null,
            slot = null,
            selectionRequestId = null,
        )
        persist()
    }

    fun choose(slot: AccountSlot) {
        if (!canEdit()) return
        mutable.value = state.value.copy(
            slot = slot,
            selectionRequestId = null,
            phase = EntryPhase.LOADING,
            ui = state.value.ui.copy(candidateAccountId = null, error = null),
        )
        persist()
        viewModelScope.launch { loadAccounts() }
    }

    fun dismissPicker() {
        if (state.value.phase != EntryPhase.EDITING && !state.value.canReload) return
        mutable.value = state.value.copy(
            slot = null,
            selectionRequestId = null,
            ui = state.value.ui.copy(candidateAccountId = null),
        )
        persist()
    }

    fun selectCandidate(id: String) {
        if (canEdit() && state.value.slot != null &&
            state.value.ui.accounts.any { it.id == id }
        ) {
            mutable.value = state.value.copy(ui = state.value.ui.copy(candidateAccountId = id))
            persist()
        }
    }

    fun beginRegistration(): String? {
        if (!canEdit() || state.value.slot == null) return null
        val id = state.value.selectionRequestId ?: UUID.randomUUID().toString()
        mutable.value = state.value.copy(selectionRequestId = id)
        persist()
        return id
    }

    /** UI 반환은 후보일 뿐이다. 현재 장부의 활성 계좌를 다시 조회한 뒤 명시 적용한다. */
    fun receive(result: RegisteredAccountResult): Boolean {
        if (state.value.phase != EntryPhase.EDITING || state.value.slot == null ||
            state.value.selectionRequestId != result.selectionRequestId
        ) return false
        mutable.value = state.value.copy(selectionRequestId = null, phase = EntryPhase.LOADING)
        persist()
        viewModelScope.launch { loadAccounts(result.accountId) }
        return true
    }

    fun applyCandidate() {
        val current = state.value
        if (!canEdit()) return
        val candidate = current.ui.candidateAccountId ?: return
        val ledger = context ?: return
        mutable.value = current.copy(phase = EntryPhase.LOADING)
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot(ledger, today())
                if (invalidBinding) return@launch
                require(snapshot.accounts.any { it.id == candidate && it.status == AccountStatus.ACTIVE })
                val opposite = if (current.slot == AccountSlot.DEBIT) current.ui.creditAccountId else current.ui.debitAccountId
                require(current.ui.kind != TransactionKind.TRANSFER || candidate != opposite)
                val ui = when (current.slot) {
                    AccountSlot.DEBIT -> current.ui.copy(debitAccountId = candidate)
                    AccountSlot.CREDIT -> current.ui.copy(creditAccountId = candidate)
                    null -> error("No selected slot")
                }
                mutable.value = current.copy(
                    ui = ui.copy(candidateAccountId = null, error = null),
                    phase = EntryPhase.EDITING,
                    slot = null,
                    selectionRequestId = null,
                )
                persist()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!invalidBinding) mutable.value = current.copy(ui = current.ui.copy(error = "계좌를 적용하지 못했어요. 활성 상태와 이체 계좌를 확인해 주세요."))
            }
        }
    }

    fun submit() {
        if (!canEdit() || state.value.slot != null) return
        val inputError = validateTransactionDraft(state.value.ui, today())
        if (inputError != null) {
            mutable.value = state.value.copy(
                inputError = inputError,
                validationAttempt = state.value.validationAttempt + 1,
                ui = state.value.ui.copy(error = inputError.message),
            )
            return
        }
        val value = try {
            state.value.ui.toRequest(UUID.randomUUID().toString())
        } catch (_: Exception) {
            message("금액(1~999,999,999,999원), 날짜(YYYY-MM-DD), 서로 다른 활성 계좌를 확인해 주세요.")
            return
        }
        val ids = listOfNotNull(value.debitAccountId, value.creditAccountId)
        if (ids.any { id -> state.value.ui.accounts.none { it.id == id } }) {
            message("현재 장부의 활성 계좌를 다시 선택해 주세요.")
            return
        }
        mutable.value = state.value.copy(phase = EntryPhase.SAVING)
        viewModelScope.launch {
            val committed = editor?.flush(force = true)
            if (committed == null || invalidBinding) {
                if (!invalidBinding) mutable.value = state.value.copy(phase = EntryPhase.EDITING)
                return@launch
            }
            draftSubmission = DraftSubmission(committed.reference, committed.scope)
            request = value
            operationId = UUID.randomUUID().toString()
            editor?.freeze()
            persist()
            write()
        }
    }

    fun check(allowWrite: Boolean = false) {
        if (state.value.phase != EntryPhase.UNCONFIRMED) return
        mutable.value = state.value.copy(phase = EntryPhase.CHECKING, resultAbsent = false)
        viewModelScope.launch { checkResult(allowWrite) }
    }

    fun reload() {
        if (!invalidBinding && request == null && context != null && state.value.canReload && state.value.phase == EntryPhase.BLOCKED) {
            mutable.value = state.value.copy(phase = EntryPhase.LOADING)
            viewModelScope.launch { loadAccounts() }
        }
    }

    fun finish(onFinished: () -> Unit) {
        val ledger = context ?: return
        val operation = operationId ?: return
        if (state.value.phase != EntryPhase.SAVED || state.value.finishing) return
        mutable.value = state.value.copy(finishing = true)
        viewModelScope.launch {
            try {
                val result = repository.finishSubmissionRecovery(ledger, operation)
                if (invalidBinding) return@launch
                when (result) {
                    FinishSubmissionRecoveryResult.Finished, FinishSubmissionRecoveryResult.AlreadyInactive -> onFinished()
                    is FinishSubmissionRecoveryResult.NotReady -> message("저장 결과를 다시 확인해 주세요.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message("결과 확인을 마치지 못했어요. 기록은 보존됩니다.")
            } finally {
                mutable.value = state.value.copy(finishing = false)
            }
        }
    }

    private suspend fun loadAccounts(candidate: String? = state.value.ui.candidateAccountId) {
        val ledger = context ?: return
        try {
            val accounts = repository.snapshot(ledger, today()).accounts
                .filter { it.status == AccountStatus.ACTIVE }.map { EntryAccountUiState(it.id, it.name) }
            if (invalidBinding) return
            mutable.value = state.value.copy(
                phase = EntryPhase.EDITING,
                ui = state.value.ui.copy(accounts = accounts, candidateAccountId = candidate?.takeIf { id -> accounts.any { it.id == id } }),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!invalidBinding) block(canReload = true)
        }
    }

    private suspend fun write() {
        if (invalidBinding) return
        try {
            val draft = draftSubmission
            val receipt = if (draft == null) repository.createTransaction(requireNotNull(context), requireNotNull(operationId), requireNotNull(request))
            else drafts.createTransactionFromDraft(requireNotNull(context), draft.reference, draft.scope, requireNotNull(operationId), requireNotNull(request))
            accept(receipt)
        } catch (cancelled: CancellationException) {
            if (!invalidBinding) mutable.value = state.value.copy(phase = EntryPhase.UNCONFIRMED)
            throw cancelled
        } catch (_: Exception) {
            checkResult(false)
        }
    }

    private suspend fun checkResult(allowWrite: Boolean) {
        if (invalidBinding) return
        mutable.value = state.value.copy(phase = EntryPhase.CHECKING, resultAbsent = false)
        try {
            val receipt = repository.receipt(requireNotNull(context), requireNotNull(operationId))
            if (invalidBinding) return
            when {
                receipt != null -> accept(receipt)

                allowWrite -> {
                    mutable.value = state.value.copy(phase = EntryPhase.SAVING)
                    write()
                }

                else -> mutable.value = state.value.copy(phase = EntryPhase.UNCONFIRMED, resultAbsent = true)
            }
        } catch (cancelled: CancellationException) {
            if (!invalidBinding) mutable.value = state.value.copy(phase = EntryPhase.UNCONFIRMED)
            throw cancelled
        } catch (_: Exception) {
            if (!invalidBinding) mutable.value = state.value.copy(phase = EntryPhase.UNCONFIRMED)
        }
    }

    private fun accept(receipt: WriteReceipt) {
        if (invalidBinding) return
        val entity = receipt.entities.singleOrNull()
        if (receipt.operationId != operationId || receipt.ledgerRevision <= 0 ||
            entity?.collection != "transactions" || entity.id != request?.id || entity.revision <= 0
        ) {
            block()
        } else {
            mutable.value = state.value.copy(phase = EntryPhase.SAVED, ui = state.value.ui.copy(error = null))
        }
    }

    private fun TransactionEntryUiState.toRequest(id: String): CreateTransaction {
        require(amount.matches(Regex("[0-9]{1,12}")))
        val day = LocalDate.parse(date)
        require(day.toString() == date && day >= LocalDate.of(1900, 1, 1) && day <= today())
        return CreateTransaction(
            id,
            kind,
            day,
            amount.toLong(),
            debitAccountId.takeIf { kind != TransactionKind.INCOME },
            creditAccountId.takeIf { kind != TransactionKind.EXPENSE },
            memo,
        )
    }

    private fun persist() {
        val ledger = context ?: return
        val current = state.value
        saved[KEY] = Bundle().apply {
            putString("ledger", ledger.ledgerId)
            putString("generation", ledger.storageGeneration)
            putString("entry", entryId)
            putString("origin", boundOrigin?.name)
            putString("kind", current.ui.kind.name)
            putString("amount", current.ui.amount)
            putString("date", current.ui.date)
            putString("memo", current.ui.memo)
            putString("debit", current.ui.debitAccountId)
            putString("credit", current.ui.creditAccountId)
            putString("slot", current.slot?.name)
            putString("selection", current.selectionRequestId)
            putString("operation", operationId)
            putString("transaction", request?.id)
            draftSubmission?.let { putBundle("draftSubmission", DraftSavedState.submission(it)) }
        }
        if (request == null) editor?.changed(draftContent())
    }

    private fun draftContent(): DraftContent.Transaction = state.value.let {
        DraftContent.Transaction(
            it.ui.kind, it.ui.amount, it.ui.date, it.ui.debitAccountId, it.ui.creditAccountId,
            it.ui.memo, it.slot?.let { slot -> DraftSlot.valueOf(slot.name) }, it.selectionRequestId, it.ui.candidateAccountId,
        )
    }

    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun message(value: String) {
        mutable.value = state.value.copy(ui = state.value.ui.copy(error = value))
    }
    private fun block(canReload: Boolean = false) {
        mutable.value = state.value.copy(phase = EntryPhase.BLOCKED, canReload = canReload)
    }

    companion object {
        internal const val KEY = "transactionEntry"
        internal const val DRAFT_ID = "transactionDraftId"
    }
}
