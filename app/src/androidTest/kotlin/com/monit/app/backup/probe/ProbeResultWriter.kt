package com.monit.app.backup.probe

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.monit.core.data.backup.ProbeFixturePack
import com.monit.core.data.backup.ProbeRecord
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class ProbeResultWriter(app: Context, pack: ProbeFixturePack, suite: String) {
    private val file = File(pack.directory, suite + "-" + UUID.randomUUID() + ".jsonl")
    val smoke = InstrumentationRegistry.getArguments().getString("smoke") == "true"
    val count = if (smoke) 1 else 20

    init {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testInfo = app.packageManager.getApplicationInfo(instrumentation.context.packageName, 0)
        val args = InstrumentationRegistry.getArguments()
        val head = args.getString("sourceHead").orEmpty()
        require(smoke || head.matches(Regex("[0-9a-f]{40}"))) { "Source head handoff required" }
        val targetHash = sha(File(app.applicationInfo.sourceDir))
        val testHash = sha(File(testInfo.sourceDir))
        require(smoke || targetHash.equals(args.getString("targetApkSha"), true)) { "Installed target APK differs from handoff" }
        require(smoke || testHash.equals(args.getString("testApkSha"), true)) { "Installed test APK differs from handoff" }
        @Suppress("DEPRECATION")
        val certificates = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            .map { certificate -> MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
        val header = JSONObject().put("schemaVersion", 1).put("suite", suite).put("sourceHeadFromHandoff", head)
            .put("targetApkSha256", targetHash).put("testApkSha256", testHash).put("package", app.packageName)
            .put("uid", Process.myUid()).put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL)
            .put("debuggable", app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("signingCertificateSha256", JSONArray(certificates)).put("signaturesMatch", true)
            .put("maxHeapBytes", Runtime.getRuntime().maxMemory()).put("pssBeforeKb", Debug.getPss())
            .put("usableSpaceBeforeBytes", pack.directory.usableSpace)
            .put("fixtureProfile", pack.info.profile).put("fixtureSha256", pack.info.sourceSha256)
            .put("fixtureBytes", pack.info.sourceBytes).put("recordTotal", pack.info.recordTotal)
            .put("recordCounts", JSONObject(pack.info.recordCounts)).put("smokeOnly", smoke)
            .put("timingSamplesRequired", if (suite in setOf("crypto", "restore")) 20 else 0).put("heapAbsolutePeakGuaranteed", false)
        check(file.createNewFile()) { "Raw artifact already exists" }
        append(header)
        instrumentation.sendStatus(0, Bundle().apply { putString("probeRunId", pack.directory.name) })
    }

    fun append(value: JSONObject) {
        file.appendText(value.toString() + "\n", Charsets.UTF_8)
    }

    fun finish(value: JSONObject) {
        append(value.put("kind", "summary").put("pssAfterKb", Debug.getPss()))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("probeArtifact", file.parentFile?.name + "/" + file.name)
            },
        )
    }

    companion object {
        fun events(values: List<ProbeRecord>) = JSONArray(
            values.map { event ->
                JSONObject().put("event", event.event).put("start", event.start).put("nanos", event.nanos).put("threadId", event.threadId).put("mainThread", event.mainThread)
            },
        )

        fun duration(values: List<ProbeRecord>, name: String): Long {
            val events = values.filter { it.event == name }
            check(events.size == 2 && events[0].start && !events[1].start) { "Missing or repeated phase: $name" }
            return events[1].nanos - events[0].nanos
        }

        private fun sha(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
