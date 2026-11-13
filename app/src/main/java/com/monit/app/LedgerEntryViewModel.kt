package com.monit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.LedgerEntryResult
import com.monit.core.model.LedgerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 저장소 원본이 확인된 경우만 제품 host를 연다. 복구 필요를 빈 신규 장부로 바꾸지 않는다. */
@HiltViewModel
class LedgerEntryViewModel @Inject constructor(private val repository: LedgerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<LedgerEntryState>(LedgerEntryState.Loading)
    val state = mutableState.asStateFlow()
    private var readJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        if (readJob?.isActive == true || state.value is LedgerEntryState.Ready) return
        mutableState.value = LedgerEntryState.Loading
        readJob = viewModelScope.launch {
            try {
                mutableState.value = when (val entry = repository.openLedger()) {
                    is LedgerEntryResult.Ready -> LedgerEntryState.Ready(entry.context)
                    is LedgerEntryResult.RecoveryRequired -> LedgerEntryState.RecoveryRequired(entry.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = LedgerEntryState.Failed
            }
        }
    }

    /** 복원 후 이전 Ready/back stack을 재사용하지 않고 저장소의 새 generation을 다시 읽는다. */
    fun afterRestore() {
        readJob?.cancel()
        readJob = null
        mutableState.value = LedgerEntryState.Loading
        retry()
    }
}
