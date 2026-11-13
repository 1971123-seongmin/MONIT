package com.monit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.PaymentRepository
import com.monit.core.model.PaymentStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
    private val payments: PaymentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<HomeState>(HomeState.Loading)
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null
    private var generation = 0L

    fun refresh(context: LedgerContext) {
        val request = ++generation
        readJob?.cancel()
        mutableState.value = HomeState.Loading
        readJob = viewModelScope.launch {
            try {
                val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                val snapshot = repository.snapshot(context, today)
                val monthly = payments.month(context, java.time.YearMonth.from(today).toString())
                require(snapshot.revision == monthly.ledgerRevision)
                val ui = withContext(contexts.default) {
                    val format = NumberFormat.getIntegerInstance(Locale.KOREA)
                    HomeUiState(
                        "${today.year}년 ${today.monthValue}월 전체",
                        format.format(snapshot.income) + "원",
                        format.format(snapshot.expense) + "원",
                        "완료 " + monthly.items.count { it.active && it.status == PaymentStatus.COMPLETED } + " · 미완료 " + monthly.items.count { it.active && it.status == PaymentStatus.PENDING } + " · 재확인 " + monthly.items.count { it.active && it.status == PaymentStatus.RECONFIRM },
                        monthly.items.map { item ->
                            HomePaymentUiState(
                                item.reference.id,
                                item.terms.name,
                                "${item.dueDate} · ${format.format(item.terms.amount)}원",
                                when {
                                    !item.active -> "일정 종료로 비활성"
                                    item.status == PaymentStatus.COMPLETED -> "완료 · 수동 확인"
                                    item.status == PaymentStatus.SKIPPED -> "건너뜀"
                                    item.status == PaymentStatus.RECONFIRM -> "재확인 필요"
                                    else -> "미완료"
                                },
                            )
                        },
                    )
                }
                if (request == generation) mutableState.value = HomeState.Ready(ui)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutableState.value = HomeState.Failed
            }
        }
    }
}
