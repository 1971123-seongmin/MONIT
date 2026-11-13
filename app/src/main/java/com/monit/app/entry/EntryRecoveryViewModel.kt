package com.monit.app.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 신규 입력 전에 원래 context의 제출을 한 건씩 확인하는 앱 진입 경계. */
@HiltViewModel
class EntryRecoveryViewModel @Inject constructor(private val repository: LedgerRepository, private val clock: Clock) : ViewModel() {
    private var context: LedgerContext? = null
    private var invalidContext = false
    private var reading = false
    private val mutable = MutableStateFlow(EntryRecoveryState())
    val state = mutable.asStateFlow()

    fun bind(ledger: LedgerContext) {
        if (context != null) {
            if (context != ledger) {
                invalidContext = true
                mutable.value = EntryRecoveryState(loading = false, failed = true)
            }
            return
        }
        context = ledger
        reload()
    }

    fun reload() {
        if (invalidContext || reading) return
        val ledger = context ?: return
        if (state.value.loading && state.value.items.isNotEmpty()) return
        mutable.value = EntryRecoveryState()
        reading = true
        viewModelScope.launch {
            try {
                val snapshot = repository.submissionRecovery(ledger)
                require(snapshot.context == ledger)
                val records = if (snapshot.items.isNotEmpty()) repository.snapshot(ledger, LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))) else null
                val rows = snapshot.items.map { recoveryRow(it, records) }
                if (invalidContext) return@launch
                mutable.value = EntryRecoveryState(items = snapshot.items, loading = false, ready = snapshot.items.isEmpty(), ui = EntryRecoveryUiState(rows))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = EntryRecoveryState(loading = false, failed = true)
            } finally {
                reading = false
            }
        }
    }

    fun select(index: Int) {
        if (state.value.loading || index !in state.value.items.indices) return
        val item = state.value.items[index]
        val reference = item.reference(context ?: return) ?: return
        mutable.value = state.value.copy(selected = index, loading = true, receipt = null, rejection = null, paymentRejection = null, resultAbsent = false, failed = false)
        viewModelScope.launch {
            try {
                val receipt = repository.receipt(reference.context, reference.operationId)
                if (invalidContext) return@launch
                require(receipt == null || receipt.operationId == reference.operationId)
                val rejected = if (receipt == null) repository.rejection(reference.context, reference.operationId) else null
                val paymentRejected = if (receipt == null && rejected == null) repository.paymentRejection(reference.context, reference.operationId) else null
                require(paymentRejected == null || (paymentRejected.context == reference.context && paymentRejected.operationId == reference.operationId))
                require(rejected == null || (rejected.context == reference.context && rejected.operationId == reference.operationId))
                mutable.value = state.value.copy(loading = false, receipt = receipt, rejection = rejected, paymentRejection = paymentRejected, resultAbsent = receipt == null && rejected == null && paymentRejected == null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loading = false, failed = true)
            }
        }
    }

    fun retry() {
        val ledger = context ?: return
        val current = state.value
        val item = current.items.getOrNull(current.selected ?: return) ?: return
        if (current.loading || !current.resultAbsent ||
            (item !is SubmissionRecoveryItem.Pending && item !is SubmissionRecoveryItem.LegacyPending)
        ) return
        val reference = item.reference(ledger) ?: return
        mutable.value = current.copy(loading = true, failed = false, resultAbsent = false)
        viewModelScope.launch {
            try {
                // 명시 재시도 직전에도 결과를 확인하고 원래 ID와 내용을 유지한다.
                val existing = repository.receipt(reference.context, reference.operationId)
                if (invalidContext) return@launch
                val receipt = existing ?: repository.retryPending(reference.context, reference.operationId)
                if (invalidContext) return@launch
                require(receipt.operationId == reference.operationId)
                mutable.value = state.value.copy(loading = false, receipt = receipt)
            } catch (rejected: PaymentRejectedException) {
                if (!invalidContext && rejected.rejection.context == reference.context && rejected.rejection.operationId == reference.operationId) mutable.value = state.value.copy(loading = false, paymentRejection = rejected.rejection)
                else mutable.value = state.value.copy(loading = false, failed = true)
            } catch (rejected: TransactionRejectedException) {
                if (!invalidContext && rejected.rejection.context == reference.context && rejected.rejection.operationId == reference.operationId) mutable.value = state.value.copy(loading = false, rejection = rejected.rejection)
                else mutable.value = state.value.copy(loading = false, failed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loading = false, failed = true)
            }
        }
    }

    fun finish() {
        val ledger = context ?: return
        val current = state.value
        val item = current.items.getOrNull(current.selected ?: return) ?: return
        val reference = item.reference(ledger) ?: return
        if (current.loading || (current.receipt?.operationId != reference.operationId && current.rejection?.operationId != reference.operationId && current.paymentRejection?.operationId != reference.operationId)) return
        mutable.value = current.copy(loading = true, failed = false)
        viewModelScope.launch {
            try {
                val result = repository.finishSubmissionRecovery(reference.context, reference.operationId)
                if (invalidContext) return@launch
                when (result) {
                    FinishSubmissionRecoveryResult.Finished, FinishSubmissionRecoveryResult.AlreadyInactive -> reloadAfterFinish()
                    is FinishSubmissionRecoveryResult.NotReady -> mutable.value = state.value.copy(loading = false, failed = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loading = false, failed = true)
            }
        }
    }

    private fun reloadAfterFinish() {
        mutable.value = state.value.copy(loading = false)
        reload()
    }

    private fun recoveryRow(item: SubmissionRecoveryItem, records: LedgerSnapshot?): RecoveryRowUiState {
        val submission = when (item) {
            is SubmissionRecoveryItem.Pending -> item.submission
            is SubmissionRecoveryItem.LegacyPending -> item.submission
            else -> null
        }
        return when {
            submission is PendingRecoveryItem.AccountRegistration -> RecoveryRowUiState("이전 계좌 등록", "${submission.value.name} · ${submission.value.anchorDate}")

            submission is PendingRecoveryItem.TransactionUpdate -> RecoveryRowUiState("이전 거래 수정", "${submission.value.value.date} · ${submission.value.value.amount}원 · 수정 대상 revision ${submission.value.expectedRevision}")

            submission is PendingRecoveryItem.TransactionDeletion -> RecoveryRowUiState("이전 거래 삭제", "삭제 대상 revision ${submission.value.expectedRevision} · 명시 재시도 시 합계에서 제외됩니다")

            submission is PendingRecoveryItem.TransactionCreation -> RecoveryRowUiState("이전 거래 저장", "${submission.value.date} · ${submission.value.amount}원 · ${submission.value.memo}")

            submission is PendingRecoveryItem.PaymentSubmission -> RecoveryRowUiState("이전 납입 확인", "자동 반영하지 않아요. 원래 요청의 결과를 확인해 주세요.")

            item is SubmissionRecoveryItem.PaymentRejected -> RecoveryRowUiState("반영되지 않은 납입 확인", "검토한 내용이 변경되어 이 요청은 반영되지 않았습니다.")

            item is SubmissionRecoveryItem.Rejected -> RecoveryRowUiState("반영되지 않은 수정·삭제", "다른 변경이 있어 이 요청은 반영되지 않았습니다. 결과를 확인해 주세요.")

            item is SubmissionRecoveryItem.Committed -> {
                val account = records?.accounts?.find { account -> item.receipt.entities.any { it.collection == "accounts" && it.id == account.id } }
                val transaction = records?.transactions?.find { transaction -> item.receipt.entities.any { it.collection == "transactions" && it.id == transaction.id } }
                RecoveryRowUiState(
                    "저장된 결과 확인",
                    when {
                        account != null -> "${account.name} · ${account.anchor.date}"
                        transaction != null -> "${transaction.date} · ${transaction.amount}원 · ${transaction.memo}"
                        else -> "상세 내용을 특정할 수 없어요. 기존 목록에서 확인해 주세요."
                    },
                )
            }

            else -> RecoveryRowUiState("확인이 필요한 제출", "자동 실행할 수 없어요. 기존 기록은 보존됩니다.")
        }
    }

    private fun SubmissionRecoveryItem.reference(ledger: LedgerContext): SubmissionReference? = when (this) {
        is SubmissionRecoveryItem.Committed -> reference
        is SubmissionRecoveryItem.Rejected -> reference
        is SubmissionRecoveryItem.PaymentRejected -> reference
        is SubmissionRecoveryItem.Pending -> reference
        is SubmissionRecoveryItem.LegacyPending -> SubmissionReference(ledger, submission.operationId)
        is SubmissionRecoveryItem.Blocked -> null
    }
}
