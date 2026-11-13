package com.monit.feature.home.payments

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.draft.*
import com.monit.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PaymentsViewModel @Inject constructor(
    private val payments: PaymentRepository,
    private val ledger: LedgerRepository,
    private val drafts: DraftRepository,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(PaymentsState())
    val state = mutable.asStateFlow()
    private var context: LedgerContext? = null
    private var entryId: String? = null
    private var invalid = false
    private var month = YearMonth.from(today()).toString()
    private var snapshot: PaymentMonthSnapshot? = null
    private var records: LedgerSnapshot? = null
    private var selected: String? = null
    private var scope: DraftScope? = null
    private var editor: DraftEditor? = null
    private var editorJob: Job? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var operation: String? = null
    private var command: PaymentCommand? = null
    private var submittedDraft: DraftSubmission? = null
    private var preparedRequests: Map<String, PreparedPayment> = emptyMap()
    private var transactionRows: List<PaymentTransactionUiState> = emptyList()
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun id() = UUID.randomUUID().toString()
    private fun busy() = state.value.phase in listOf(PaymentsPhase.SAVING, PaymentsPhase.CHECKING, PaymentsPhase.LOADING)

    fun bind(value: LedgerContext, routeId: String) {
        if (context != null) {
            if (context != value || entryId != routeId) {
                invalid = true
                mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            }
            return
        }
        context = value
        entryId = routeId
        try {
            val bundle = saved.get<Bundle>(KEY)
            if (bundle != null) {
                require(bundle.getString("ledger") == value.ledgerId && bundle.getString("generation") == value.storageGeneration && bundle.getString("entry") == routeId)
                month = requireNotNull(bundle.getString("month")).also(::paymentMonth)
                selected = bundle.getString("selected")
                scope = bundle.getBundle("scope")?.let(DraftSavedState::scope)
                val fields = bundle.getBundle("fields")?.let { fields -> fields.keySet().associateWith { requireNotNull(fields.getString(it)) } } ?: emptyMap()
                val mode = PaymentsMode.valueOf(requireNotNull(bundle.getString("mode")))
                mutable.value = state.value.copy(mode = mode, ui = state.value.ui.copy(form = PaymentFormUiState(bundle.getString("title") ?: "납입 입력", fields)))
                operation = bundle.getString("operation")?.also(::requireLedgerId)
                submittedDraft = bundle.getBundle("submittedDraft")?.let(DraftSavedState::submission)
                if (operation != null || mode == PaymentsMode.REVIEW) command = paymentCommandFromDraft(requireNotNull(scope), fields)
                require(submittedDraft == null || operation != null)
                submittedDraft?.let { require(it.scope == scope) }
            } else require(!saved.contains(DRAFT_ID))
        } catch (_: Exception) {
            invalid = true
            mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            return
        }
        viewModelScope.launch {
            if (!load()) return@launch
            if (operation != null) checkOutcome()
            else if (scope != null) startEditor(saved[DRAFT_ID])
            else mutable.value = state.value.copy(phase = PaymentsPhase.READY)
        }
    }
    private suspend fun load(): Boolean {
        val ctx = context ?: return false
        return try {
            val result = withContext(contexts.default) {
                val payment = payments.month(ctx, month)
                val transactions = ledger.snapshot(ctx, today())
                require(payment.ledgerRevision == transactions.revision) { "Ledger changed during read; retry explicitly" }
                val requests = payments.prepared(ctx)
                preparedRequests = requests.associateBy { it.operationId }
                Triple(payment, transactions, paymentProjection(payment, transactions, requests))
            }
            if (invalid) return false
            snapshot = result.first
            records = result.second
            transactionRows = result.third.transactions
            val previous = state.value.ui
            val selectedItem = result.third.items.find { it.id == selected }
            val ui = result.third.copy(selected = selectedItem, form = previous.form, review = command?.let { paymentReview(it, selectedItem, result.third.accounts, previous.form.values) } ?: previous.review)
            mutable.value = state.value.copy(ui = ui, phase = PaymentsPhase.READY)
            persist()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!invalid) mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            false
        }
    }
    fun refresh() {
        if (invalid || busy() || state.value.mode !in listOf(PaymentsMode.MONTH, PaymentsMode.DETAIL)) return
        readJob?.cancel()
        mutable.value = state.value.copy(phase = PaymentsPhase.LOADING)
        readJob = viewModelScope.launch { load() }
    }
    fun reload() {
        if (invalid || busy()) return
        mutable.value = state.value.copy(phase = PaymentsPhase.LOADING)
        viewModelScope.launch {
            if (operation != null) checkOutcome()
            else if (load() && scope != null) startEditor(saved[DRAFT_ID])
        }
    }
    fun changeMonth(delta: Long) {
        if (state.value.mode != PaymentsMode.MONTH || busy()) return
        val next = paymentMonth(month).plusMonths(delta)
        if (next.year !in 1900..9999) return
        month = next.toString()
        selected = null
        refresh()
    }
    fun selectItem(id: String) {
        if (busy() || state.value.phase != PaymentsPhase.READY) return
        val item = state.value.ui.items.find { it.id == id } ?: return
        selected = id
        mutable.value = state.value.copy(mode = PaymentsMode.DETAIL, ui = state.value.ui.copy(selected = item, message = null))
        persist()
    }
    fun registerRule() {
        if (busy()) return
        openForm(
            DraftScope(DraftKind.PAYMENT_RULE, DraftOrigin.PAYMENTS, paymentId = id(), paymentRevision = 0),
            mapOf("mode" to "REGISTER", "name" to "", "purpose" to "GENERAL", "amount" to "", "day" to today().dayOfMonth.toString(), "start" to month, "end" to "", "debit" to "", "credit" to ""),
            "반복 납입 등록",
            PaymentsMode.RULE_FORM,
        )
    }
    fun changeRule(id: String, end: Boolean = false) {
        if (busy()) return
        val rule = snapshot?.rules?.find { it.id == id } ?: return
        val target = if (end) PaymentCommand.EndRule(id, rule.revision, maxOf(month, YearMonth.from(today()).toString(), rule.startMonth))
        else PaymentCommand.ChangeRule(id, rule.revision, maxOf(YearMonth.from(today()).plusMonths(1).toString(), rule.startMonth), rule.terms)
        val (scope, content) = paymentDraftForCommand(target)
        openForm(scope, content.fields, if (end) "일정 종료" else "반복 규칙 변경", PaymentsMode.RULE_FORM)
    }
    fun newPayment() {
        val item = selectedItem() ?: return
        if (!item.active || item.status == PaymentStatus.COMPLETED || item.status == PaymentStatus.SKIPPED || item.transactionId != null || busy()) return
        val transaction = CreateTransaction(
            id(),
            if (item.terms.purpose in listOf(PaymentPurpose.GENERAL, PaymentPurpose.INSURANCE)) TransactionKind.EXPENSE else TransactionKind.TRANSFER,
            today(),
            item.terms.amount,
            item.terms.debitAccountId,
            item.terms.creditAccountId,
            item.terms.name,
        )
        val initial = PaymentCommand.Confirm(item.reference, PaymentTransaction(transaction), false, "")
        val (scope, content) = paymentDraftForCommand(initial)
        openForm(scope, content.fields + planned(item) + mapOf("full" to ""), "새 납입 내용 입력", PaymentsMode.TRANSACTION_FORM)
    }
    fun chooseExisting() {
        val item = selectedItem() ?: return
        val current = records ?: return
        val monthly = snapshot ?: return
        if (busy() || !item.active || item.status in listOf(PaymentStatus.COMPLETED, PaymentStatus.SKIPPED)) return
        mutable.value = state.value.copy(mode = PaymentsMode.CHOOSE_TRANSACTION, phase = PaymentsPhase.LOADING, ui = state.value.ui.copy(message = null))
        viewModelScope.launch {
            val candidates = withContext(contexts.default) {
                val accounts = current.accounts.associateBy { it.id }
                val eligible = current.transactions.filter { tx ->
                    val reserved = monthly.reservations[tx.id]
                    val debit = accounts[tx.debitAccountId]
                    tx.id !in monthly.partialTransactions && (reserved == null || reserved == item.reference.id) &&
                        (item.transactionId == null || item.transactionId == tx.id) && debit?.status == AccountStatus.ACTIVE &&
                        when (item.terms.purpose) {
                            PaymentPurpose.GENERAL, PaymentPurpose.INSURANCE -> tx.kind == TransactionKind.EXPENSE
                            PaymentPurpose.SAVINGS, PaymentPurpose.INVESTMENT -> tx.kind == TransactionKind.TRANSFER && tx.creditAccountId == item.terms.creditAccountId && debit.nature == AccountNature.GENERAL && accounts[tx.creditAccountId]?.status == AccountStatus.ACTIVE
                        }
                }.map { it.id }.toSet()
                transactionRows.filter { it.id in eligible }
            }
            if (!invalid) mutable.value = state.value.copy(phase = PaymentsPhase.READY, ui = state.value.ui.copy(transactions = candidates))
            persist()
        }
    }
    fun useTransaction(id: String) {
        if (state.value.mode != PaymentsMode.CHOOSE_TRANSACTION || state.value.ui.transactions.none { it.id == id }) return
        val item = selectedItem() ?: return
        val tx = records?.transactions?.find { it.id == id } ?: return
        if (busy()) return
        val initial = PaymentCommand.Confirm(item.reference, PaymentTransaction(CreateTransaction(tx.id, tx.kind, tx.date, tx.amount, tx.debitAccountId, tx.creditAccountId, tx.memo), financialRevision = tx.financialRevision), false, "")
        val (scope, content) = paymentDraftForCommand(initial)
        openForm(scope, content.fields + planned(item) + mapOf("full" to ""), "기존 거래 납입 확인", PaymentsMode.TRANSACTION_FORM)
    }
    fun itemAction(action: PaymentAction) {
        val item = selectedItem() ?: return
        if (busy() || !item.active) return
        val value = PaymentCommand.ChangeItem(item.reference, action)
        editor?.freeze()
        editorJob?.cancel()
        editor = null
        val (scope, fields) = paymentDraftForCommand(value)
        this.scope = scope
        command = value
        operation = null
        submittedDraft = null
        saved[DRAFT_ID] = null
        mutable.value = state.value.copy(mode = PaymentsMode.REVIEW, phase = PaymentsPhase.READY, draft = null, ui = state.value.ui.copy(form = PaymentFormUiState("납입 상태 확인", fields.fields), review = paymentReview(value, state.value.ui.selected, state.value.ui.accounts), message = null))
        persist()
    }
    private fun selectedItem() = snapshot?.items?.find { it.reference.id == selected }
    private fun planned(item: MonthlyPayment) = mapOf("plannedName" to item.terms.name, "plannedAmount" to item.terms.amount.toString(), "plannedDate" to item.dueDate.toString(), "purpose" to item.terms.purpose.name)
    private fun openForm(value: DraftScope, fields: Map<String, String>, title: String, mode: PaymentsMode) {
        editor?.freeze()
        editorJob?.cancel()
        editor = null
        scope = value
        operation = null
        command = null
        submittedDraft = null
        saved[DRAFT_ID] = null
        mutable.value = state.value.copy(mode = mode, phase = PaymentsPhase.READY, draft = null, picker = null, ui = state.value.ui.copy(form = PaymentFormUiState(title, fields), message = null))
        persist()
        startEditor(null)
    }
    private fun startEditor(savedId: String?) {
        val ctx = context ?: return
        val target = scope ?: return
        editor?.freeze()
        editorJob?.cancel()
        val current = DraftEditor(
            drafts,
            ctx,
            target,
            viewModelScope,
            DraftContent.Payment(state.value.ui.form.values),
            onId = { saved[DRAFT_ID] = it },
            onRestore = { content ->
                if (content is DraftContent.Payment && !invalid) {
                    mutable.value = state.value.copy(ui = state.value.ui.copy(form = state.value.ui.form.copy(values = content.fields), message = null))
                    if (operation == null && state.value.mode == PaymentsMode.REVIEW) {
                        command = null
                        mutable.value = state.value.copy(mode = if (target.kind == DraftKind.PAYMENT_RULE) PaymentsMode.RULE_FORM else PaymentsMode.TRANSACTION_FORM)
                    }
                    persist()
                }
            },
        )
        editor = current
        editorJob = viewModelScope.launch { current.state.collect { if (!invalid) mutable.value = state.value.copy(draft = it) } }
        current.start(savedId)
    }
    fun freshDraft() {
        editor?.fresh()
    }
    fun restoreDraft(value: FormDraft) {
        if (operation != null || busy() || editor?.state?.value?.phase != DraftEditorPhase.CHOOSING) return
        if (!matchesDraft(value)) return
        if (value.scope == scope) {
            editor?.restore(value)
            return
        }
        scope = value.scope
        saved[DRAFT_ID] = value.reference.id
        mutable.value = state.value.copy(ui = state.value.ui.copy(form = state.value.ui.form.copy(values = (value.content as DraftContent.Payment).fields)))
        persist()
        startEditor(value.reference.id)
        val current = editor ?: return
        viewModelScope.launch {
            val ready = current.state.first { it.phase != DraftEditorPhase.LOADING }
            if (ready.phase == DraftEditorPhase.CHOOSING && editor === current) current.restore(value)
        }
    }
    fun discardChoice(value: FormDraft) {
        editor?.discardChoice(value)
    }
    fun retryDrafts() {
        editor?.reload()
    }
    fun matchesDraft(value: FormDraft): Boolean = value.context == context && (
        value.scope == scope ||
            (
                state.value.ui.form.values["mode"] == "REGISTER" && scope?.kind == DraftKind.PAYMENT_RULE &&
                    value.scope.kind == DraftKind.PAYMENT_RULE && value.scope.paymentRevision == 0L &&
                    (value.content as? DraftContent.Payment)?.fields?.get("mode") == "REGISTER"
                )
        )
    fun flush() {
        editor?.requestFlush()
    }
    fun edit(key: String, value: String) {
        if (busy() || operation != null || editor?.state?.value?.phase != DraftEditorPhase.EDITING || editor?.state?.value?.frozen == true) return
        if (key !in setOf("name", "purpose", "amount", "day", "start", "end", "effective", "debit", "credit", "date", "memo", "reason", "full")) return
        if (state.value.ui.form.values["financialRevision"]?.isNotEmpty() == true && key in setOf("amount", "date", "debit", "credit", "memo")) return
        val fields = state.value.ui.form.values + (key to value)
        val normalized = if (key == "purpose" && value in listOf("GENERAL", "INSURANCE")) fields + ("credit" to "") else fields
        mutable.value = state.value.copy(ui = state.value.ui.copy(form = state.value.ui.form.copy(values = normalized), message = null), picker = null)
        editor?.changed(DraftContent.Payment(normalized))
        persist()
    }
    fun picker(field: String?) {
        if (!busy()) mutable.value = state.value.copy(picker = field)
    }
    fun review() {
        if (busy() || editor?.state?.value?.phase != DraftEditorPhase.EDITING || editor?.state?.value?.frozen == true) return
        try {
            val value = paymentCommandFromDraft(requireNotNull(scope), state.value.ui.form.values)
            if (value is PaymentCommand.Confirm) {
                require(value.transaction.value.date in LocalDate.of(1900, 1, 1)..today())
                val planned = state.value.ui.form.values["plannedAmount"]?.toLongOrNull()
                if (!value.full || planned != value.transaction.value.amount) requireLedgerText(value.reason)
            }
            command = value
            mutable.value = state.value.copy(mode = PaymentsMode.REVIEW, ui = state.value.ui.copy(review = paymentReview(value, state.value.ui.selected, state.value.ui.accounts, state.value.ui.form.values), message = null))
            persist()
        } catch (_: Exception) {
            message("이름·금액·날짜·활성 계좌·전액/일부 선택과 필요한 차이 사유를 확인해 주세요.")
        }
    }
    fun submit() {
        if (invalid || busy() || state.value.mode != PaymentsMode.REVIEW || operation != null) return
        val value = command ?: return
        mutable.value = state.value.copy(phase = PaymentsPhase.SAVING)
        writeJob = viewModelScope.launch {
            val current = editor
            val draft = if (current != null) current.flush(force = true) else null
            if (current != null && (draft == null || draft.content != DraftContent.Payment(state.value.ui.form.values) || draft.scope != scope)) {
                mutable.value = state.value.copy(phase = PaymentsPhase.READY)
                message("최근 입력을 보관하지 못했어요. 초안 저장을 다시 확인해 주세요.")
                return@launch
            }
            submittedDraft = draft?.let { DraftSubmission(it.reference, it.scope) }
            operation = id()
            command = value
            editor?.freeze()
            persist()
            performWrite(false)
        }
    }
    private suspend fun performWrite(retry: Boolean) {
        try {
            val receipt = if (retry) {
                when (val result = payments.attempt(requireNotNull(context), requireNotNull(operation))) {
                    is PaymentAttempt.Committed -> result.receipt

                    is PaymentAttempt.Rejected -> throw PaymentRejectedException(result.rejection)

                    is PaymentAttempt.Prepared -> payments.retry(requireNotNull(context), requireNotNull(operation))

                    PaymentAttempt.Missing -> payments.submit(requireNotNull(context), requireNotNull(operation), requireNotNull(command), submittedDraft)

                    PaymentAttempt.Released -> {
                        checkOutcome()
                        return
                    }
                }
            } else payments.submit(requireNotNull(context), requireNotNull(operation), requireNotNull(command), submittedDraft)
            accept(receipt)
        } catch (cancelled: CancellationException) {
            if (!invalid) mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED)
            throw cancelled
        } catch (rejected: PaymentRejectedException) {
            accept(rejected.rejection)
        } catch (_: Exception) {
            checkOutcome()
        }
    }
    fun check(retry: Boolean = false) {
        if (invalid || busy() || operation == null || state.value.phase != PaymentsPhase.UNCONFIRMED) return
        if (retry && !state.value.resultAbsent) return
        mutable.value = state.value.copy(phase = PaymentsPhase.CHECKING, resultAbsent = false)
        writeJob = viewModelScope.launch { if (retry) performWrite(true) else checkOutcome() }
    }
    private suspend fun checkOutcome() {
        if (invalid) return
        mutable.value = state.value.copy(mode = PaymentsMode.RESULT, phase = PaymentsPhase.CHECKING, resultAbsent = false)
        try {
            when (val result = payments.attempt(requireNotNull(context), requireNotNull(operation))) {
                is PaymentAttempt.Committed -> accept(result.receipt)

                is PaymentAttempt.Rejected -> accept(result.rejection)

                is PaymentAttempt.Prepared -> {
                    require(result.request.context == context && result.request.operationId == operation && result.request.command == command)
                    mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED, resultAbsent = true)
                }

                PaymentAttempt.Missing -> mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED, resultAbsent = true)

                PaymentAttempt.Released -> mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED, resultAbsent = true, ui = state.value.ui.copy(message = "이 요청은 명시 해제됐어요. 원래 요청을 재실행하지 않고 입력을 다시 확인해 주세요."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!invalid) mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED, resultAbsent = false)
        }
    }
    private fun accept(receipt: WriteReceipt) {
        if (invalid) return
        val expected = command ?: return
        if (receipt.operationId != operation || receipt.ledgerRevision <= 0 || receipt.entities != paymentResultEntities(expected)) {
            mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            return
        }
        mutable.value = state.value.copy(mode = PaymentsMode.RESULT, phase = PaymentsPhase.SAVED, resultAbsent = false)
    }
    private fun accept(value: PaymentRejection) {
        if (invalid) return
        if (value.context != context || value.operationId != operation) {
            mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            return
        }
        mutable.value = state.value.copy(mode = PaymentsMode.RESULT, phase = PaymentsPhase.REJECTED, resultAbsent = false)
    }
    fun releaseForEditing() {
        if (busy() || !state.value.resultAbsent || operation == null) return
        mutable.value = state.value.copy(phase = PaymentsPhase.CHECKING)
        writeJob = viewModelScope.launch {
            try {
                when (val result = payments.release(requireNotNull(context), requireNotNull(operation), requireNotNull(command))) {
                    is PaymentAttempt.Committed -> accept(result.receipt)

                    is PaymentAttempt.Rejected -> accept(result.rejection)

                    PaymentAttempt.Released -> {
                        if (command is PaymentCommand.ChangeItem) {
                            toMonth()
                            return@launch
                        }
                        operation = null
                        submittedDraft = null
                        command = null
                        mutable.value = state.value.copy(mode = if (scope?.kind == DraftKind.PAYMENT_RULE) PaymentsMode.RULE_FORM else PaymentsMode.TRANSACTION_FORM, phase = PaymentsPhase.READY, resultAbsent = false)
                        persist()
                        startEditor(saved[DRAFT_ID])
                    }

                    else -> checkOutcome()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(phase = PaymentsPhase.UNCONFIRMED, resultAbsent = false)
            }
        }
    }
    fun recover(id: String) {
        if (busy()) return
        mutable.value = state.value.copy(phase = PaymentsPhase.CHECKING)
        viewModelScope.launch {
            try {
                val result = payments.attempt(requireNotNull(context), id)
                val request = (result as? PaymentAttempt.Prepared)?.request ?: preparedRequests[id]
                if (request == null) {
                    mutable.value = state.value.copy(mode = PaymentsMode.MONTH)
                    load()
                    return@launch
                }
                val (scope, fields) = paymentDraftForCommand(request.command)
                this@PaymentsViewModel.scope = scope
                operation = request.operationId
                command = request.command
                submittedDraft = request.draft
                saved[DRAFT_ID] = request.draft?.reference?.id
                editor?.freeze()
                editorJob?.cancel()
                editor = null
                mutable.value = state.value.copy(mode = PaymentsMode.RESULT, phase = PaymentsPhase.UNCONFIRMED, resultAbsent = true, draft = null, ui = state.value.ui.copy(form = PaymentFormUiState("이전 납입 요청", fields.fields), review = paymentReview(request.command, null, state.value.ui.accounts)))
                persist()
                when (result) {
                    is PaymentAttempt.Committed -> accept(result.receipt)
                    is PaymentAttempt.Rejected -> accept(result.rejection)
                    else -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(phase = PaymentsPhase.BLOCKED)
            }
        }
    }
    fun finish() {
        if (busy() || state.value.finishing || state.value.phase !in listOf(PaymentsPhase.SAVED, PaymentsPhase.REJECTED)) return
        mutable.value = state.value.copy(finishing = true)
        viewModelScope.launch {
            try {
                when (ledger.finishSubmissionRecovery(requireNotNull(context), requireNotNull(operation))) {
                    FinishSubmissionRecoveryResult.Finished, FinishSubmissionRecoveryResult.AlreadyInactive -> toMonth()
                    else -> message("결과 확인을 마치지 못했어요. 기존 기록은 보존됩니다.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message("결과 확인을 마치지 못했어요. 같은 결과를 다시 확인해 주세요.")
            } finally {
                mutable.value = state.value.copy(finishing = false)
            }
        }
    }
    fun back(onClose: () -> Unit) {
        if (state.value.picker != null) {
            picker(null)
            return
        }
        if (busy()) {
            message("처리 중이에요. 원래 요청의 결과를 확인하고 있어요.")
            return
        }
        if (state.value.phase == PaymentsPhase.UNCONFIRMED) {
            message("원래 요청의 결과를 먼저 확인해 주세요. 결과가 없을 때만 명시적으로 요청을 해제하고 수정할 수 있어요.")
            return
        }
        when (state.value.mode) {
            PaymentsMode.MONTH -> onClose()

            PaymentsMode.DETAIL, PaymentsMode.RESULT -> viewModelScope.launch { toMonth() }

            PaymentsMode.CHOOSE_TRANSACTION -> {
                mutable.value = state.value.copy(mode = PaymentsMode.DETAIL)
                persist()
            }

            PaymentsMode.REVIEW -> {
                if (command is PaymentCommand.ChangeItem) {
                    mutable.value = state.value.copy(mode = PaymentsMode.DETAIL)
                    scope = null
                    command = null
                } else mutable.value = state.value.copy(mode = if (scope?.kind == DraftKind.PAYMENT_RULE) PaymentsMode.RULE_FORM else PaymentsMode.TRANSACTION_FORM)
                persist()
            }

            else -> mutable.value = state.value.copy(showExit = true)
        }
    }
    fun keepEditing() {
        mutable.value = state.value.copy(showExit = false)
    }
    fun dismissMessage() {
        mutable.value = state.value.copy(ui = state.value.ui.copy(message = null))
    }
    fun linkedTransactionId(): String? = selectedItem()?.transactionId
    fun leaveForm(discard: Boolean) {
        if (busy()) return
        mutable.value = state.value.copy(phase = PaymentsPhase.SAVING)
        viewModelScope.launch {
            val current = editor
            if (current != null && current.state.value.phase == DraftEditorPhase.EDITING) {
                val success = if (discard) current.discardCurrent() else current.flush(force = true) != null
                if (!success) {
                    mutable.value = state.value.copy(phase = PaymentsPhase.READY)
                    message("초안을 보관하지 못했어요. 최근 입력을 확인해 주세요.")
                    return@launch
                }
            }
            toMonth()
        }
    }
    private suspend fun toMonth() {
        editor?.freeze()
        editorJob?.cancel()
        editor = null
        scope = null
        operation = null
        command = null
        submittedDraft = null
        selected = null
        saved[DRAFT_ID] = null
        mutable.value = state.value.copy(mode = PaymentsMode.MONTH, phase = PaymentsPhase.LOADING, draft = null, showExit = false, picker = null, resultAbsent = false)
        persist()
        load()
    }
    private fun message(value: String) {
        mutable.value = state.value.copy(ui = state.value.ui.copy(message = value))
    }
    private fun persist() {
        val ctx = context ?: return
        val current = state.value
        saved[KEY] = Bundle().apply {
            putString("ledger", ctx.ledgerId)
            putString("generation", ctx.storageGeneration)
            putString("entry", entryId)
            putString("month", month)
            putString("selected", selected)
            putString("mode", current.mode.name)
            putString("title", current.ui.form.title)
            putBundle("fields", Bundle().apply { current.ui.form.values.forEach { (key, value) -> putString(key, value) } })
            scope?.let { putBundle("scope", DraftSavedState.scope(it)) }
            putString("operation", operation)
            submittedDraft?.let { putBundle("submittedDraft", DraftSavedState.submission(it)) }
        }
    }
    companion object {
        internal const val KEY = "paymentsEntry"
        internal const val DRAFT_ID = "paymentsDraftId"
    }
}
