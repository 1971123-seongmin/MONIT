package com.monit.core.data.backup

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.data.importing.*
import com.monit.core.model.*
import java.time.Clock
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** 공개 합성 seed를 만들고 실제 v3 validator를 통과시킨다. 기기 자료를 읽는 factory가 아니다. */
internal class BackupProbeFixtures(
    private val database: LedgerDatabase,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) {
    private val context = LedgerContext(id(1), id(2))
    private val ledger = RoomLedgerRepository(database, clock, contexts)
    private val day = LocalDate.of(2026, 11, 14)
    private val normalIds = mutableSetOf<String>()

    suspend fun create(profile: ProbeProfile): Pair<JSONObject, JSONObject> {
        ledger.initialize(context)
        for (number in 10..11) {
            ledger.createAccount(context, id(100 + number), CreateAccount(id(number), id(number + 10), "합성 계좌 $number", if (number == 10) AccountNature.GENERAL else AccountNature.SAVINGS, LocalDate.of(2026, 1, 1), "합성 기준"))
            ledger.confirmAnchor(context, id(200 + number), ConfirmAnchor(id(number), id(number + 10), 1, LocalDate.of(2026, 1, 1), 10_000, ""))
        }
        val count = if (profile == ProbeProfile.MIXED_LIMIT) 200 else 2
        for (number in 0 until count) {
            val target = id(1000 + number)
            normalIds += target
            ledger.createTransaction(context, id(3000 + number), CreateTransaction(target, TransactionKind.INCOME, day.minusDays(1), 1, creditAccountId = id(10)))
        }
        importedExpense()
        if (profile == ProbeProfile.MIXED_LIMIT) addOriginal()
        ledger.createTransaction(context, id(9002), CreateTransaction(id(9001), TransactionKind.INCOME, day.minusDays(1), 1, creditAccountId = id(10), memo = "합성 A 전용"))
        val current = BackupSnapshotAdapter(database, clock).capture(context)
        ledger.deleteTransaction(context, id(9003), DeleteTransaction(id(9001), 1))
        var source = BackupSnapshotAdapter(database, clock).capture(context)
        if (profile != ProbeProfile.SMALL) {
            val remaining = 50_000 - source.getJSONObject("manifest").getInt("recordTotal")
            check(remaining > 0)
            database.withTransaction {
                repeat(remaining) { number -> database.backupDao().fence(OperationFenceEntity(id(1_000_000 + number))) }
            }
            source = BackupSnapshotAdapter(database, clock).capture(context)
        }
        if (profile == ProbeProfile.MIXED_LIMIT) {
            val base = bytes(source).size
            check(base < BackupEnvelope.MAX_PAYLOAD)
            val trial = JSONObject(source.toString())
            padMemos(trial, 1)
            val step = bytes(trial).size - base
            check(step > 0)
            val memoLength = (BackupEnvelope.MAX_PAYLOAD - base) / step
            check(memoLength in 1..16_384)
            padMemos(source, memoLength)
            padMemos(current, memoLength)
            val padding = BackupEnvelope.MAX_PAYLOAD - bytes(source).size
            check(padding in 0..16_000)
            for (root in listOf(current, source)) {
                val file = root.getJSONArray("originals").values().map { it as JSONObject }.single { it.getString("id") == id(9990) }
                file.put("fileName", "synthetic-public-padding" + "p".repeat(padding))
            }
            check(bytes(source).size == BackupEnvelope.MAX_PAYLOAD)
        }
        validate(current)
        validate(source)
        if (profile != ProbeProfile.SMALL) check(source.getJSONObject("manifest").getInt("recordTotal") == 50_000)
        val transactions = source.getJSONObject("collections").getJSONArray("transactions").values().map { it as JSONObject }
        check(transactions.count { it.isNull("deletedAt") } == count + 1)
        check(transactions.count { !it.isNull("deletedAt") } == 1)
        return current to source
    }

    private suspend fun importedExpense() {
        val raw = JSONObject().put("title", "synthetic imported expense").put("date", "2026-11-02").put("amount", "5000").put("category", "지출").put("debit", JSONArray(listOf("wallet-a"))).put("credit", JSONArray()).toString()
        val namespace = "synthetic:backup-probe:transactions"
        val wallets = "synthetic:backup-probe:wallets"
        val source = JSONObject().put("capturedAt", "2026-11-12T00:00:00Z").put("scope", "synthetic backup probe").put(
            "sources",
            JSONArray(
                listOf(
                    JSONObject().put("namespace", namespace).put("entityKind", "transaction").put("paginationComplete", true).put("missingProperties", JSONArray()),
                    JSONObject().put("namespace", wallets).put("entityKind", "wallet").put("paginationComplete", true).put("missingProperties", JSONArray()),
                ),
            ),
        ).put("rows", JSONArray(listOf(JSONObject().put("sourceNamespace", namespace).put("entityKind", "transaction").put("sourceId", "PROBE1").put("locator", "rows/0").put("rawJson", raw).put("rawSha256", BackupJson.digest(raw)).put("relationsComplete", true).put("relationNamespace", wallets)))).toString()
        val fields = JSONObject().put("namespace", namespace).put("entityKind", "transaction").put("title", "title").put("date", "date").put("amount", "amount").put("category", "category").put("debitRelation", "debit").put("creditRelation", "credit").put("walletNamespace", wallets).put("currency", "KRW").put("currencyConfirmed", true)
        val mapping = JSONObject().put("version", "backup-probe-1").put("transformerVersion", "monit-preview-1").put("target", JSONObject().put("ledgerId", context.ledgerId).put("storageGeneration", context.storageGeneration)).put("fields", JSONArray(listOf(fields))).put("accounts", JSONArray(listOf(JSONObject().put("walletNamespace", wallets).put("sourceId", "wallet-a").put("accountId", id(10)).put("confirmed", true)))).put("choices", JSONArray()).toString()
        val input = bytes(JSONObject().put("version", 1).put("snapshotJson", source).put("snapshotSha256", BackupJson.digest(source)).put("mappingJson", mapping).put("mappingSha256", BackupJson.digest(mapping)))
        val writer = RoomImportRepository(database, ledger, clock, contexts)
        val options = ImportReviewOptions(targetConfirmed = true, anchors = ledger.snapshot(context, day).accounts.map { ImportAnchorChoice(it.id, it.anchor.id, it.anchor.revision, it.anchor.date.toString(), ImportAnchorAction.KEEP, it.anchor.amount, "", true) }, confirmedRows = setOf("rows/0"))
        val review = writer.review(context, input, options)
        check(review.report.rows.single().decision == ImportDecision.CANDIDATE)
        val backup = RoomBackupRepository(database, clock, contexts)
        val exported = backup.create(context, password())
        val receipt = backup.verifyExport(context, exported.id, exported.encrypted, password())
        val bound = writer.bindBackup(context, review.token, receipt.id)
        writer.checkSource(context, bound.token, input)
        check(writer.apply(context, bound.token) {}.createdTransactions == 1)
        input.fill(0)
        exported.encrypted.fill(0)
    }

    private suspend fun addOriginal() {
        val originals = readBackupOriginals(database, context.ledgerId)
        val size = 2 * 1024 * 1024 - originals.sumOf { it.bytes.size }
        originals.forEach { it.bytes.fill(0) }
        check(size > 0)
        val raw = ByteArray(size) { ' '.code.toByte() }.also {
            it[0] = '{'.code.toByte()
            it[1] = '}'.code.toByte()
        }
        database.backupDao().original(BackupOriginalEntity(context.ledgerId, id(9990), "synthetic:backup-probe", "synthetic-public-padding", raw.size.toLong(), BackupJson.digest(raw), raw))
        raw.fill(0)
    }

    /** 처음 공개할 합성 genesis의 memo와 그 원문을 함께 구성한다. 실제 저장된 사용자의 이력을 수정하지 않는다. */
    private fun padMemos(root: JSONObject, length: Int) {
        val memo = "M".repeat(length)
        root.getJSONObject("collections").getJSONArray("transactions").values().map { it as JSONObject }.filter { it.getString("id") in normalIds }.forEach { it.put("memo", memo) }
        for (item in root.getJSONArray("submissionsEvidence").values().map { it as JSONObject }) {
            for (table in BackupLayout.evidence) {
                if (item.isNull(table)) continue
                val row = item.getJSONObject(table)
                if (!row.has("requestJson")) continue
                val command = decodeWrite(context, row.getString("requestJson"))
                if (command !is WriteCommand.Transaction || command.value.id !in normalIds) continue
                val raw = captureWrite(context, WriteCommand.Transaction(command.value.copy(memo = memo)))
                row.put("requestJson", raw).put("requestSha256", requestHash(raw))
            }
        }
    }

    private suspend fun validate(root: JSONObject) {
        val raw = bytes(root)
        try {
            BackupValidator(clock).validate(raw)
        } finally {
            raw.fill(0)
        }
    }

    companion object {
        fun id(number: Int) = "72000000-0000-0000-0000-" + number.toString().padStart(12, '0')
        fun password() = "synthetic-backup-performance-2026".toCharArray()
        fun bytes(root: JSONObject) = BackupJson.utf8(root.toString())
    }
}
