package com.monit.app.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.model.LedgerContext
import com.monit.feature.home.reminders.ReminderSettingsRoute
import com.monit.feature.home.reminders.ReminderSettingsViewModel

@Composable
internal fun ReminderSettingsContent(
    context: LedgerContext,
    onBack: () -> Unit,
    onReschedule: () -> Unit,
) {
    val platform = LocalContext.current
    val model: ReminderSettingsViewModel = hiltViewModel()
    var rationale by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    val activity = remember(platform) { platform.activity() }
    val openSettings: (Boolean) -> Unit = { channel ->
        settingsFailed = !openReminderSettings(platform, channel)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onReschedule()
        model.refresh()
    }
    val request: () -> Unit = {
        if (Build.VERSION.SDK_INT < 33 || platform.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onReschedule()
            model.refresh()
        } else if (activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true) rationale = true
        else if (requested || activity == null) openSettings(false)
        else {
            requested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    ReminderSettingsRoute(
        context = context,
        onBack = onBack,
        onRequestPermission = request,
        onOpenSettings = openSettings,
        onReschedule = onReschedule,
        viewModel = model,
    )

    if (rationale) {
        MonitDialog(
            onDismissRequest = { rationale = false },
            title = { Text("납입 알림 권한") },
            actions = {
                MonitButton(
                    text = "나중에",
                    onClick = { rationale = false },
                )

                MonitButton(
                    text = "계속",
                    onClick = {
                        rationale = false
                        requested = true
                        if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            },
        ) {
            Text("직접 확인할 납입을 하루 요약으로 알려드려요. 허용하지 않아도 기록과 수동 납입 확인은 계속 이용할 수 있어요.")
        }
    }

    if (settingsFailed) {
        MonitDialog(
            onDismissRequest = { settingsFailed = false },
            title = { Text("알림 설정을 열지 못했어요") },
            actions = {
                MonitButton(
                    text = "확인",
                    onClick = { settingsFailed = false },
                )
            },
        ) {
            Text("기기 설정의 MONIT 알림에서 권한·앱·채널 상태를 확인해 주세요. 금융 기록은 그대로예요.")
        }
    }
}

internal fun openReminderSettings(context: Context, channel: Boolean): Boolean = try {
    val intent = Intent(if (channel) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (channel) intent.putExtra(Settings.EXTRA_CHANNEL_ID, REMINDER_CHANNEL)
    if (context.activity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

private fun Context.activity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
