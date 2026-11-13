package com.monit.app.notifications

import android.Manifest
import android.app.*
import android.content.Intent
import android.net.Uri
import com.monit.app.MainActivity
import com.monit.core.model.*
import java.time.*
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidReminderPlatformTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(1), id(2))
    private val now = Instant.parse("2026-11-13T00:01:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val configuration = ReminderConfiguration(context, true, 1, true, false, now.plusSeconds(60))
    private val delivery = ReminderDelivery(id(4), LocalDate.of(2026, 11, 13), listOf(PaymentReminder(id(5), "2026-11", ReminderStage.DUE, now.minusSeconds(60))))
    private fun plan() = ReminderPlan(configuration, 1, id(3), configuration.nextAt, delivery, false)

    /** 일반 부정확 일회 예약과 명시·불변 PendingIntent만 사용하고 끄면 취소한다. */
    @Test fun inexactOneShotAlarmHasPrivateExplicitImmutableIdentityAndCancels() {
        val app: Application = RuntimeEnvironment.getApplication()
        val platform = AndroidReminderPlatform(app, clock)
        platform.createChannel()
        platform.schedule(plan())
        val manager = app.getSystemService(AlarmManager::class.java)
        val alarm = assertNotNull(shadowOf(manager).peekNextScheduledAlarm())
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(now.plusSeconds(60).toEpochMilli(), alarm.triggerAtTime)
        assertEquals(0L, alarm.interval)
        assertTrue(alarm.allowWhileIdle)
        val pending = shadowOf(alarm.operation)
        assertTrue(pending.isImmutable)
        assertTrue(pending.isBroadcastIntent)
        assertEquals(ReminderAlarmReceiver::class.java.name, pending.savedIntent.component!!.className)
        assertEquals(context to id(3), alarmRequest(pending.savedIntent))
        platform.schedule(plan().copy(nextAt = null))
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }

    /** 잠금화면/일반 문구에 금융값을 넣지 않고 동일 ID를 교체하며 직접 Activity 조회만 연다. */
    @Test fun notificationIsGenericPrivateReplaceableAndOpensActivityWithoutTrampoline() {
        val app: Application = RuntimeEnvironment.getApplication()
        val platform = AndroidReminderPlatform(app, clock)
        platform.createChannel()
        platform.post(delivery)
        platform.post(delivery)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("납입 확인", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("확인할 납입이 있어요. 앱에서 직접 확인해 주세요.", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("앱에서 직접 확인해 주세요.", notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isActivityIntent)
        assertTrue(pending.isImmutable)
        assertEquals(MainActivity::class.java.name, pending.savedIntent.component!!.className)
        assertEquals(delivery.token, reminderOpenToken(pending.savedIntent))
        platform.cancelNotification()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** 앱/채널 차단은 실제 플랫폼 조회에서 구분하며 표시 권한을 추정하지 않는다. */
    @Test fun blockedAppAndChannelAreReadFromNotificationManager() {
        val app: Application = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "합성 차단 채널", NotificationManager.IMPORTANCE_NONE))
        val platform = AndroidReminderPlatform(app, clock)
        platform.createChannel()
        assertFalse(platform.read().channelEnabled)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(platform.read().appEnabled)
        assertFailsWith<IllegalStateException> { platform.post(delivery) }
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** Android13 런타임 권한의 거부·철회와 실제 허용을 매번 확인한다. */
    @Test
    @Config(sdk = [33])
    fun runtimePermissionDenialAndGrantAreCheckedOnEachOperation() {
        val app: Application = RuntimeEnvironment.getApplication()
        val platform = AndroidReminderPlatform(app, clock)
        platform.createChannel()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(platform.read().permissionGranted)
        assertFailsWith<SecurityException> { platform.post(delivery) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(platform.read().permissionGranted)
        platform.post(delivery)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(platform.read().permissionGranted)
    }

    /** 알 수 없는 action·URI/권한 위임·형식 오류는 내부 조회 경로로 전달하지 않는다. */
    @Test fun malformedOrRedirectingIntentsAreRejected() {
        assertNull(reminderOpenToken(Intent(Intent.ACTION_VIEW).putExtra(REMINDER_TOKEN, id(4))))
        assertNull(reminderOpenToken(Intent(REMINDER_OPEN_ACTION).setData(Uri.parse("https://example.com")).putExtra(REMINDER_TOKEN, id(4))))
        assertNull(reminderOpenToken(Intent(REMINDER_OPEN_ACTION).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(REMINDER_TOKEN, id(4))))
        assertNull(reminderOpenToken(Intent(REMINDER_OPEN_ACTION).putExtra(REMINDER_TOKEN, "invalid")))
        assertNull(alarmRequest(Intent(REMINDER_ALARM_ACTION).putExtra(REMINDER_TOKEN, id(3))))
        assertNull(alarmRequest(Intent("untrusted")))
    }

    /** 알람/시스템 수신기는 비공개이고 정확 알림 특별 권한이나 외부 deep link를 열지 않는다. */
    @Test fun manifestKeepsReceiversPrivateAndDeclaresOnlyRequiredNotificationPermissions() {
        val app: Application = RuntimeEnvironment.getApplication()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_RECEIVERS or android.content.pm.PackageManager.GET_PERMISSIONS)
        val receivers = assertNotNull(packageInfo.receivers).filter { it.name.contains("Reminder") }
        val permissions = assertNotNull(packageInfo.requestedPermissions)
        assertEquals(2, receivers.size)
        assertTrue(receivers.none { it.exported || it.directBootAware })
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.USE_EXACT_ALARM" in permissions)
    }
}
