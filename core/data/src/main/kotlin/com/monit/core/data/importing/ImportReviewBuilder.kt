package com.monit.core.data.importing

import com.monit.core.data.*
import com.monit.core.model.*
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

internal class ImportReviewBuilder(private val today: LocalDate) {
    private fun id() = UUID.randomUUID().toString()

    suspend fun build(context: LedgerContext, bytes: ByteArray, options: ImportReviewOptions, snapshot: LedgerSnapshot, mappings: List<ImportMappingEntity>, fences: List<ImportSourceFenceEntity>, transactions: List<TransactionEntity>): ImportPlan {
        val original = ImportPreviewParser.parse(bytes)
        if (original.target != null && original.target != context) importFailure(ImportFileProblem.WRONG_LEDGER)
        val locators = original.rows.map { it.locator }.toSet()
        require((options.confirmedRows + options.distinctRows + options.excludedRows).all { it in locators })
        require(options.accounts.map { it.walletNamespace to it.sourceId }.distinct().size == options.accounts.size)
        require(options.anchors.map { it.accountId }.distinct().size == options.anchors.size)
        require(options.anchors.all { anchor -> snapshot.accounts.any { it.id == anchor.accountId } })
        val input = original.copy(target = context.takeIf { options.targetConfirmed }, accounts = options.accounts.ifEmpty { original.accounts })
        val grouped = grouped(input, options)
        val base = ImportPreviewEvaluator.evaluate(input, context, snapshot, today, grouped, true)
        val identities = input.rows.mapIndexed { index, row -> row.locator to importIdentity(input, row, index) }.toMap()
        val hashes = input.rows.associate { it.locator to mappingHash(input, it, options) }
        val current = mappings.associateBy { it.identity }
        val history = fences.associateBy { it.identity }
        val targets = transactions.associateBy { it.id }
        val rows = base.rows.mapIndexed { position, row ->
            currentCoroutineContext().ensureActive()
            fun held(reason: ImportHoldReason) = row.copy(decision = ImportDecision.HELD, reasons = setOf(reason), candidate = null)
            val key = checkNotNull(identities[row.source.locator])
            val mapping = current[key.hash]
            val fence = history[key.hash]
            val previousNoId = if (row.source.sourceId == null) fences.filter { it.snapshotSha256 == input.snapshotSha256 && it.locator == row.source.locator && it.position == position && JSONObject(it.keyJson).optString("namespace") == row.source.sourceNamespace && JSONObject(it.keyJson).optString("entityKind") == row.source.entityKind } else emptyList()
            when {
                row.reasons.any { it == ImportHoldReason.REPEATED_SOURCE_KEY || it == ImportHoldReason.CONFLICTING_SOURCE_KEY } -> row.copy(candidate = null)

                mapping != null && mapping.rawSha256 != row.source.rawSha256 -> held(ImportHoldReason.SOURCE_CHANGED)

                mapping != null && mapping.mappingSha256 != hashes[row.source.locator] -> held(ImportHoldReason.MAPPING_CHANGED)

                mapping != null && (fence == null || !matchingFence(mapping, fence)) -> held(ImportHoldReason.HISTORICAL_SOURCE)

                mapping != null -> {
                    val target = targets[mapping.transactionId]
                    if (target == null || target.deletedAt != null || target.revision != mapping.appliedRevision || target.financialRevision != mapping.appliedFinancialRevision || transactionPayload(target) != mapping.payloadJson || requestHash(mapping.payloadJson) != mapping.payloadSha256) held(ImportHoldReason.LOCAL_CHANGED)
                    else row.copy(decision = ImportDecision.EXISTS, reasons = emptySet(), candidate = null)
                }

                fence != null || previousNoId.isNotEmpty() -> held(ImportHoldReason.HISTORICAL_SOURCE)

                row.source.locator in options.excludedRows -> row.copy(decision = ImportDecision.EXCLUDED, reasons = emptySet(), candidate = null)

                row.candidate != null && row.reasons.all { it == ImportHoldReason.POSSIBLE_DUPLICATE } -> {
                    when {
                        ImportHoldReason.POSSIBLE_DUPLICATE in row.reasons && row.source.locator !in options.distinctRows -> held(ImportHoldReason.POSSIBLE_DUPLICATE)
                        row.source.locator !in options.confirmedRows -> held(ImportHoldReason.REVIEW_CONFIRMATION_REQUIRED)
                        else -> row.copy(decision = ImportDecision.CANDIDATE, reasons = emptySet())
                    }
                }

                else -> row
            }
        }.toMutableList()
        // 명시 묶음은 두 출처가 함께 후보이거나 함께 동일 거래의 EXISTS여야 한다.
        for (group in options.groups) {
            val values = rows.filter { it.source.locator in group.locators }
            val bothCandidates = values.size == 2 && values.all { it.decision == ImportDecision.CANDIDATE } && values.map { it.candidate?.copy(memo = "") }.distinct().size == 1
            val bothExisting = values.size == 2 && values.all { it.decision == ImportDecision.EXISTS } && values.map { current[identities[it.source.locator]?.hash]?.transactionId }.distinct().size == 1
            if (!bothCandidates && !bothExisting) for (index in rows.indices) if (rows[index].source.locator in group.locators) rows[index] = rows[index].copy(decision = ImportDecision.HELD, reasons = setOf(ImportHoldReason.INVALID_GROUP), candidate = null)
        }
        val needed = rows.filter { it.decision == ImportDecision.CANDIDATE }.flatMap { listOfNotNull(it.candidate?.debitAccountId, it.candidate?.creditAccountId) }.toSet()
        val choices = options.anchors.associateBy { it.accountId }
        val anchorsReady = needed.all { account -> validAnchorChoice(snapshot.accounts.single { it.id == account }, choices[account]) }
        if (!anchorsReady) for (index in rows.indices) if (rows[index].decision == ImportDecision.CANDIDATE) rows[index] = rows[index].copy(decision = ImportDecision.HELD, reasons = setOf(ImportHoldReason.BASIS_CONFIRMATION_REQUIRED), candidate = null)
        val children = mutableListOf<ImportPlannedChild>()
        for (choice in options.anchors.filter { it.confirmed && it.action == ImportAnchorAction.CONFIRM }) {
            require(input.accounts.any { it.accountId == choice.accountId && it.confirmed } && validAnchorChoice(snapshot.accounts.single { it.id == choice.accountId && it.status == AccountStatus.ACTIVE }, choice))
            children += ImportPlannedChild(id(), WriteCommand.Anchor(ConfirmAnchor(choice.accountId, choice.anchorId, choice.expectedRevision, LocalDate.parse(choice.date), checkNotNull(choice.amount), choice.reason)), emptyList())
        }
        val processed = mutableSetOf<String>()
        for (row in rows.filter { it.decision == ImportDecision.CANDIDATE }) {
            if (!processed.add(row.source.locator)) continue
            val group = options.groups.singleOrNull { row.source.locator in it.locators }
            val sources = group?.locators?.sorted() ?: listOf(row.source.locator)
            processed += sources
            val candidate = checkNotNull(row.candidate)
            val memo = sources.joinToString("\n") { locator -> rows.single { it.source.locator == locator }.title }.also { requireLedgerText(it, allowBlank = true) }
            children += ImportPlannedChild(id(), WriteCommand.Transaction(CreateTransaction(id(), candidate.kind, candidate.date, candidate.amount, candidate.debitAccountId, candidate.creditAccountId, memo)), sources)
        }
        val candidates = children.mapNotNull { (it.command as? WriteCommand.Transaction)?.value }
        val totals = candidates.groupBy { it.date.toString().take(7) to it.kind }.map { (key, values) -> ImportCandidateTotal(key.first, key.second, values.sumOf { BigInteger.valueOf(it.amount) }, values.size) }.sortedWith(compareBy({ it.month }, { it.kind.name }))
        val effects = mutableMapOf<String, BigInteger>()
        candidates.forEach { value ->
            value.debitAccountId?.let { effects[it] = (effects[it] ?: BigInteger.ZERO) - BigInteger.valueOf(value.amount) }
            value.creditAccountId?.let { effects[it] = (effects[it] ?: BigInteger.ZERO) + BigInteger.valueOf(value.amount) }
        }
        val fingerprint = importHash(importObject("source" to input.inputSha256, "options" to optionsJson(options), "context" to importObject("ledger" to context.ledgerId, "generation" to context.storageGeneration), "revision" to snapshot.revision.toString(), "accounts" to JSONArray(snapshot.accounts.sortedBy { it.id }.map { importObject("id" to it.id, "revision" to it.revision.toString(), "anchorRevision" to it.anchor.revision.toString()) }), "children" to JSONArray(children.map { importObject("operation" to it.operationId, "raw" to captureWrite(context, it.command)) })))
        val report = base.copy(rows = rows.toList(), totals = totals, accountEffects = effects.toMap(), fingerprint = fingerprint, plannedTransactionCount = candidates.size)
        val sourceTotals = rows.filter { it.currency != null && it.rawAmount?.matches(Regex("[0-9]{1,12}")) == true }.groupBy { checkNotNull(it.currency) }.map { (currency, values) -> ImportCurrencyTotal(currency, values.sumOf { checkNotNull(it.rawAmount).toBigInteger() }, values.size) }.sortedBy { it.currency }
        val links = input.rows.flatMap { source ->
            val field = input.fields.singleOrNull { it.namespace == source.sourceNamespace && it.entityKind == source.entityKind }
            val raw = runCatching { JSONObject(source.rawJson) }.getOrNull()
            field?.let {
                listOf(it.debitRelation, it.creditRelation).flatMap { name ->
                    (raw?.opt(name) as? JSONArray)?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }.orEmpty()
                }.map { sourceId -> input.accounts.singleOrNull { it.walletNamespace == field.walletNamespace && it.sourceId == sourceId } ?: ImportAccountMapping(field.walletNamespace, sourceId, "", false) }
            }.orEmpty()
        }.distinctBy { it.walletNamespace to it.sourceId }
        val review = ImportReview(id(), id(), report, options, snapshot.accounts, sourceTotals, options.targetConfirmed && anchorsReady, accountLinks = links)
        return ImportPlan(review, bytes.copyOf(), identities, hashes, children, id(), null)
    }

    private fun validAnchorChoice(account: LedgerAccount, choice: ImportAnchorChoice?): Boolean {
        if (choice == null || !choice.confirmed || choice.anchorId != account.anchor.id || choice.expectedRevision != account.anchor.revision || choice.date != account.anchor.date.toString()) return false
        return when (choice.action) {
            ImportAnchorAction.KEEP -> choice.amount == account.anchor.amount
            ImportAnchorAction.CONFIRM -> choice.amount != null && choice.amount in -999_999_999_999L..999_999_999_999L && runCatching { requireLedgerText(choice.reason, allowBlank = account.anchor.amount == null) }.isSuccess
        }
    }

    internal fun grouped(input: ImportPreviewInput, options: ImportReviewOptions): Map<String, ImportRelations> {
        require(options.groups.flatMap { it.locators }.distinct().size == options.groups.sumOf { it.locators.size })
        val result = mutableMapOf<String, ImportRelations>()
        for (group in options.groups) {
            require(group.confirmed && group.locators.size == 2 && group.locators.distinct().size == 2)
            val source = group.locators.map { locator -> input.rows.single { it.locator == locator } }
            val fields = source.map { row -> input.fields.single { it.namespace == row.sourceNamespace && it.entityKind == row.entityKind } }
            require(fields.map { it.walletNamespace }.distinct().size == 1 && fields.all { it.currency == "KRW" && it.currencyConfirmed })
            val sides = source.zip(fields).map { (row, field) ->
                val raw = JSONObject(row.rawJson)
                require(raw.optString(field.category) == "이체")
                fun relation(name: String): List<String> = raw.getJSONArray(name).let { array -> (0 until array.length()).map { array.getString(it).also { value -> require(value.isNotBlank()) } } }
                ImportRelations(relation(field.debitRelation), relation(field.creditRelation))
            }
            require(sides.count { it.debit.size == 1 && it.credit.isEmpty() } == 1 && sides.count { it.credit.size == 1 && it.debit.isEmpty() } == 1)
            val relations = ImportRelations(sides.flatMap { it.debit }, sides.flatMap { it.credit })
            group.locators.forEach { result[it] = relations }
        }
        return result
    }
}

internal fun matchingFence(mapping: ImportMappingEntity, fence: ImportSourceFenceEntity): Boolean = mapping.identity == fence.identity && mapping.keyJson == fence.keyJson && mapping.ledgerId == fence.ledgerId && mapping.storageGeneration == fence.storageGeneration && mapping.rawSha256 == fence.rawSha256 && mapping.batchId == fence.batchId && mapping.transactionId == fence.transactionId && mapping.payloadSha256 == fence.payloadSha256
