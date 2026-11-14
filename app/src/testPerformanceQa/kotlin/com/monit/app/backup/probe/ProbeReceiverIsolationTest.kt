package com.monit.app.backup.probe

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 일반 Application을 쓰는 합성 계측에 제품 Hilt 수신기가 등록되지 않는지 실제 병합 manifest로 확인한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class)
class ProbeReceiverIsolationTest {
    /** 전용 provider는 유지하고 제품 알림 수신기/시스템 방송은 계측 프로세스에서 해결되지 않는다. */
    @Test fun mergedProbeManifestDoesNotRegisterProductionReceivers() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("com.monit.app.qa.performance", app.packageName)
        val manager = app.packageManager
        assertEquals("com.monit.app.backup.probe.ProbeDocumentsProvider", manager.resolveContentProvider("com.monit.app.qa.performance.backup.probe", 0)?.name)
        val names = manager.getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS).receivers.orEmpty().map { it.name }
        assertFalse("com.monit.app.notifications.ReminderAlarmReceiver" in names)
        assertFalse("com.monit.app.notifications.ReminderSystemReceiver" in names)
        val resolved = manager.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName), 0)
        assertTrue(resolved.none { it.activityInfo.name == "com.monit.app.notifications.ReminderSystemReceiver" })
    }
}
