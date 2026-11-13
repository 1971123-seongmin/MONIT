package com.monit.app.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monit.core.model.LedgerContext
import com.monit.core.model.ReminderLink
import com.monit.core.model.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ReminderOpenState(val link: ReminderLink? = null, val failed: Boolean = false)

@HiltViewModel
internal class ReminderOpenViewModel @Inject constructor(private val repository: ReminderRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ReminderOpenState())
    val state = mutable.asStateFlow()
    private var key: Pair<LedgerContext, String>? = null
    private var read: Job? = null
    fun resolve(context: LedgerContext, token: String, retry: Boolean = false) {
        if (!retry && key == (context to token)) return
        read?.cancel()
        key = context to token
        mutable.value = ReminderOpenState()
        read = viewModelScope.launch {
            try {
                val link = repository.resolve(token)
                require(link != null && link.context == context)
                if (key == (context to token)) mutable.value = ReminderOpenState(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (key == (context to token)) mutable.value = ReminderOpenState(failed = true)
            }
        }
    }
    fun clear() {
        read?.cancel()
        key = null
        mutable.value = ReminderOpenState()
    }
}
