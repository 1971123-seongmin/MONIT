package com.monit.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import com.monit.core.model.LedgerSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 선택값만 saved state에 보존한다. 한 월의 표시 캐시는 재개/재시도 때 Room 원본으로 교체한다. */
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val savedState: SavedStateHandle,
    private val contexts: CoroutineContextProvider,
) : ViewModel() {
    private fun today() = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
    private val restoredDate = savedState.get<String>("selectedDate")
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { it.year in 1900..9999 } ?: today()
    private val mutableState = MutableStateFlow(
        TransactionsState(
            TransactionsUiState(
                YearMonth.from(restoredDate),
                restoredDate,
                today(),
                calendar = savedState["calendar"] ?: true,
                selectedKindIndex = (savedState.get<Int>("kind") ?: 0).coerceIn(0, 3),
                selectedAccountId = savedState["account"],
            ),
        ),
    )
    val state = mutableState.asStateFlow()
    private var context: LedgerContext? = null
    private var readJob: Job? = null
    private var generation = 0L
    private var cachedSnapshot: LedgerSnapshot? = null
    private var cachedMonth: YearMonth? = null

    /** 재개된 실제 entry의 context로 조회한다. 요청 교체 시 이전 결과는 반영하지 않는다. */
    fun refresh(context: LedgerContext) {
        this.context = context
        refresh()
    }

    fun refresh() = load(useCache = false)

    private fun load(useCache: Boolean) {
        val ledger = context ?: return
        val request = ++generation
        readJob?.cancel()
        val selection = state.value.ui.copy(
            today = today(),
            income = "",
            expense = "",
            dates = emptyList(),
            rows = emptyList(),
        )
        val cached = cachedSnapshot.takeIf { useCache && cachedMonth == selection.month }
        if (!useCache) cachedSnapshot = null
        mutableState.value = TransactionsState(selection)
        readJob = viewModelScope.launch {
            try {
                val snapshot = cached ?: repository.snapshot(
                    ledger,
                    minOf(selection.month.atEndOfMonth(), selection.today),
                )
                val content =
                    withContext(contexts.default) { snapshot.toTransactionsUiState(selection) }
                if (request == generation) {
                    cachedSnapshot = snapshot
                    cachedMonth = selection.month
                    mutableState.value = TransactionsState(content, TransactionsLoadState.READY)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutableState.value =
                    TransactionsState(selection, TransactionsLoadState.FAILED)
            }
        }
    }

    fun selectDate(date: LocalDate) {
        require(date.year in 1900..9999)
        if (date == state.value.ui.selectedDate) return
        savedState["selectedDate"] = date.toString()
        mutableState.value = state.value.copy(
            ui = state.value.ui.copy(
                month = YearMonth.from(date),
                selectedDate = date,
            ),
        )
        load(useCache = true)
    }

    fun changeMonth(month: YearMonth) {
        if (month.year !in 1900..9999) return
        selectDate(
            month.atDay(
                minOf(
                    state.value.ui.selectedDate.dayOfMonth,
                    month.lengthOfMonth(),
                ),
            ),
        )
    }

    fun goToToday() {
        if (state.value.ui.selectedDate == today()) refresh() else selectDate(today())
    }

    fun selectCalendar(calendar: Boolean) {
        if (calendar == state.value.ui.calendar) return
        savedState["calendar"] = calendar
        mutableState.value = state.value.copy(ui = state.value.ui.copy(calendar = calendar))
        load(useCache = true)
    }

    fun selectKind(index: Int) {
        require(index in 0..3)
        if (index == state.value.ui.selectedKindIndex) return
        savedState["kind"] = index
        mutableState.value = state.value.copy(ui = state.value.ui.copy(selectedKindIndex = index))
        load(useCache = true)
    }

    fun selectAccount(id: String?) {
        if (id == state.value.ui.selectedAccountId) return
        savedState["account"] = id
        mutableState.value = state.value.copy(ui = state.value.ui.copy(selectedAccountId = id))
        load(useCache = true)
    }
}
