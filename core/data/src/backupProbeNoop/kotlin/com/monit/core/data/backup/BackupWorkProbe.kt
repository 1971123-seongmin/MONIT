package com.monit.core.data.backup

/** 일반 변형에는 설정·장애 주입 입구가 없다. */
internal class BackupWorkProbe {
    fun start(event: BackupProbeEvent) = Unit

    fun end(event: BackupProbeEvent) = Unit

    fun checkpoint(point: BackupCheckpoint) = Unit

    fun provider(event: BackupProbeEvent, name: String) = Unit
}
