package com.monit.feature.accounts.importing

import com.monit.core.model.*
import com.monit.core.model.TransactionKind
import java.math.BigInteger

internal fun ImportPreviewReport.toUiState(): ImportPreviewUiState {
    val candidateCount = rows.count { it.decision == ImportDecision.CANDIDATE }
    val omitted = rows.size - candidateCount
    val totalsUi = totals.map { total ->
        ImportPreviewTotalUiState(
            "${total.month}:${total.kind}",
            "${total.month} · KRW · ${total.kind.label()}",
            "후보 ${total.count}건 · ${total.amount.display()}원",
        )
    } + accountEffects.entries.sortedBy { it.key }.map { (id, value) ->
        ImportPreviewTotalUiState("account:$id", "${accountNames[id] ?: id} · 후보 거래 효과", "${value.display()}원 · 확인 잔액이 아니에요")
    }
    return ImportPreviewUiState(
        sourceSummary = "제공된 범위: ${input.scope}\n추출본 표시 시각: ${input.capturedAt}",
        sourceRowCount = "${rows.size}건",
        candidateCount = "${candidateCount}건",
        existingCount = "${existingCount}건",
        comparison = "단순 유사 기록은 기반영으로 확정하지 않아요. 원본·매핑·로컬 변경은 보류해요.",
        heldCount = "${rows.count { it.decision == ImportDecision.HELD }}건",
        excludedCount = "${rows.count { it.decision == ImportDecision.EXCLUDED }}건",
        transactionCount = "${expectedTransactionCount}건",
        completeness = if (partial) "불완전하거나 미확인인 추출 범위가 있어요. 제공된 일부 행만 검토했어요."
        else "제공된 추출본의 완료 표시에 따른 검토예요. Notion 전체 내용 확보를 검증한 결과는 아니에요.",
        reconciliation = "후보 ${candidateCount}행의 KRW 정수 금액만 집계했어요. 보류/제외 ${omitted}행의 원본 금액은 합계에 넣지 않았어요. 전체 금액 대사는 미확인입니다.",
        evidence = "장부 기준 revision $ledgerRevision · 매핑 ${input.mappingVersion}\n원문과 매핑은 선택한 중간 검토 표현이며 Notion 진위 인증이나 영속 원본 보관이 아니에요.",
        totals = totalsUi,
        rows = rows.map { row ->
            val candidate = row.candidate
            val direction = candidate?.let { "${accountNames[it.debitAccountId] ?: "외부"} → ${accountNames[it.creditAccountId] ?: "외부"}" }
            val details = if (candidate != null) "${candidate.date} · ${candidate.kind.label()} · ${candidate.amount}원\n$direction"
            else "원문 종류 ${row.rawKind ?: "확인 불가"} · 원문 금액 ${row.rawAmount?.take(80) ?: "확인 불가"}\n" +
                row.reasons.sortedBy { it.name }.joinToString("\n") { it.label() }
            val excerpt = if (row.source.rawJson.codePointCount(0, row.source.rawJson.length) > 1000) {
                row.source.rawJson.substring(0, row.source.rawJson.offsetByCodePoints(0, 1000)) + "\n이후 표시 생략 · 전체 원문은 선택한 파일에서 확인해 주세요."
            } else row.source.rawJson
            ImportPreviewRowUiState(
                key = input.inputSha256 + ":" + row.source.locator,
                title = row.title.ifBlank { "제목 없는 원본 행" }.let { title ->
                    if (title.codePointCount(0, title.length) > 120) title.substring(0, title.offsetByCodePoints(0, 120)) + "… (표시 생략)" else title
                },
                decision = when (row.decision) {
                    ImportDecision.CANDIDATE -> "반영예정 후보"
                    ImportDecision.EXISTS -> "기반영"
                    ImportDecision.HELD -> "보류"
                    ImportDecision.EXCLUDED -> "사용자 확인 제외"
                },
                detail = details,
                evidence = "${row.source.sourceNamespace} · ${row.source.entityKind}\n원본 ID ${row.source.sourceId ?: "없음 · 이 추출본 내 위치로만 추적"}\n위치 ${row.source.locator}\n행 SHA256 ${row.source.rawSha256}\n추출본 SHA256 ${input.snapshotSha256}\n매핑 SHA256 ${input.mappingSha256}\n검토 지문 $fingerprint · 실제 반영 승인 토큰이 아니에요.",
                rawExcerpt = excerpt,
                locator = row.source.locator,
                canConfirm = row.decision == ImportDecision.CANDIDATE || row.reasons.any { it == ImportHoldReason.REVIEW_CONFIRMATION_REQUIRED || it == ImportHoldReason.BASIS_CONFIRMATION_REQUIRED },
                canDistinct = ImportHoldReason.POSSIBLE_DUPLICATE in row.reasons,
                canGroup = row.rawKind == "이체" && row.decision != ImportDecision.EXISTS,
            )
        },
    )
}

internal fun ImportReview.toUiState(): ImportPreviewUiState = report.toUiState().copy(
    comparison = if (historyCoverage == ImportHistoryCoverage.RECORDED) "저장된 출처와 현재 거래를 대조했어요. 유사 기록만으로 중복을 확정하지 않아요." else "이전 백업에 이관 정보가 미기록된 범위가 있어요. 이력이 없다고 확정하지 않으며, 유사 기록은 별도 확인이 필요해요.",
    workflow = ImportWorkflowUiState(
        targetConfirmed = options.targetConfirmed,
        canApply = canApply,
        backupVerified = verifiedBackupId != null,
        hasFinancialChanges = report.expectedTransactionCount > 0 || options.anchors.any { it.confirmed && it.action == ImportAnchorAction.CONFIRM },
        basisSummary = options.anchors.filter { it.confirmed }.sortedBy { it.accountId }.map { choice ->
            val name = accounts.singleOrNull { it.id == choice.accountId }?.name ?: choice.accountId
            "$name · 고정 기준일 ${choice.date}\n" + if (choice.action == ImportAnchorAction.KEEP) "기존 기준 ${choice.amount?.let { "${it}원" } ?: "미확인"} 유지" else "기준액 ${choice.amount}원으로 명시 확정 · 사유 ${choice.reason.ifBlank { "최초 확정" }}"
        },
        links = accountLinks.map { link -> ImportAccountLinkUiState(link.walletNamespace, link.sourceId, link.accountId, "${link.walletNamespace} / ${link.sourceId}") },
        accounts = accounts.map { account ->
            val choice = options.anchors.singleOrNull { it.accountId == account.id && it.confirmed }
            ImportAccountUiState(
                account.id,
                account.name,
                account.anchor.date.toString(),
                account.anchor.amount?.let { "${it}원" } ?: "미확인 · 0원으로 추정하지 않음",
                when (choice?.action) {
                    ImportAnchorAction.KEEP -> "기존 기준 유지 확인됨"
                    ImportAnchorAction.CONFIRM -> "${choice.amount}원으로 명시 확정 예정"
                    null -> "기준 유지 또는 명시 확정 선택 필요"
                },
                account.status == AccountStatus.ACTIVE,
            )
        },
        currencyTotals = sourceTotals.map { "정수로 읽은 원문 ${it.currency} ${it.amount} · ${it.count}행 (통화별 별도 합계)" } + listOfNotNull("미해석·소수 금액은 원문으로 별도 확인해 주세요. 외화를 원화에 더하지 않아요.", limitMessage),
    ),
)

internal fun ImportResult.toUiState(): ImportPreviewUiState = ImportPreviewUiState(
    canAcknowledge = true,
    sourceRowCount = "${sourceRows}건",
    candidateCount = "${appliedRows}건 반영",
    existingCount = "${existingRows}건",
    heldCount = "${heldRows}건",
    excludedCount = "${excludedRows}건",
    transactionCount = "${createdTransactions}건",
    resultSummary = "${if (partial) "부분 지원 반영 결과" else "검토 집합 반영 결과"}\n원본 ${sourceRows} = 반영 ${appliedRows} + 기반영 ${existingRows} + 보류 ${heldRows} + 제외 ${excludedRows}\n실제 생성 거래 ${createdTransactions}건 · 기준액 확정 ${confirmedAnchors}건\n보류·제외 원문은 보관했어요. 전체 원본을 모두 이관했다는 뜻은 아니에요.\n확정 시각 $committedAt · 장부 revision $revision",
)

private fun BigInteger.display(): String = toString()
private fun TransactionKind.label(): String = when (this) {
    TransactionKind.INCOME -> "수입"
    TransactionKind.EXPENSE -> "지출"
    TransactionKind.TRANSFER -> "이체"
}
private fun ImportHoldReason.label(): String = when (this) {
    ImportHoldReason.EXPLICIT_HOLD -> "사용자의 보류 선택 또는 미확인 제외 선택"
    ImportHoldReason.RAW_FORMAT -> "지원하지 않거나 해석할 수 없는 원문 속성 형식"
    ImportHoldReason.RAW_DIGEST -> "행 원문과 제공된 digest 불일치"
    ImportHoldReason.REPEATED_SOURCE_KEY -> "같은 출처·종류·원본 ID가 여러 번 나옴"
    ImportHoldReason.CONFLICTING_SOURCE_KEY -> "같은 원본 ID의 내용이 서로 다름"
    ImportHoldReason.SOURCE_INCOMPLETE -> "해당 출처의 pagination 또는 속성 확보 미확인"
    ImportHoldReason.RELATION_UNAVAILABLE -> "관계 ID 미확보 또는 필요한 관계 속성 없음"
    ImportHoldReason.AMBIGUOUS_RELATION -> "중복/복수 관계 또는 모호한 계좌 대응"
    ImportHoldReason.UNSUPPORTED_KIND -> "지원하지 않는 종류 · 환전/카드 정산 등을 자동 변환하지 않음"
    ImportHoldReason.INVALID_DATE -> "거래일 형식 또는 지원 기간 확인 필요"
    ImportHoldReason.INVALID_AMOUNT -> "정수 원화 범위 확인 필요 · 금액을 임의 보정하지 않음"
    ImportHoldReason.CURRENCY_UNCONFIRMED -> "KRW 통화에 대한 명시 확인 없음 또는 미지원 통화"
    ImportHoldReason.ACCOUNT_MAPPING_REQUIRED -> "명시 확인된 계좌 대응 또는 이체 양쪽 관계 필요"
    ImportHoldReason.ACCOUNT_UNAVAILABLE -> "현재 장부의 활성 계좌가 아님"
    ImportHoldReason.SAME_TRANSFER_ACCOUNT -> "이체 양쪽 계좌가 같음"
    ImportHoldReason.POSSIBLE_DUPLICATE -> "날짜·금액·계좌가 유사한 기록 · 기반영 확정이 아니므로 자동 합치지 않음"
    ImportHoldReason.REVIEW_CONFIRMATION_REQUIRED -> "이 행의 반영에 대한 명시 확인 필요"
    ImportHoldReason.BASIS_CONFIRMATION_REQUIRED -> "계좌별 기준일·기준액 유지 또는 확정 선택 필요"
    ImportHoldReason.SOURCE_CHANGED -> "같은 출처의 원문이 변경됨 · 자동 갱신하지 않음"
    ImportHoldReason.LOCAL_CHANGED -> "대상 거래가 수정 또는 삭제됨 · 덮어쓰거나 부활시키지 않음"
    ImportHoldReason.MAPPING_CHANGED -> "이전에 반영한 매핑이 변경됨 · 다시 검토 필요"
    ImportHoldReason.HISTORICAL_SOURCE -> "이전 반영 출처 이력이 있음 · 자동 신규 반영하지 않음"
    ImportHoldReason.INVALID_GROUP -> "명시 이체 묶음의 양쪽 판정·금액·계좌·날짜 확인 필요"
}
