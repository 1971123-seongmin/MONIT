package com.monit.app.backup.probe

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.monit.core.data.backup.BackupProbeSession
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** non-debuggable 앱의 raw를 같은 서명의 계측 상태 채널로만 꺼낸다. shell 파일 접근을 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class ProbeArtifactExportTest {
    /**
     * 목적: 정리/재실행 없이 지정한 자체 run의 JSONL 증거를 회수한다.
     * 조건: runner의 UID/서명 검증, UUID run ID, 고정 경로/확장자·16MiB 파일 상한.
     * 절차: 읽기 전용으로 chunk와 최종 SHA256/byte 수를 계측 상태에 전송한다.
     * 기대: 키·DB·합성 원문은 회수 대상이 아니며 누락/순서/변조는 호스트에서 검출한다.
     */
    @Test fun exportOneSyntheticRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        require(app.packageName == BackupProbeSession.APPLICATION_ID)
        val runId = checkNotNull(InstrumentationRegistry.getArguments().getString("artifactRunId"))
        UUID.fromString(runId)
        require(runId == UUID.fromString(runId).toString())
        val root = File(app.filesDir, "backup-probe-72").canonicalFile
        val directory = File(root, runId).canonicalFile
        require(directory.parentFile == root && directory.isDirectory)
        val files = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-z-]+-[0-9a-f-]{36}\\.jsonl")) }.sortedBy { it.name }
        check(files.isNotEmpty()) { "No raw evidence in selected run" }
        for (file in files) {
            require(file.canonicalFile.parentFile == directory && file.isFile && file.length() in 1..16 * 1024 * 1024L)
            val hash = MessageDigest.getInstance("SHA-256")
            var sequence = 0
            var total = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(12_288)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                    total += count
                    val value = JSONObject().put("runId", runId).put("file", file.name).put("sequence", sequence++)
                        .put("base64", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP))
                    instrumentation.sendStatus(0, Bundle().apply { putString("probeChunk", value.toString()) })
                }
            }
            val value = JSONObject().put("runId", runId).put("file", file.name).put("chunks", sequence).put("bytes", total)
                .put("sha256", hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            instrumentation.sendStatus(0, Bundle().apply { putString("probeFileEnd", value.toString()) })
        }
    }
}
