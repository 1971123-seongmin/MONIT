package com.monit.feature.accounts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.LedgerContext
import com.monit.core.model.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AccountsState>(AccountsState.Loading)
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null
    private var generation = 0L

    fun refresh(context: LedgerContext) {
        val request = ++generation
        readJob?.cancel()
        mutableState.value = AccountsState.Loading
        readJob = viewModelScope.launch {
            try {
                val snapshot = repository.snapshot(
                    context,
                    LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
                )
                val ui = withContext(contexts.default) { snapshot.toAccountsUiState() }
                if (request == generation) mutableState.value = AccountsState.Ready(ui)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutableState.value = AccountsState.Failed
            }
        }
    }
}
