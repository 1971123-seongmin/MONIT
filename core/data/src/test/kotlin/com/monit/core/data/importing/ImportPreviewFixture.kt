package com.monit.core.data.importing

import com.monit.core.model.*
import java.io.File
import java.time.LocalDate
import org.json.JSONObject

/** 공개 저장소의 한 합성 fixture만 사용한다. 실제 원본/앱 금융 자료를 읽지 않는다. */
internal object ImportPreviewFixture {
    val context = LedgerContext(id(1), id(2))
    val today: LocalDate = LocalDate.of(2026, 11, 12)
    fun bytes(): ByteArray = File("../../docs/contracts/fixtures/import-preview-v1-synthetic.json").readBytes()
    fun envelope() = JSONObject(bytes().toString(Charsets.UTF_8))
    fun edited(changeSnapshot: (JSONObject) -> Unit = {}, changeMapping: (JSONObject) -> Unit = {}): ByteArray {
        val envelope = envelope()
        val snapshot = JSONObject(envelope.getString("snapshotJson")).also(changeSnapshot)
        val mapping = JSONObject(envelope.getString("mappingJson")).also(changeMapping)
        return seal(snapshot.toString(), mapping.toString())
    }
    fun seal(snapshot: String, mapping: String): ByteArray = envelope()
        .put("snapshotJson", snapshot).put("snapshotSha256", ImportPreviewParser.digest(snapshot))
        .put("mappingJson", mapping).put("mappingSha256", ImportPreviewParser.digest(mapping)).toString().toByteArray(Charsets.UTF_8)
    fun row(snapshot: JSONObject, index: Int, raw: String) {
        snapshot.getJSONArray("rows").getJSONObject(index).put("rawJson", raw).put("rawSha256", ImportPreviewParser.digest(raw))
    }
    fun snapshot(revision: Long = 4, transactions: List<LedgerTransaction> = emptyList(), archived: Boolean = false) = LedgerSnapshot(
        revision,
        listOf(10, 11).map { number ->
            LedgerAccount(
                id(number),
                "합성 계좌 $number",
                AccountNature.GENERAL,
                if (archived) AccountStatus.ARCHIVED else AccountStatus.ACTIVE,
                1,
                BalanceAnchor(id(number + 10), LocalDate.of(2026, 1, 1), null, null, "미확인"),
            )
        },
        transactions,
        mapOf(id(10) to null, id(11) to null),
        0,
        0,
    )
    fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
