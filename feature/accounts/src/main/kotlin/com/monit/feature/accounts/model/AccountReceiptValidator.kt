package com.monit.feature.accounts.model

import com.monit.core.model.WriteReceipt

/** 제출 ID뿐 아니라 계좌/기준점 참조와 revision도 일치해야 저장 성공으로 수용한다. */
internal fun WriteReceipt.matches(submission: SubmittedAccountState): Boolean {
    val expected = setOf("accounts" to submission.value.id, "anchors" to submission.value.anchorId)
    return operationId == submission.operationId && ledgerRevision > 0 && entities.size == 2 &&
        entities.all { it.revision > 0 } && entities.map { it.collection to it.id }.toSet() == expected
}
