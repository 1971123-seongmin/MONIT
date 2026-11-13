package com.monit.app.backup.probe

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** 제품 Application/알림 observer를 시작하지 않고 실제 플랫폼 Context를 사용하는 합성 계측 runner. */
class BackupProbeRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application = super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        check(targetContext.packageName == "com.monit.app.qa.performance")
        check(context.packageName == "com.monit.app.qa.performance.test")
        check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Non-debuggable measurement target required" }
        check(targetContext.packageManager.checkSignatures(targetContext.packageName, context.packageName) == PackageManager.SIGNATURE_MATCH) { "Instrumentation signing mismatch" }
        super.onCreate(arguments)
    }
}
