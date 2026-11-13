package com.monit.core.data.importing

import com.monit.core.model.*

/** 공개 List/Map의 mutable 구현이 private 검토 계획과 같은 인스턴스를 공유하지 않게 한다. */
internal fun ImportReviewOptions.detached(): ImportReviewOptions = copy(accounts = accounts.map { it.copy() }, anchors = anchors.map { it.copy() }, groups = groups.map { it.copy(locators = it.locators.toList()) }, confirmedRows = confirmedRows.toSet(), distinctRows = distinctRows.toSet(), excludedRows = excludedRows.toSet())
internal fun ImportReview.detached(): ImportReview = copy(
    options = options.detached(),
    accounts = accounts.map { it.copy(anchor = it.anchor.copy()) },
    sourceTotals = sourceTotals.map { it.copy() },
    accountLinks = accountLinks.map { it.copy() },
    report = report.copy(
        input = report.input.copy(coverage = report.input.coverage.map { it.copy(missingProperties = it.missingProperties.toList()) }, rows = report.input.rows.map { it.copy() }, fields = report.input.fields.map { it.copy() }, accounts = report.input.accounts.map { it.copy() }, choices = report.input.choices.map { it.copy() }),
        rows = report.rows.map { it.copy(source = it.source.copy(), reasons = it.reasons.toSet(), candidate = it.candidate?.copy()) },
        totals = report.totals.map { it.copy() },
        accountEffects = report.accountEffects.toMap(),
        accountNames = report.accountNames.toMap(),
    ),
)
