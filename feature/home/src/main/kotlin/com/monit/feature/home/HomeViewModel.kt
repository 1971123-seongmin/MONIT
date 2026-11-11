package com.monit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
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
                val ui = withContext(contexts.default) {
                    val format = NumberFormat.getIntegerInstance(Locale.KOREA)
                    HomeUiState(
                        "${today.year}년 ${today.monthValue}월 전체",
                        format.format(snapshot.income) + "원",
                        format.format(snapshot.expense) + "원",
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
