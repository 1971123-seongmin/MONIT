package com.monit.feature.accounts

import android.os.Bundle
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.AccountNature
import com.monit.core.model.CreateAccount
import com.monit.core.model.FinishSubmissionRecoveryResult
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
    private var context = runCatching {
        LedgerContext(
            requireNotNull(savedState.get<String>(LEDGER_ID)),
            requireNotNull(savedState.get<String>(STORAGE_GENERATION)),
        )
    }.getOrNull()
    private var submitted: SubmittedAccountState? = null
    private var invalidBinding = false
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
                if (submitted == null) {
                    val draft = runCatching { savedState.get<Bundle>(DRAFT)?.let { decodeDraft(it, ledger) } }
                    if (draft.isFailure) {
                        invalidBinding = true
                        block(AccountProblem.INVALID_RECOVERY)
                    } else draft.getOrNull()?.let { mutableState.value = state.value.copy(draft = it) }
                }
                submitted?.let {
                    savedState[SUBMISSION] = AccountSubmissionCodec.encode(it)
                    mutableState.value = mutableState.value.copy(draft = it.draft())
                }
                if (!invalidBinding) viewModelScope.launch { initialize(ledger) }
            }
        }
    }

    /** 앱이 검증한 context를 최초 한 번 연결한다. 복구에 남은 원래 세대는 덮어쓰지 않는다. */
    fun bind(ledger: LedgerContext) {
        if (context != null) {
            if (context != ledger) {
                invalidBinding = true
                block(AccountProblem.INVALID_RECOVERY)
            }
            return
        }
        if (savedState.contains(LEDGER_ID) || savedState.contains(STORAGE_GENERATION) ||
            savedState.contains(SUBMISSION) || savedState.contains(DRAFT)
        ) {
            invalidBinding = true
            block(AccountProblem.INVALID_RECOVERY)
            return
        }
        context = ledger
        savedState[LEDGER_ID] = ledger.ledgerId
        savedState[STORAGE_GENERATION] = ledger.storageGeneration
        mutableState.value = state.value.copy(saveState = AccountSaveState.Initializing)
        viewModelScope.launch { initialize(ledger) }
    }

    /** 저장 완료 화면에서 사용자가 확인한 하나의 참조만 종료한다. */
    fun finish(onFinished: (String) -> Unit) {
        if (invalidBinding) return
        val intent = submitted ?: return
        if (state.value.saveState !is AccountSaveState.Saved || state.value.finishing) return
        mutableState.value = state.value.copy(finishing = true, completionError = false)
        viewModelScope.launch {
            try {
                val result = repository.finishSubmissionRecovery(intent.context, intent.operationId)
                if (invalidBinding) return@launch
                when (result) {
                    FinishSubmissionRecoveryResult.Finished, FinishSubmissionRecoveryResult.AlreadyInactive -> onFinished(intent.value.id)
                    is FinishSubmissionRecoveryResult.NotReady -> mutableState.value = state.value.copy(completionError = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(completionError = true)
            } finally {
                mutableState.value = state.value.copy(finishing = false)
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
                    savedState.remove<Any>(DRAFT)
                    submitted = null
                    mutableState.value = AccountRegistrationState(newDraft(), AccountSaveState.Editing)
                }
            }
        }
    }

    private suspend fun initialize(ledger: LedgerContext) {
        if (invalidBinding) return
        try {
            // 화면 진입은 메타데이터를 생성/수리하지 않는 조회 경계다.
            repository.submissionRecovery(ledger)
            if (invalidBinding) return
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
            val ledger = context ?: return
            val draft = state.value.draft
            savedState[DRAFT] = Bundle().apply {
                putString(LEDGER_ID, ledger.ledgerId)
                putString(STORAGE_GENERATION, ledger.storageGeneration)
                putString("name", draft.name)
                putString("nature", draft.nature.name)
                putString("date", draft.anchorDate)
                putString("reason", draft.unknownReason)
            }
        }
    }

    private fun submit() {
        val ledger = context ?: return
        if (!state.value.editable) return
        val draft = state.value.draft
        val error = validateAccountDraft(draft, today())
        if (error != null) {
            mutableState.value = state.value.copy(inputError = error, validationAttempt = state.value.validationAttempt + 1)
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
        if (invalidBinding) return
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
        if (invalidBinding) return
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
        if (invalidBinding) return
        when {
            receipt != null -> accept(intent, receipt)

            allowWrite -> {
                mutableState.value = state.value.copy(saveState = AccountSaveState.Saving(intent))
                write(intent)
            }

            else -> unconfirmed(intent, resultAbsent = true)
        }
    }

    private suspend fun accept(intent: SubmittedAccountState, receipt: WriteReceipt) {
        if (invalidBinding) return
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
            if (submitted != intent || invalidBinding) return
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
    private fun decodeDraft(bundle: Bundle, ledger: LedgerContext): AccountDraftUiState {
        require(bundle.getString(LEDGER_ID) == ledger.ledgerId && bundle.getString(STORAGE_GENERATION) == ledger.storageGeneration)
        return AccountDraftUiState(
            name = requireNotNull(bundle.getString("name")),
            nature = AccountNature.valueOf(requireNotNull(bundle.getString("nature"))),
            anchorDate = requireNotNull(bundle.getString("date")),
            unknownReason = requireNotNull(bundle.getString("reason")),
        )
    }
    private fun today(): LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private fun unconfirmed(intent: SubmittedAccountState, resultAbsent: Boolean = false) {
        if (invalidBinding) return
        mutableState.value = state.value.copy(
            saveState = AccountSaveState.Unconfirmed(intent),
            resultAbsent = resultAbsent,
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
        internal const val DRAFT = "accountDraft"
    }
}
