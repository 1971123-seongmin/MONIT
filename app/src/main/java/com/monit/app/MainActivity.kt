package com.monit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.monit.app.notifications.REMINDER_TOKEN
import com.monit.app.notifications.ReminderCoordinator
import com.monit.app.notifications.reminderOpenToken
import com.monit.app.ui.MonitLauncher
import com.monit.core.common.coroutines.CoroutineScopeProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var reminders: ReminderCoordinator

    @Inject internal lateinit var scopes: CoroutineScopeProvider
    private var reminderToken by mutableStateOf<String?>(null)
    private var consumedToken: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedToken = savedInstanceState?.getString("consumedReminder")
        reminderToken = (savedInstanceState?.getString("pendingReminder") ?: reminderOpenToken(intent))?.takeIf { it != consumedToken }
        enableEdgeToEdge()
        setContent {
            MonitLauncher(
                reminderToken = reminderToken,
                onReminderConsumed = { token ->
                    if (reminderToken == token) {
                        consumedToken = token
                        reminderToken = null
                        intent.removeExtra(REMINDER_TOKEN)
                    }
                },
                onReschedule = { scopes.ioScope.launch { reminders.reconcile() } },
            )
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val token = reminderOpenToken(intent) ?: return
        if (token == consumedToken) return
        setIntent(intent)
        reminderToken = token
    }
    override fun onResume() {
        super.onResume()
        scopes.ioScope.launch { reminders.reconcile() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingReminder", reminderToken)
        outState.putString("consumedReminder", consumedToken)
        super.onSaveInstanceState(outState)
    }
}
