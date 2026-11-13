package com.monit.app.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.monit.app.MainActivity
import com.monit.core.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

internal const val REMINDER_CHANNEL = "payment_reminders_v1"
internal const val REMINDER_NOTIFICATION_ID = 62001
internal const val REMINDER_ALARM_ID = 62002
internal const val REMINDER_OPEN_ACTION = "com.monit.app.OPEN_PAYMENT_REMINDER"
internal const val REMINDER_ALARM_ACTION = "com.monit.app.PAYMENT_REMINDER_ALARM"
internal const val REMINDER_TOKEN = "reminder_token"
internal const val REMINDER_LEDGER = "reminder_ledger"
internal const val REMINDER_GENERATION = "reminder_generation"

@Singleton
internal class AndroidReminderPlatform @Inject constructor(@param:ApplicationContext private val context: Context, private val clock: Clock) : ReminderPlatform {
    private val notifications get() = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val alarms get() = checkNotNull(context.getSystemService(AlarmManager::class.java))

    override fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL, "월별 납입 확인", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "직접 확인할 납입을 하루 요약으로 알려줍니다."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    override fun read(): ReminderAccess = ReminderAccess(
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        notifications.areNotificationsEnabled(),
        notifications.getNotificationChannel(REMINDER_CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true,
    )

    override fun schedule(plan: ReminderPlan) {
        val at = plan.nextAt
        if (at == null || !plan.configuration.enabled || !read().allowed) {
            cancelAlarm()
            return
        }
        val value = plan.configuration.context
        val intent = alarmIntent().putExtra(REMINDER_TOKEN, plan.token).putExtra(REMINDER_LEDGER, value.ledgerId).putExtra(REMINDER_GENERATION, value.storageGeneration)
        val pending = PendingIntent.getBroadcast(context, REMINDER_ALARM_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    override fun cancelAll() {
        cancelAlarm()
        cancelNotification()
    }
    override fun cancelNotification() = notifications.cancel(REMINDER_NOTIFICATION_ID)

    override fun post(delivery: ReminderDelivery) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Notification permission denied")
        check(read().allowed) { "Notification access changed" }
        val timeout = Duration.between(clock.instant(), delivery.members.maxOf { it.scheduledAt }.plusSeconds(86_400)).toMillis()
        check(timeout > 0) { "Reminder window expired" }
        val intent = Intent(context, MainActivity::class.java).setAction(REMINDER_OPEN_ACTION)
            .putExtra(REMINDER_TOKEN, delivery.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, REMINDER_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, REMINDER_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("납입 확인").setContentText("앱에서 직접 확인해 주세요.").build()
        val value = Notification.Builder(context, REMINDER_CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("납입 확인").setContentText("확인할 납입이 있어요. 앱에서 직접 확인해 주세요.")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(timeout).build()
        notifications.notify(REMINDER_NOTIFICATION_ID, value)
    }

    private fun alarmIntent() = Intent(context, ReminderAlarmReceiver::class.java).setAction(REMINDER_ALARM_ACTION)
    private fun cancelAlarm() {
        PendingIntent.getBroadcast(context, REMINDER_ALARM_ID, alarmIntent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
}
