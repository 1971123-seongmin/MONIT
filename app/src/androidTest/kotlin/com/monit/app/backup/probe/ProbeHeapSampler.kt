package com.monit.app.backup.probe

import android.os.Debug
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** 실제 할당 heap의 표본이며 PSS나 샘플 사이의 절대 peak를 의미하지 않는다. */
internal class ProbeHeapSampler : Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "monit-backup-heap-72").apply { isDaemon = true } }
    private val cursor = AtomicInteger()
    private val time = LongArray(CAPACITY)
    private val java = LongArray(CAPACITY)
    private val native = LongArray(CAPACITY)
    private var future: ScheduledFuture<*>? = null
    private val runtime get() = Runtime.getRuntime()
    private var baselineJava = 0L
    private var baselineNative = 0L
    private var initialGc: String? = null

    fun start() {
        check(future == null)
        baselineJava = runtime.totalMemory() - runtime.freeMemory()
        baselineNative = Debug.getNativeHeapAllocatedSize()
        initialGc = Debug.getRuntimeStat("art.gc.gc-count")
        sample()
        future = executor.scheduleAtFixedRate(::sample, 5, 5, TimeUnit.MILLISECONDS)
    }

    private fun sample() {
        val index = cursor.getAndIncrement()
        if (index >= CAPACITY) return
        time[index] = SystemClock.elapsedRealtimeNanos()
        java[index] = runtime.totalMemory() - runtime.freeMemory()
        native[index] = Debug.getNativeHeapAllocatedSize()
    }

    override fun close() {
        future?.cancel(false)
        executor.shutdown()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Heap sampler did not stop" }
    }

    fun report(): JSONObject {
        check(executor.isTerminated)
        val count = minOf(cursor.get(), CAPACITY)
        val values = JSONArray()
        var combined = 0L
        var javaPeak = 0L
        var nativePeak = 0L
        var maxGap = 0L
        repeat(count) { i ->
            values.put(JSONArray(listOf(time[i], java[i], native[i])))
            javaPeak = maxOf(javaPeak, java[i] - baselineJava)
            nativePeak = maxOf(nativePeak, native[i] - baselineNative)
            combined = maxOf(combined, java[i] + native[i] - baselineJava - baselineNative)
            if (i > 0) maxGap = maxOf(maxGap, time[i] - time[i - 1])
        }
        return JSONObject().put("baselineJavaBytes", baselineJava).put("baselineNativeBytes", baselineNative)
            .put("javaObservedDeltaBytes", javaPeak).put("nativeObservedDeltaBytes", nativePeak).put("combinedObservedDeltaBytes", combined)
            .put("intervalTargetNanos", 5_000_000).put("maxGapNanos", maxGap).put("capacity", CAPACITY)
            .put("truncated", cursor.get() > CAPACITY).put("initialGcCount", initialGc ?: JSONObject.NULL)
            .put("finalGcCount", Debug.getRuntimeStat("art.gc.gc-count") ?: JSONObject.NULL)
            .put("absolutePeakProven", false).put("pssUsedForHeapVerdict", false).put("rawTimeJavaNativeBytes", values)
    }

    companion object {
        private const val CAPACITY = 30_000
    }
}
