package com.monit.core.data

import com.monit.core.model.*

/** 신규/편집 중인 자식만 현재 부모 요청을 검사한다. 잠긴 제출은 부모 폐기로 바꾸지 않는다. */
internal suspend fun FormDraftDao.requireCaller(context: LedgerContext, scope: DraftScope) {
    if (scope.origin != DraftOrigin.TRANSACTION_ENTRY) return
    val parent = draft(context.ledgerId, checkNotNull(scope.parentDraftId))
        ?: throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
    if (parent.status != "EDITING") throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
    val form = parent.editable(context)
    val value = form.content as? DraftContent.Transaction ?: throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
    if (value.selectionRequestId != scope.selectionRequestId || value.selectionSlot != scope.slot) {
        throw DraftConflictException(DraftProblem.WRONG_CONTEXT)
    }
}
