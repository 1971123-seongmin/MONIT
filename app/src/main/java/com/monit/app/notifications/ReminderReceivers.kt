package com.monit.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.monit.core.common.coroutines.CoroutineScopeProvider
import com.monit.core.model.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal fun alarmRequest(intent: Intent): Pair<LedgerContext, String>? = try {
    if (intent.action != REMINDER_ALARM_ACTION || intent.data != null) null
    else LedgerContext(requireNotNull(intent.getStringExtra(REMINDER_LEDGER)), requireNotNull(intent.getStringExtra(REMINDER_GENERATION))) to requireNotNull(intent.getStringExtra(REMINDER_TOKEN)).also(::requireLedgerId)
} catch (_: RuntimeException) {
    null
}

internal fun reminderOpenToken(intent: Intent): String? = try {
    val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    if (intent.action != REMINDER_OPEN_ACTION || intent.data != null || intent.flags and grants != 0) null
    else intent.getStringExtra(REMINDER_TOKEN)?.also(::requireLedgerId)
} catch (_: RuntimeException) {
    null
}

@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject internal lateinit var repository: ReminderRepository

    @Inject internal lateinit var coordinator: ReminderCoordinator

    @Inject internal lateinit var scopes: CoroutineScopeProvider
    override fun onReceive(context: Context, intent: Intent) {
        val request = alarmRequest(intent) ?: return
        val pending = goAsync()
        scopes.ioScope.launch {
            try {
                withTimeout(8_000) { if (repository.acceptsAlarm(request.first, request.second)) coordinator.reconcile() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* 금융 변경 없이 다음 명시/시스템 재조정까지 dirty 원본을 보존한다. */ } finally {
                pending.finish()
            }
        }
    }
}

@AndroidEntryPoint
class ReminderSystemReceiver : BroadcastReceiver() {
    @Inject internal lateinit var coordinator: ReminderCoordinator

    @Inject internal lateinit var scopes: CoroutineScopeProvider
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        scopes.ioScope.launch {
            try {
                withTimeout(8_000) { coordinator.reconcile() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* OS 이벤트 오류는 금융 성공/실패를 변경하지 않는다. */ } finally {
                pending.finish()
            }
        }
    }
}
