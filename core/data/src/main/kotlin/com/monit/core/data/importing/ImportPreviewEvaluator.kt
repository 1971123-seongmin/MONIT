package com.monit.core.data.importing

import com.monit.core.model.*
import java.math.BigInteger
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

/** 금융 명령을 실행하지 않는다. source mapping 부재에서 EXISTS를 생성하지 않는다. */
internal data class ImportRelations(val debit: List<String>, val credit: List<String>)

object ImportPreviewEvaluator {
    suspend fun evaluate(input: ImportPreviewInput, context: LedgerContext, snapshot: LedgerSnapshot, today: LocalDate): ImportPreviewReport = evaluate(input, context, snapshot, today, emptyMap(), false)

    internal suspend fun evaluate(input: ImportPreviewInput, context: LedgerContext, snapshot: LedgerSnapshot, today: LocalDate, grouped: Map<String, ImportRelations>, retainDuplicateCandidate: Boolean): ImportPreviewReport {
        if (input.target != null && input.target != context) throw ImportFileException(ImportFileProblem.WRONG_LEDGER)
        require(snapshot.revision >= 0)
        val index = PreviewIndex(input, snapshot, grouped, retainDuplicateCandidate)
        val duplicates = input.rows.filter { it.sourceKey != null }.groupBy { it.sourceKey }.filterValues { it.size > 1 }
        val mapped = input.rows.map { row ->
            currentCoroutineContext().ensureActive()
            assess(row, duplicates[row.sourceKey], index, today)
        }
        val repeatedCandidates = mapped.filter { it.decision == ImportDecision.CANDIDATE }
            .groupBy { it.candidate?.let { candidate -> listOf(candidate.kind, candidate.date, candidate.amount, candidate.debitAccountId, candidate.creditAccountId) } }
            .filterValues { group -> group.size > 1 && !(group.size == 2 && group.all { grouped[it.source.locator] != null } && group.map { grouped[it.source.locator] }.distinct().size == 1) }.values.flatten().map { it.source.locator }.toSet()
        val rows = mapped.map { row ->
            if (row.source.locator in repeatedCandidates) row.copy(decision = ImportDecision.HELD, reasons = row.reasons + ImportHoldReason.POSSIBLE_DUPLICATE, candidate = row.candidate.takeIf { retainDuplicateCandidate })
            else row
        }
        val candidates = rows.filter { it.decision == ImportDecision.CANDIDATE }.map { checkNotNull(it.candidate) }
        val totals = candidates.groupBy { it.date.toString().take(7) to it.kind }.map { (key, group) ->
            ImportCandidateTotal(key.first, key.second, group.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value.amount) }, group.size)
        }.sortedWith(compareBy({ it.month }, { it.kind.name }))
        val effects = mutableMapOf<String, BigInteger>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val amount = BigInteger.valueOf(candidate.amount)
            candidate.debitAccountId?.let { effects[it] = (effects[it] ?: BigInteger.ZERO) - amount }
            candidate.creditAccountId?.let { effects[it] = (effects[it] ?: BigInteger.ZERO) + amount }
        }
        val fingerprint = ImportPreviewParser.digest(
            listOf(
                input.transformerVersion,
                input.inputSha256,
                input.snapshotSha256,
                input.mappingSha256,
                context.ledgerId,
                context.storageGeneration,
                snapshot.revision.toString(),
                rows.joinToString(";") { it.decision.name + ":" + it.reasons.map { reason -> reason.name }.sorted().joinToString(",") },
            ).joinToString("\n"),
        )
        val names = snapshot.accounts.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single().name }
        return ImportPreviewReport(input, context, snapshot.revision, rows, totals, effects.toMap(), names, fingerprint)
    }

    private suspend fun assess(row: ImportSourceRow, duplicate: List<ImportSourceRow>?, index: PreviewIndex, today: LocalDate): ImportRowAssessment {
        fun held(reason: ImportHoldReason) = ImportRowAssessment(row, ImportDecision.HELD, setOf(reason), "원본 행 ${row.locator}", null, null, null, null)
        if (!row.rawSha256.matches(Regex("[0-9a-f]{64}")) || ImportPreviewParser.digest(row.rawJson) != row.rawSha256) return held(ImportHoldReason.RAW_DIGEST)
        val raw = try {
            ImportPreviewParser.strictObject(row.rawJson)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return held(ImportHoldReason.RAW_FORMAT)
        }
        val reasons = mutableSetOf<ImportHoldReason>()
        if (duplicate != null) reasons += if (duplicate.map { it.rawSha256 }.distinct().size > 1) ImportHoldReason.CONFLICTING_SOURCE_KEY else ImportHoldReason.REPEATED_SOURCE_KEY
        val choice = index.choices[row.locator]
        if (choice?.decision == ImportDecision.EXCLUDED && choice.confirmed && reasons.isEmpty()) {
            return ImportRowAssessment(row, ImportDecision.EXCLUDED, emptySet(), "원본 행 ${row.locator}", null, null, null, null)
        }
        if (choice != null) reasons += ImportHoldReason.EXPLICIT_HOLD
        if (!index.targetConfirmed) reasons += ImportHoldReason.ACCOUNT_MAPPING_REQUIRED
        val fields = index.fields[row.sourceNamespace to row.entityKind]
        if (row.entityKind != "transaction" || fields == null) return held(ImportHoldReason.UNSUPPORTED_KIND).copy(reasons = reasons + ImportHoldReason.UNSUPPORTED_KIND)
        val coverage = index.coverage[row.sourceNamespace to row.entityKind]
        if (coverage == null || coverage.paginationComplete != true || !coverage.missingPropertiesKnown || coverage.missingProperties.isNotEmpty()) reasons += ImportHoldReason.SOURCE_INCOMPLETE
        val title = raw.opt(fields.title) as? String
        val dateText = raw.opt(fields.date) as? String
        val kindText = raw.opt(fields.category) as? String
        val amountValue = raw.opt(fields.amount)
        val amountText = if (amountValue is String || amountValue is Number) amountValue.toString() else null
        if (title == null || kindText == null || dateText == null || amountText == null) reasons += ImportHoldReason.RAW_FORMAT
        try {
            requireLedgerText(title ?: "", allowBlank = true)
        } catch (_: IllegalArgumentException) {
            reasons += ImportHoldReason.RAW_FORMAT
        }
        val kind = when (kindText) {
            "수입" -> TransactionKind.INCOME
            "지출" -> TransactionKind.EXPENSE
            "이체" -> TransactionKind.TRANSFER
            else -> null
        }
        if (kind == null) reasons += ImportHoldReason.UNSUPPORTED_KIND
        val date = try {
            LocalDate.parse(dateText).also { require(it.toString() == dateText && it >= LocalDate.of(1900, 1, 1) && it <= today) }
        } catch (_: Exception) {
            reasons += ImportHoldReason.INVALID_DATE
            null
        }
        val amount = amountText?.takeIf { (amountValue is String || amountValue is Int || amountValue is Long) && it.matches(Regex("[0-9]{1,12}")) }
            ?.toLongOrNull()?.takeIf { it in 1..999_999_999_999L }
        if (amount == null) reasons += ImportHoldReason.INVALID_AMOUNT
        if (!fields.currencyConfirmed || fields.currency != "KRW") reasons += ImportHoldReason.CURRENCY_UNCONFIRMED
        if (row.relationsComplete != true || row.relationNamespace != fields.walletNamespace) reasons += ImportHoldReason.RELATION_UNAVAILABLE
        fun relation(name: String): List<String>? {
            val array = raw.opt(name) as? JSONArray ?: run {
                reasons += ImportHoldReason.RELATION_UNAVAILABLE
                return null
            }
            val ids = (0 until array.length()).map { array.opt(it) as? String }
            if (ids.any { it.isNullOrBlank() }) {
                reasons += ImportHoldReason.RELATION_UNAVAILABLE
                return null
            }
            if (ids.size > 1) reasons += ImportHoldReason.AMBIGUOUS_RELATION
            return ids.filterNotNull()
        }
        val originalDebit = relation(fields.debitRelation)
        val originalCredit = relation(fields.creditRelation)
        val debit = index.grouped[row.locator]?.debit ?: originalDebit
        val credit = index.grouped[row.locator]?.credit ?: originalCredit
        fun account(ids: List<String>?): String? {
            if (!index.targetConfirmed) return null
            if (ids == null) return null
            val sourceId = ids.singleOrNull() ?: run {
                reasons += ImportHoldReason.ACCOUNT_MAPPING_REQUIRED
                return null
            }
            val matches = index.accounts[fields.walletNamespace to sourceId].orEmpty()
            if (matches.size > 1) {
                reasons += ImportHoldReason.AMBIGUOUS_RELATION
                return null
            }
            val mapped = matches.singleOrNull()?.takeIf { it.confirmed } ?: run {
                reasons += ImportHoldReason.ACCOUNT_MAPPING_REQUIRED
                return null
            }
            val ledgerAccount = index.activeAccounts[mapped.accountId]?.singleOrNull()
            if (ledgerAccount == null) reasons += ImportHoldReason.ACCOUNT_UNAVAILABLE
            return ledgerAccount?.id
        }
        val debitId = if (kind != TransactionKind.INCOME) account(debit) else null
        val creditId = if (kind != TransactionKind.EXPENSE) account(credit) else null
        if ((kind == TransactionKind.INCOME && !debit.isNullOrEmpty()) || (kind == TransactionKind.EXPENSE && !credit.isNullOrEmpty())) reasons += ImportHoldReason.AMBIGUOUS_RELATION
        if (kind == TransactionKind.TRANSFER && debitId != null && debitId == creditId) reasons += ImportHoldReason.SAME_TRANSFER_ACCOUNT
        if (kind != null && date != null && amount != null && LocalKey(kind, date, amount, debitId, creditId) in index.existing) reasons += ImportHoldReason.POSSIBLE_DUPLICATE
        val candidate = if (reasons.isEmpty() || (index.retainDuplicateCandidate && reasons == setOf(ImportHoldReason.POSSIBLE_DUPLICATE))) ImportTransactionCandidate(checkNotNull(kind), checkNotNull(date), checkNotNull(amount), debitId, creditId, checkNotNull(title)) else null
        return ImportRowAssessment(
            row,
            if (reasons.isEmpty() && candidate != null) ImportDecision.CANDIDATE else ImportDecision.HELD,
            reasons.toSet(),
            title ?: "원본 행 ${row.locator}",
            amountText,
            kindText,
            fields.currency,
            candidate,
        )
    }

    private data class LocalKey(val kind: TransactionKind, val date: LocalDate, val amount: Long, val debit: String?, val credit: String?)

    private class PreviewIndex(input: ImportPreviewInput, snapshot: LedgerSnapshot, val grouped: Map<String, ImportRelations>, val retainDuplicateCandidate: Boolean) {
        val targetConfirmed = input.target != null
        val choices = input.choices.associateBy { it.locator }
        val fields = input.fields.associateBy { it.namespace to it.entityKind }
        val coverage = input.coverage.associateBy { it.namespace to it.entityKind }
        val accounts = input.accounts.groupBy { it.walletNamespace to it.sourceId }
        val activeAccounts = snapshot.accounts.filter { it.status == AccountStatus.ACTIVE }.groupBy { it.id }
        val existing = snapshot.transactions.map { LocalKey(it.kind, it.date, it.amount, it.debitAccountId, it.creditAccountId) }.toSet()
    }
}
