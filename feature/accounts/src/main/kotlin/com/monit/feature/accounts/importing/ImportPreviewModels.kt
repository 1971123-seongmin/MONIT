package com.monit.feature.accounts.importing

import com.monit.core.model.LedgerContext
import com.monit.core.model.TransactionKind
import java.math.BigInteger
import java.time.LocalDate

data class ImportSourceKey(val namespace: String, val entityKind: String, val sourceId: String)

data class ImportCoverage(
    val namespace: String,
    val entityKind: String,
    val paginationComplete: Boolean?,
    val missingProperties: List<String>,
    val missingPropertiesKnown: Boolean,
)

data class ImportSourceRow(
    val sourceNamespace: String,
    val entityKind: String,
    val sourceId: String?,
    val locator: String,
    val rawJson: String,
    val rawSha256: String,
    val relationsComplete: Boolean?,
    val relationNamespace: String?,
) {
    val sourceKey: ImportSourceKey? get() = sourceId?.let { ImportSourceKey(sourceNamespace, entityKind, it) }
}

data class ImportFieldMapping(
    val namespace: String,
    val entityKind: String,
    val title: String,
    val date: String,
    val amount: String,
    val category: String,
    val debitRelation: String,
    val creditRelation: String,
    val walletNamespace: String,
    val currency: String?,
    val currencyConfirmed: Boolean,
)

data class ImportAccountMapping(val walletNamespace: String, val sourceId: String, val accountId: String, val confirmed: Boolean)
data class ImportRowChoice(val locator: String, val decision: ImportDecision, val confirmed: Boolean)

data class ImportPreviewInput(
    val inputSha256: String,
    val snapshotSha256: String,
    val mappingSha256: String,
    val capturedAt: String,
    val scope: String,
    val coverage: List<ImportCoverage>,
    val rows: List<ImportSourceRow>,
    val mappingVersion: String,
    val transformerVersion: String,
    val target: LedgerContext?,
    val fields: List<ImportFieldMapping>,
    val accounts: List<ImportAccountMapping>,
    val choices: List<ImportRowChoice>,
)

data class ImportTransactionCandidate(
    val kind: TransactionKind,
    val date: LocalDate,
    val amount: Long,
    val debitAccountId: String?,
    val creditAccountId: String?,
    val memo: String,
)

data class ImportRowAssessment(
    val source: ImportSourceRow,
    val decision: ImportDecision,
    val reasons: Set<ImportHoldReason>,
    val title: String,
    val rawAmount: String?,
    val rawKind: String?,
    val currency: String?,
    val candidate: ImportTransactionCandidate?,
)

/** 후보의 KRW 정수 금액만 합산한다. 보류/미지원 원본 금액을 0으로 채우지 않는다. */
data class ImportCandidateTotal(val month: String, val kind: TransactionKind, val amount: BigInteger, val count: Int)

data class ImportPreviewReport(
    val input: ImportPreviewInput,
    val context: LedgerContext,
    val ledgerRevision: Long,
    val rows: List<ImportRowAssessment>,
    val totals: List<ImportCandidateTotal>,
    val accountEffects: Map<String, BigInteger>,
    val accountNames: Map<String, String>,
    val fingerprint: String,
) {
    init {
        require(rows.none { it.decision == ImportDecision.EXISTS })
    }
    val existingCount: Int get() = 0
    val actualCreatedCount: Int get() = 0
    val expectedTransactionCount: Int get() = rows.count { it.decision == ImportDecision.CANDIDATE }
    val partial: Boolean get() = input.coverage.isEmpty() || input.coverage.any { it.paginationComplete != true || !it.missingPropertiesKnown || it.missingProperties.isNotEmpty() } ||
        input.rows.any { it.entityKind == "transaction" && it.relationsComplete != true } ||
        input.rows.any { row -> input.coverage.none { it.namespace == row.sourceNamespace && it.entityKind == row.entityKind } } ||
        input.fields.any { field -> input.coverage.none { it.namespace == field.walletNamespace && it.entityKind == "wallet" } }
}
