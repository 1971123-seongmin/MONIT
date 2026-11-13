package com.monit.core.data.backup

import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BackupProbeIsolationTest {
    /** 일반 변형에 설정 API나 performance 전용 bridge/fixture가 섞이면 실패한다. */
    @Test fun debugHasOnlyNonConfigurableNoop() {
        val names = BackupWorkProbe::class.java.declaredMethods.map { it.name }
        assertTrue(names.none { it.startsWith("configure") || it.startsWith("snapshot") || it.startsWith("providerSnapshot") })
        for (type in listOf("BackupProbeSession", "BackupProbeFixtures", "ProbeKdfGate", "ProbeFailure", "ProbeRecord")) {
            assertFailsWith<ClassNotFoundException> { Class.forName("com.monit.core.data.backup.$type") }
        }
    }
}
