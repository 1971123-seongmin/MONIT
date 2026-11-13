package com.monit.core.data.backup

import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** 이 변형에서 생성한 Repository 인스턴스에만 결합한다. hot path에서 객체·로그를 만들지 않는다. */
internal class BackupWorkProbe {
    private val cursor = AtomicInteger()
    private val codes = IntArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private val threads = LongArray(CAPACITY)
    private val main = BooleanArray(CAPACITY)
    private val providers = arrayOfNulls<String>(BackupProbeEvent.entries.size)
    private var enabled = false
    private var failurePoint: BackupCheckpoint? = null
    private var failure: (() -> Unit)? = null

    fun start(event: BackupProbeEvent) = record(event, true)

    fun end(event: BackupProbeEvent) = record(event, false)

    fun checkpoint(point: BackupCheckpoint) {
        if (point == failurePoint) failure?.invoke()
    }

    fun configure(point: BackupCheckpoint? = null, action: (() -> Unit)? = null) {
        failurePoint = point
        failure = action
        cursor.set(0)
        providers.fill(null)
        enabled = true
    }

    fun provider(event: BackupProbeEvent, name: String) {
        if (enabled) providers[event.ordinal] = name
    }

    fun providerSnapshot(): Map<String, String> = BackupProbeEvent.entries.mapNotNull { event -> providers[event.ordinal]?.let { event.name to it } }.toMap()

    fun snapshot(): List<ProbeRecord> {
        val count = cursor.get()
        check(count <= CAPACITY) { "Probe buffer overflow" }
        return (0 until count).map { index ->
            ProbeRecord(BackupProbeEvent.entries[codes[index] / 2].name, codes[index] % 2 == 0, times[index], threads[index], main[index])
        }
    }

    private fun record(event: BackupProbeEvent, start: Boolean) {
        if (!enabled) return
        val index = cursor.getAndIncrement()
        if (index >= CAPACITY) return
        codes[index] = event.ordinal * 2 + if (start) 0 else 1
        times[index] = SystemClock.elapsedRealtimeNanos()
        threads[index] = Thread.currentThread().id
        main[index] = Looper.myLooper() == Looper.getMainLooper()
    }

    companion object {
        const val CAPACITY = 4096
    }
}

data class ProbeRecord(val event: String, val start: Boolean, val nanos: Long, val threadId: Long, val mainThread: Boolean)
