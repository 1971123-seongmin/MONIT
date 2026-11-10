package com.monit.feature.accounts

import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.AccountNature
import com.monit.core.model.CreateAccount
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.WriteReceipt
import com.monit.core.model.requireLedgerId
import com.monit.core.model.requireLedgerText
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

@HiltViewModel
class AccountRegistrationViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val context = runCatching {
        LedgerContext(
            requireNotNull(savedState.get<String>(LEDGER_ID)),
            requireNotNull(savedState.get<String>(STORAGE_GENERATION)),
        )
    }.getOrNull()
    private var submitted: SubmittedAccount? = null
    private val mutableState = MutableStateFlow(AccountRegistrationUiState(newDraft()))
    val uiState = mutableState.asStateFlow()

    init {
        val ledger = context
        if (ledger == null) {
            block(AccountProblem.CONTEXT_REQUIRED)
        } else {
            val restored = runCatching { restoreSubmission(ledger) }
            if (restored.isFailure) {
                block(AccountProblem.INVALID_RECOVERY)
            } else {
                submitted = restored.getOrNull()
                submitted?.let { mutableState.value = mutableState.value.copy(draft = it.draft()) }
                viewModelScope.launch { initialize(ledger) }
            }
        }
    }

    /** UI 이벤트는 Main에서 직렬 처리한다. 저장 작업을 예약하기 전에 중복 제출을 차단한다. */
    @MainThread
    fun onAction(action: AccountRegistrationAction) {
        when (action) {
            is AccountRegistrationAction.NameChanged -> edit { copy(name = action.value) }
            is AccountRegistrationAction.NatureChanged -> edit { copy(nature = action.value) }
            is AccountRegistrationAction.AnchorDateChanged -> edit { copy(anchorDate = action.value) }
            is AccountRegistrationAction.UnknownReasonChanged -> edit { copy(unknownReason = action.value) }
            AccountRegistrationAction.Submit -> submit()
            AccountRegistrationAction.Retry -> retry()
            AccountRegistrationAction.ReloadSavedAccount -> {
                val intent = submitted
                if (intent != null && uiState.value.phase == AccountSavePhase.SAVED &&
                    uiState.value.readPhase == AccountReadPhase.FAILED
                ) {
                    mutableState.value = uiState.value.copy(readPhase = AccountReadPhase.LOADING)
                    viewModelScope.launch { readAccount(intent) }
                }
            }
            AccountRegistrationAction.StartNew -> {
                if (uiState.value.phase == AccountSavePhase.SAVED) {
                    savedState.remove<ArrayList<String>>(SUBMISSION)
                    submitted = null
                    mutableState.value = AccountRegistrationUiState(newDraft(), AccountSavePhase.EDITING)
                }
            }
        }
    }

    private suspend fun initialize(ledger: LedgerContext) {
        try {
            repository.initialize(ledger)
            val intent = submitted
            if (intent == null) {
                mutableState.value = uiState.value.copy(phase = AccountSavePhase.EDITING, problem = null)
            } else {
                checkResult(intent, allowWrite = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
        } catch (_: Exception) {
            mutableState.value = uiState.value.copy(
                phase = AccountSavePhase.RETRY_AVAILABLE, problem = AccountProblem.INITIALIZATION_FAILED,
            )
        }
    }

    private fun edit(change: AccountDraft.() -> AccountDraft) {
        if (uiState.value.editable) {
            mutableState.value = uiState.value.copy(draft = uiState.value.draft.change(), inputError = null)
        }
    }

    private fun submit() {
        val ledger = context ?: return
        if (!uiState.value.editable) return
        val draft = uiState.value.draft
        val error = validate(draft)
        if (error != null) {
            mutableState.value = uiState.value.copy(inputError = error)
            return
        }
        val intent = SubmittedAccount(
            ledger,
            UUID.randomUUID().toString(),
            CreateAccount(
                id = UUID.randomUUID().toString(), anchorId = UUID.randomUUID().toString(),
                name = draft.name, nature = draft.nature,
                anchorDate = LocalDate.parse(draft.anchorDate), unknownReason = draft.unknownReason,
            ),
        )
        // 하나의 saved-state 값으로 제출 스냅샷을 보관한다. 미제출 입력폼의 영속 초안은 아니다.
        savedState[SUBMISSION] = intent.encode()
        submitted = intent
        mutableState.value = uiState.value.copy(phase = AccountSavePhase.SAVING, problem = null)
        viewModelScope.launch { write(intent) }
    }

    private fun retry() {
        val ledger = context ?: return
        if (uiState.value.phase != AccountSavePhase.RETRY_AVAILABLE) return
        if (uiState.value.problem == AccountProblem.INITIALIZATION_FAILED) {
            mutableState.value = uiState.value.copy(phase = AccountSavePhase.INITIALIZING, problem = null)
            viewModelScope.launch { initialize(ledger) }
        } else {
            val intent = submitted ?: return
            mutableState.value = uiState.value.copy(phase = AccountSavePhase.CHECKING_RESULT, problem = null)
            viewModelScope.launch { checkResult(intent, allowWrite = true) }
        }
    }

    private suspend fun write(intent: SubmittedAccount) {
        val receipt = try {
            repository.createAccount(intent.context, intent.operationId, intent.value)
        } catch (cancelled: CancellationException) {
            unconfirmed()
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
            return
        } catch (_: Exception) {
            checkResult(intent, allowWrite = false)
            return
        }
        accept(intent, receipt)
    }

    private suspend fun checkResult(intent: SubmittedAccount, allowWrite: Boolean) {
        mutableState.value = uiState.value.copy(phase = AccountSavePhase.CHECKING_RESULT, problem = null)
        val receipt = try {
            repository.receipt(intent.context, intent.operationId)
        } catch (cancelled: CancellationException) {
            unconfirmed()
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
            return
        } catch (_: Exception) {
            unconfirmed()
            return
        }
        when {
            receipt != null -> accept(intent, receipt)
            allowWrite -> {
                mutableState.value = uiState.value.copy(phase = AccountSavePhase.SAVING)
                write(intent)
            }
            else -> unconfirmed()
        }
    }

    private suspend fun accept(intent: SubmittedAccount, receipt: WriteReceipt) {
        val expected = setOf("accounts" to intent.value.id, "anchors" to intent.value.anchorId)
        if (receipt.operationId != intent.operationId || receipt.ledgerRevision < 1 ||
            receipt.entities.size != 2 || receipt.entities.any { it.revision < 1 } ||
            receipt.entities.map { it.collection to it.id }.toSet() != expected
        ) {
            block(AccountProblem.RESULT_MISMATCH)
            return
        }
        mutableState.value = uiState.value.copy(
            phase = AccountSavePhase.SAVED, problem = null, savedAccountId = intent.value.id,
            readPhase = AccountReadPhase.LOADING,
        )
        readAccount(intent)
    }

    private suspend fun readAccount(intent: SubmittedAccount) {
        try {
            val snapshot = repository.snapshot(intent.context, today())
            val account = snapshot.accounts.single { it.id == intent.value.id }
            val balance = snapshot.balances.getValue(account.id)
            if (submitted != intent) return
            mutableState.value = uiState.value.copy(
                account = AccountUiModel(
                    account.id, account.name, account.nature, account.anchor.date, balance, account.anchor.unknownReason,
                ),
                readPhase = AccountReadPhase.READY,
            )
        } catch (cancelled: CancellationException) {
            if (submitted == intent) mutableState.value = uiState.value.copy(readPhase = AccountReadPhase.FAILED)
            throw cancelled
        } catch (_: Exception) {
            // 조회 실패가 확인된 저장 성공을 취소하거나 새 저장을 유발하지 않는다.
            if (submitted == intent) mutableState.value = uiState.value.copy(readPhase = AccountReadPhase.FAILED)
        }
    }

    private fun validate(draft: AccountDraft): AccountInputError? = when {
        runCatching { requireLedgerText(draft.name) }.isFailure -> AccountInputError.NAME
        runCatching {
            val date = LocalDate.parse(draft.anchorDate)
            require(date.toString() == draft.anchorDate)
            require(date >= LocalDate.of(1900, 1, 1) && date < today())
        }.isFailure -> AccountInputError.ANCHOR_DATE
        runCatching { requireLedgerText(draft.unknownReason) }.isFailure -> AccountInputError.UNKNOWN_REASON
        else -> null
    }

    private fun restoreSubmission(ledger: LedgerContext): SubmittedAccount? {
        val values = savedState.get<ArrayList<String>>(SUBMISSION) ?: return null
        require(values.size == 10 && values[0] == "1")
        require(values[1] == ledger.ledgerId && values[2] == ledger.storageGeneration)
        return SubmittedAccount(
            ledger, requireLedgerId(values[3]),
            CreateAccount(values[4], values[5], values[6], AccountNature.valueOf(values[7]),
                LocalDate.parse(values[8]), values[9]),
        )
    }

    private fun newDraft() = AccountDraft(anchorDate = today().minusDays(1).toString())
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun unconfirmed() {
        mutableState.value = uiState.value.copy(
            phase = AccountSavePhase.RETRY_AVAILABLE, problem = AccountProblem.RESULT_UNCONFIRMED,
        )
    }
    private fun block(problem: AccountProblem) {
        mutableState.value = uiState.value.copy(phase = AccountSavePhase.UNAVAILABLE, problem = problem)
    }

    companion object {
        const val LEDGER_ID = "ledgerId"
        const val STORAGE_GENERATION = "storageGeneration"
        internal const val SUBMISSION = "accountSubmission"
    }
}

private data class SubmittedAccount(
    val context: LedgerContext,
    val operationId: String,
    val value: CreateAccount,
) {
    fun draft() = AccountDraft(value.name, value.nature, value.anchorDate.toString(), value.unknownReason)
    fun encode() = arrayListOf(
        "1", context.ledgerId, context.storageGeneration, operationId, value.id, value.anchorId,
        value.name, value.nature.name, value.anchorDate.toString(), value.unknownReason,
    )
}
