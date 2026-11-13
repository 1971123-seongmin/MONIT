package com.monit.core.data.backup

import com.monit.core.data.*
import com.monit.core.data.importing.*
import com.monit.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** v2의 미기록과 v3의 실제 이력을 구분한다. 원래 canonical child는 변환하지 않는다. */
internal object BackupImportV3 {
    fun upgradeV2(root: JSONObject): JSONObject {
        val imports = JSONObject().put("coverage", if (root.getJSONObject("manifest").text("sourceMappings") == "NOT_RECORDED_BY_V1") "NOT_RECORDED_BY_V1" else "NOT_RECORDED_BY_V2")
        BackupLayout.importTables.forEach { imports.put(it, JSONArray()) }
        root.put("formatVersion", 3).put("contractVersion", 3).put("imports", imports)
        root.put("manifest", BackupSnapshotAdapter.manifest(root, root.getJSONObject("manifest").text("submissionCoverage"), imports.text("coverage")))
        return root
    }

    fun unionFences(current: List<JSONObject>, incoming: List<JSONObject>): List<JSONObject> {
        val all = (current + incoming).groupBy { it.text("identity") }
        for (values in all.values) require(values.map(::canonicalImport).distinct().size == 1) { "Conflicting source provenance" }
        val result = all.values.map { it.first() }.sortedBy { it.text("identity") }
        val noId = result.filter { JSONObject(it.text("keyJson")).text("kind") == "FILE_ROW" }.groupBy { row ->
            val key = JSONObject(row.text("keyJson"))
            canonicalImport(importObject("namespace" to key.text("namespace"), "entityKind" to key.text("entityKind"), "snapshot" to row.text("snapshotSha256"), "locator" to row.text("locator"), "position" to row.getInt("position")))
        }
        require(noId.values.all { it.size == 1 })
        return result
    }

    suspend fun validate(data: ValidatedBackup, finance: BackupFinanceValidator, today: LocalDate) {
        val imports = data.root.getJSONObject("imports")
        require(imports.text("coverage") == data.root.getJSONObject("manifest").text("sourceMappings"))
        val tables = BackupLayout.importTables.associateWith { imports.list(it) }
        val batches = tables.getValue("import_batches")
        val rows = tables.getValue("import_rows")
        val children = tables.getValue("import_child_commands")
        val mappings = tables.getValue("import_source_mappings")
        val fences = tables.getValue("import_source_fences")
        fun unique(values: List<JSONObject>, vararg names: String) = require(values.map { row -> names.map { row.get(it) } }.distinct().size == values.size)
        unique(batches, "batchId")
        unique(rows, "batchId", "locator")
        unique(mappings, "identity")
        unique(children, "operationId")
        unique(fences, "identity")
        for (table in BackupLayout.importTables.filter { it != "import_source_fences" }) for (row in tables.getValue(table)) require(row.text("ledgerId") == data.context.ledgerId)
        val batchIndex = batches.associateBy { it.text("batchId") }
        require(rows.all { it.text("batchId") in batchIndex } && children.all { it.text("batchId") in batchIndex })
        val operations = data.root.list("submissionsEvidence").associateBy { it.text("operationId") }
        val operationFences = data.root.getJSONArray("operationFences").values().toSet()
        val originals = data.root.list("originals").associateBy { it.text("id") }
        val sourceFences = fences.associateBy { it.text("identity") }
        val plannedIds = mutableSetOf<String>()
        for (batch in batches) {
            currentCoroutineContext().ensureActive()
            val id = requireLedgerId(batch.text("batchId"))
            val context = LedgerContext(batch.text("ledgerId"), batch.text("storageGeneration"))
            require(batch.exactLong("expectedRevision") > 0 && id in operationFences && id !in operations)
            batch.instant("createdAt")
            val request = BackupJson.parseText(batch.text("requestJson"))
            request.fields("version", "batchId", "ledgerId", "storageGeneration", "expectedRevision", "originalId", "sourceSha256", "snapshotSha256", "mappingSha256", "fingerprint", "backupId", "historyCoverage", "options", "rows", "children")
            ImportHistoryCoverage.valueOf(request.text("historyCoverage"))
            require(canonicalImport(request) == batch.text("requestJson") && requestHash(batch.text("requestJson")) == batch.text("requestSha256"))
            require(request.text("version") == "monit-import-1" && request.text("batchId") == id && request.text("ledgerId") == context.ledgerId && request.text("storageGeneration") == context.storageGeneration && request.text("expectedRevision") == batch.text("expectedRevision") && request.text("fingerprint") == batch.text("fingerprint"))
            requireLedgerId(request.text("backupId"))
            val original = checkNotNull(originals[batch.text("originalId")])
            require(request.text("originalId") == original.text("id") && request.text("sourceSha256") == original.text("sha256"))
            val bytes = Base64.getDecoder().decode(original.text("bytesBase64"))
            val input = try {
                ImportPreviewParser.parse(bytes)
            } finally {
                bytes.fill(0)
            }
            require(request.text("snapshotSha256") == input.snapshotSha256 && request.text("mappingSha256") == input.mappingSha256)
            val options = importOptions(request.getJSONObject("options"))
            require(options.targetConfirmed)
            val effectiveInput = input.copy(target = context, accounts = options.accounts.ifEmpty { input.accounts })
            val historicalAccounts = finance.accounts.values.map { account ->
                val anchor = finance.anchors.values.single { it.accountId == account.id }
                LedgerAccount(account.id, account.name, AccountNature.valueOf(account.nature), AccountStatus.ACTIVE, account.revision, BalanceAnchor(anchor.id, LocalDate.parse(anchor.date), anchor.amount, anchor.confirmedAt?.let(Instant::parse), anchor.unknownReason, anchor.revision))
            }
            val historical = LedgerSnapshot(data.revision, historicalAccounts, emptyList(), emptyMap(), 0, 0)
            val relations = ImportReviewBuilder(today).grouped(effectiveInput, options)
            val derivedReport = ImportPreviewEvaluator.evaluate(effectiveInput, context, historical, today, relations, true)
            val derivation = derivedReport.rows.associateBy { it.source.locator }
            val requestRows = request.list("rows")
            val storedRows = rows.filter { it.text("batchId") == id }.sortedBy { it.getInt("position") }
            require(requestRows.size == input.rows.size && storedRows.size == input.rows.size)
            for ((index, source) in input.rows.withIndex()) {
                val row = storedRows[index]
                val approved = requestRows[index]
                val key = importIdentity(input, source, index)
                approved.fields("position", "locator", "identity", "keyJson", "rawSha256", "mappingHash", "decision", "reasons")
                require(row.getInt("position") == index && approved.getInt("position") == index && row.text("locator") == source.locator && approved.text("locator") == source.locator && row.text("identity") == key.hash && approved.text("identity") == key.hash && row.text("keyJson") == key.key && approved.text("keyJson") == key.key && row.text("rawSha256") == source.rawSha256 && approved.text("rawSha256") == source.rawSha256)
                val decision = approved.text("decision")
                require(decision in setOf("CANDIDATE", "EXISTS", "HELD", "EXCLUDED"))
                approved.getJSONArray("reasons").values().forEach { ImportHoldReason.valueOf(it as String) }
                if (decision == "EXCLUDED") require(source.locator in options.excludedRows || input.choices.any { it.locator == source.locator && it.confirmed && it.decision == ImportDecision.EXCLUDED })
                require(row.text("decision") == if (batch.text("status") == "COMMITTED" && decision == "CANDIDATE") "APPLIED" else decision)
                require(row.text("reasonsJson") == canonicalImport(approved.getJSONArray("reasons")))
                require(approved.text("mappingHash") == mappingHash(effectiveInput, source, options))
            }
            val planned = request.list("children")
            val linked = children.filter { it.text("batchId") == id }.associateBy { it.text("operationId") }
            val result = batch.optional("resultJson")?.let(::importResult)
            if (result == null) require(batch.text("status") == "REQUESTED" && linked.isEmpty() && batch.isNull("committedAt"))
            else {
                require(batch.text("status") == "COMMITTED" && result.context == context && result.batchId == id && result.revision == Math.addExact(batch.exactLong("expectedRevision"), 1) && result.committedAt == batch.text("committedAt") && result.originalId == original.text("id"))
                require(result.sourceRows == storedRows.size && result.appliedRows == storedRows.count { it.text("decision") == "APPLIED" } && result.existingRows == storedRows.count { it.text("decision") == "EXISTS" } && result.heldRows == storedRows.count { it.text("decision") == "HELD" } && result.excludedRows == storedRows.count { it.text("decision") == "EXCLUDED" })
                require(linked.size == planned.size && result.createdTransactions == linked.values.count { it.text("kind") == "CREATE_TRANSACTION" } && result.confirmedAnchors == linked.values.count { it.text("kind") == "CONFIRM_ANCHOR" })
                if (derivedReport.partial || request.text("historyCoverage") != "RECORDED" || result.heldRows > 0 || result.excludedRows > 0) require(result.partial)
            }
            val sourceLinks = mutableSetOf<String>()
            for (child in planned) {
                child.fields("operationId", "requestJson", "locators")
                val childId = requireLedgerId(child.text("operationId"))
                require(plannedIds.add(childId) && childId !in batchIndex && childId in operationFences)
                val command = decodeWrite(context, child.text("requestJson"))
                require(command is WriteCommand.Transaction || command is WriteCommand.Anchor)
                val locators = child.getJSONArray("locators").values().map { it as String }
                require(locators.distinct().size == locators.size && locators.all { sourceLinks.add(it) })
                if (command is WriteCommand.Transaction) require(locators.size in 1..2) else require(locators.isEmpty())
                val sourceRows = storedRows.filter { it.text("locator") in locators }
                require(sourceRows.size == locators.size && sourceRows.all { it.optional("groupId") == childId && it.optional("transactionId") == (command as? WriteCommand.Transaction)?.value?.id })
                require(sourceRows.all { it.text("decision") == if (result == null) "CANDIDATE" else "APPLIED" })
                when (command) {
                    is WriteCommand.Transaction -> {
                        require(locators.all { it in options.confirmedRows && it !in options.excludedRows })
                        if (locators.size == 2) require(options.groups.any { it.confirmed && it.locators.toSet() == locators.toSet() })
                        else require(options.groups.none { locators.single() in it.locators })
                        val derived = locators.map { locator ->
                            val row = checkNotNull(derivation[locator])
                            require(row.reasons.all { it == ImportHoldReason.POSSIBLE_DUPLICATE } && (ImportHoldReason.POSSIBLE_DUPLICATE !in row.reasons || locator in options.distinctRows))
                            checkNotNull(row.candidate)
                        }
                        require(derived.map { it.copy(memo = "") }.distinct().size == 1)
                        val value = command.value
                        val expected = derived.first()
                        require(value.kind == expected.kind && value.date == expected.date && value.amount == expected.amount && value.debitAccountId == expected.debitAccountId && value.creditAccountId == expected.creditAccountId && value.memo == locators.sorted().joinToString("\n") { checkNotNull(derivation[it]).title })
                        for (accountId in listOfNotNull(value.debitAccountId, value.creditAccountId)) {
                            val anchor = finance.anchors.values.single { it.accountId == accountId }
                            val choice = options.anchors.single { it.accountId == accountId }
                            require(choice.confirmed && choice.anchorId == anchor.id && choice.date == anchor.date && choice.expectedRevision <= anchor.revision)
                            val amount = if (choice.expectedRevision == anchor.revision) anchor.amount else finance.anchorHistories[anchor.id].orEmpty().single { it.beforeRevision == choice.expectedRevision }.beforeAmount
                            if (choice.action == ImportAnchorAction.KEEP) require(choice.amount == amount)
                            else require(
                                planned.any { plannedChild ->
                                    val plannedCommand = decodeWrite(context, plannedChild.text("requestJson"))
                                    plannedCommand is WriteCommand.Anchor && plannedCommand.value.accountId == accountId
                                },
                            )
                        }
                    }

                    is WriteCommand.Anchor -> require(options.anchors.any { it.confirmed && it.action == ImportAnchorAction.CONFIRM && it.accountId == command.value.accountId && it.anchorId == command.value.anchorId && it.expectedRevision == command.value.expectedRevision && it.date == command.value.date.toString() && it.amount == command.value.amount && it.reason == command.value.reason })

                    else -> error("Unsupported import child")
                }
                if (result == null) require(childId !in operations)
                else {
                    val link = checkNotNull(linked[childId])
                    val evidence = checkNotNull(operations[childId])
                    val success = evidence.getJSONObject("operations")
                    require(success.text("requestJson") == child.text("requestJson") && success.text("requestSha256") == link.text("requestSha256") && success.exactLong("ledgerRevision") == result.revision && success.text("committedAt") == result.committedAt)
                    val target = when (command) {
                        is WriteCommand.Transaction -> command.value.id
                        is WriteCommand.Anchor -> command.value.anchorId
                        else -> error("Unsupported child")
                    }
                    require(link.text("entityId") == target && link.text("kind") == success.text("kind"))
                }
            }
            require(storedRows.filter { it.text("decision") in setOf("CANDIDATE", "APPLIED") }.all { it.text("locator") in sourceLinks })
        }
        for (mapping in mappings) {
            currentCoroutineContext().ensureActive()
            val batch = checkNotNull(batchIndex[mapping.text("batchId")])
            require(batch.text("status") == "COMMITTED" && mapping.text("storageGeneration") == batch.text("storageGeneration") && mapping.text("originalId") == batch.text("originalId"))
            val row = rows.single { it.text("batchId") == mapping.text("batchId") && it.text("locator") == mapping.text("locator") }
            require(row.text("decision") == "APPLIED" && row.text("identity") == mapping.text("identity") && row.text("keyJson") == mapping.text("keyJson") && row.text("rawSha256") == mapping.text("rawSha256") && row.text("transactionId") == mapping.text("transactionId") && row.text("groupId") == mapping.text("groupId"))
            require(mapping.exactLong("appliedRevision") == 1L && mapping.exactLong("appliedFinancialRevision") == 1L && requestHash(mapping.text("payloadJson")) == mapping.text("payloadSha256"))
            val target = finance.transactionSnapshot(mapping.text("transactionId"), mapping.exactLong("appliedRevision"))
            require(transactionPayload(target) == mapping.text("payloadJson") && target.financialRevision == mapping.exactLong("appliedFinancialRevision"))
            val request = JSONObject(batch.text("requestJson"))
            require(mapping.text("fileSha256") == request.text("sourceSha256") && mapping.text("snapshotSha256") == request.text("snapshotSha256") && mapping.text("fileMappingSha256") == request.text("mappingSha256"))
            require(mapping.text("mappingSha256") == request.list("rows").single { it.text("locator") == mapping.text("locator") }.text("mappingHash"))
            val fence = checkNotNull(sourceFences[mapping.text("identity")])
            for (name in listOf("identity", "keyJson", "ledgerId", "storageGeneration", "rawSha256", "fileSha256", "snapshotSha256", "fileMappingSha256", "locator", "position", "batchId", "transactionId", "payloadSha256")) require(mapping.get(name) == fence.get(name))
        }
        require(rows.filter { it.text("decision") == "APPLIED" }.all { row -> mappings.any { it.text("identity") == row.text("identity") && it.text("batchId") == row.text("batchId") } })
        val mappingIndex = mappings.associateBy { it.text("identity") }
        for (row in rows.filter { it.text("decision") == "EXISTS" }) {
            val mapping = checkNotNull(mappingIndex[row.text("identity")])
            val request = JSONObject(checkNotNull(batchIndex[row.text("batchId")]).text("requestJson"))
            require(mapping.text("rawSha256") == row.text("rawSha256") && mapping.text("keyJson") == row.text("keyJson") && mapping.text("mappingSha256") == request.list("rows").single { it.text("locator") == row.text("locator") }.text("mappingHash"))
        }
        for (fence in fences) {
            LedgerContext(fence.text("ledgerId"), fence.text("storageGeneration"))
            requireLedgerId(fence.text("batchId"))
            requireLedgerId(fence.text("transactionId"))
            val key = BackupJson.parseText(fence.text("keyJson"))
            require(canonicalImport(key) == fence.text("keyJson") && importHash(key) == fence.text("identity"))
            requireLedgerText(key.text("namespace"))
            requireLedgerText(key.text("entityKind"))
            when (key.text("kind")) {
                "SOURCE_ID" -> {
                    key.fields("kind", "namespace", "entityKind", "sourceId")
                    requireLedgerText(key.text("sourceId"))
                }

                "FILE_ROW" -> {
                    key.fields("kind", "namespace", "entityKind", "fileSha256", "locator", "position")
                    require(key.text("fileSha256") == fence.text("fileSha256") && key.text("locator") == fence.text("locator") && key.getInt("position") == fence.getInt("position"))
                }

                else -> error("Unknown source key")
            }
        }
        unionFences(fences, emptyList())
    }
}
