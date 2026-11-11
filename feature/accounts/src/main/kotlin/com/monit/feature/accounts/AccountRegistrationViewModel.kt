package com.monit.feature.accounts

import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.CreateAccount
import com.monit.core.model.LedgerConflictException
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.WriteReceipt
import com.monit.feature.accounts.model.AccountSubmissionCodec
import com.monit.feature.accounts.model.SubmittedAccountState
import com.monit.feature.accounts.model.matches
import com.monit.feature.accounts.model.toAccountUiState
import com.monit.feature.accounts.model.validateAccountDraft
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
    private var submitted: SubmittedAccountState? = null
    private val mutableState = MutableStateFlow(AccountRegistrationState(newDraft()))
    val state = mutableState.asStateFlow()

    init {
        val ledger = context
        if (ledger == null) {
            block(AccountProblem.CONTEXT_REQUIRED)
        } else {
            val restored = runCatching {
                savedState.get<Any>(SUBMISSION)?.let { AccountSubmissionCodec.decode(it, ledger) }
            }
            if (restored.isFailure) {
                block(AccountProblem.INVALID_RECOVERY)
            } else {
                submitted = restored.getOrNull()
                submitted?.let {
                    savedState[SUBMISSION] = AccountSubmissionCodec.encode(it)
                    mutableState.value = mutableState.value.copy(draft = it.draft())
                }
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

            AccountRegistrationAction.CheckResult -> retry(allowWrite = false)

            AccountRegistrationAction.RetrySave -> retry(allowWrite = true)

            AccountRegistrationAction.RetryInitialization -> {
                val ledger = context
                if (ledger != null && state.value.saveState == AccountSaveState.InitializationFailed) {
                    mutableState.value = state.value.copy(saveState = AccountSaveState.Initializing)
                    viewModelScope.launch { initialize(ledger) }
                }
            }

            AccountRegistrationAction.ReloadSavedAccount -> {
                val intent = submitted
                if (intent != null && state.value.phase == AccountSavePhase.SAVED &&
                    state.value.readPhase == AccountReadPhase.FAILED
                ) {
                    mutableState.value = state.value.copy(readState = AccountReadState.Loading)
                    viewModelScope.launch { readAccount(intent) }
                }
            }

            AccountRegistrationAction.StartNew -> {
                if (state.value.phase == AccountSavePhase.SAVED) {
                    savedState.remove<Any>(SUBMISSION)
                    submitted = null
                    mutableState.value = AccountRegistrationState(newDraft(), AccountSaveState.Editing)
                }
            }
        }
    }

    private suspend fun initialize(ledger: LedgerContext) {
        try {
            repository.initialize(ledger)
            val intent = submitted
            if (intent == null) {
                mutableState.value = state.value.copy(saveState = AccountSaveState.Editing)
            } else {
                checkResult(intent, allowWrite = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
        } catch (error: Exception) {
            Log.w(TAG, "initialize: ${error.javaClass.simpleName}")
            mutableState.value = state.value.copy(
                saveState = AccountSaveState.InitializationFailed,
            )
        }
    }

    private fun edit(change: AccountDraftUiState.() -> AccountDraftUiState) {
        if (state.value.editable) {
            mutableState.value = state.value.copy(draft = state.value.draft.change(), inputError = null)
        }
    }

    private fun submit() {
        val ledger = context ?: return
        if (!state.value.editable) return
        val draft = state.value.draft
        val error = validateAccountDraft(draft, today())
        if (error != null) {
            mutableState.value = state.value.copy(inputError = error)
            return
        }
        val intent = SubmittedAccountState(
            ledger,
            UUID.randomUUID().toString(),
            CreateAccount(
                id = UUID.randomUUID().toString(),
                anchorId = UUID.randomUUID().toString(),
                name = draft.name,
                nature = draft.nature,
                anchorDate = LocalDate.parse(draft.anchorDate),
                unknownReason = draft.unknownReason,
            ),
        )
        // 하나의 saved-state 값으로 제출 스냅샷을 보관한다. 미제출 입력폼의 영속 초안은 아니다.
        savedState[SUBMISSION] = AccountSubmissionCodec.encode(intent)
        submitted = intent
        mutableState.value = state.value.copy(saveState = AccountSaveState.Saving(intent))
        viewModelScope.launch { write(intent) }
    }

    private fun retry(allowWrite: Boolean) {
        val unconfirmed = state.value.saveState as? AccountSaveState.Unconfirmed ?: return
        mutableState.value = state.value.copy(saveState = AccountSaveState.CheckingResult(unconfirmed.submission))
        viewModelScope.launch { checkResult(unconfirmed.submission, allowWrite) }
    }

    private suspend fun write(intent: SubmittedAccountState) {
        val receipt = try {
            repository.createAccount(intent.context, intent.operationId, intent.value)
        } catch (cancelled: CancellationException) {
            unconfirmed(intent)
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
            return
        } catch (error: Exception) {
            Log.w(TAG, "write: ${error.javaClass.simpleName}")
            checkResult(intent, allowWrite = false)
            return
        }
        accept(intent, receipt)
    }

    private suspend fun checkResult(intent: SubmittedAccountState, allowWrite: Boolean) {
        mutableState.value = state.value.copy(saveState = AccountSaveState.CheckingResult(intent))
        val receipt = try {
            repository.receipt(intent.context, intent.operationId)
        } catch (cancelled: CancellationException) {
            unconfirmed(intent)
            throw cancelled
        } catch (_: LedgerConflictException) {
            block(AccountProblem.CONFLICT)
            return
        } catch (error: Exception) {
            Log.w(TAG, "receipt: ${error.javaClass.simpleName}")
            unconfirmed(intent)
            return
        }
        when {
            receipt != null -> accept(intent, receipt)

            allowWrite -> {
                mutableState.value = state.value.copy(saveState = AccountSaveState.Saving(intent))
                write(intent)
            }

            else -> unconfirmed(intent)
        }
    }

    private suspend fun accept(intent: SubmittedAccountState, receipt: WriteReceipt) {
        if (!receipt.matches(intent)) {
            block(AccountProblem.RESULT_MISMATCH)
            return
        }
        mutableState.value = state.value.copy(
            saveState = AccountSaveState.Saved(intent.operationId, intent.value.id),
            readState = AccountReadState.Loading,
        )
        readAccount(intent)
    }

    private suspend fun readAccount(intent: SubmittedAccountState) {
        try {
            val snapshot = repository.snapshot(intent.context, today())
            val account = snapshot.accounts.single { it.id == intent.value.id }
            val model = account.toAccountUiState(snapshot.balances)
            if (submitted != intent) return
            mutableState.value = state.value.copy(
                readState = AccountReadState.Ready(model),
            )
        } catch (cancelled: CancellationException) {
            if (submitted == intent) mutableState.value = state.value.copy(readState = AccountReadState.Failed)
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "snapshot: ${error.javaClass.simpleName}")
            // 조회 실패가 확인된 저장 성공을 취소하거나 새 저장을 유발하지 않는다.
            if (submitted == intent) mutableState.value = state.value.copy(readState = AccountReadState.Failed)
        }
    }

    private fun newDraft() = AccountDraftUiState(anchorDate = today().minusDays(1).toString())
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun unconfirmed(intent: SubmittedAccountState) {
        mutableState.value = state.value.copy(
            saveState = AccountSaveState.Unconfirmed(intent),
        )
    }
    private fun block(problem: AccountProblem) {
        mutableState.value = state.value.copy(saveState = AccountSaveState.Unavailable(problem))
    }

    companion object {
        private const val TAG = "AccountRegistration"
        const val LEDGER_ID = "ledgerId"
        const val STORAGE_GENERATION = "storageGeneration"
        internal const val SUBMISSION = "accountSubmission"
    }
}
