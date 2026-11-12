package com.monit.feature.accounts.importing

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
            )
        },
    )
}

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
}
