package com.monit.app.notifications

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 계측 격리로 일반 앱의 알림 수신기 등록을 제거하지 않는지 병합 manifest를 대조한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class)
class ReminderManifestTest {
    /** 일반 debug 앱은 두 수신기와 package 교체 후 재조정 경로를 그대로 등록한다. */
    @Test fun mergedProductManifestKeepsBothReminderReceivers() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("com.monit.app", app.packageName)
        val manager = app.packageManager
        val names = manager.getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS).receivers.orEmpty().map { it.name }
        assertTrue("com.monit.app.notifications.ReminderAlarmReceiver" in names)
        assertTrue("com.monit.app.notifications.ReminderSystemReceiver" in names)
        val resolved = manager.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName), 0)
        assertTrue(resolved.any { it.activityInfo.name == "com.monit.app.notifications.ReminderSystemReceiver" })
        assertNull(manager.resolveContentProvider("com.monit.app.qa.performance.backup.probe", 0))
    }
}
