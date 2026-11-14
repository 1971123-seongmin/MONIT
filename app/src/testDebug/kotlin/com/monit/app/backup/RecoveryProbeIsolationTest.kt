package com.monit.app.backup

import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class RecoveryProbeIsolationTest {
    /** 일반 앱에는 오류 주입 설정과 합성 provider가 없어야 한다. APK 검사와 함께 사용한다. */
    @Test fun debugHasNoFaultSetterOrSyntheticProvider() {
        val names = RecoveryCopyProbe::class.java.declaredMethods.map { it.name }
        assertTrue(names.none { it.contains("failurePoint", true) || it.contains("injected", true) })
        for (type in listOf("ProbeDocumentsProvider", "ProbeDocumentMode", "ProbeStatistics")) {
            assertFailsWith<ClassNotFoundException> { Class.forName("com.monit.app.backup.probe.$type") }
        }
    }
}
