package com.monit.core.common.draft

import android.os.Bundle
import com.monit.core.model.*

/** SavedState에는 원문 대신 작은 초안 참조/호출 문맥을 저장한다. 영속 원본은 Room이다. */
object DraftSavedState {
    fun scope(value: DraftScope): Bundle = Bundle().apply {
        putString("kind", value.kind.name)
        putString("origin", value.origin.name)
        putString("parent", value.parentDraftId)
        putString("request", value.selectionRequestId)
        putString("slot", value.slot?.name)
        putString("transaction", value.transactionId)
        value.transactionRevision?.let { putLong("transactionRevision", it) }
    }

    fun scope(value: Bundle): DraftScope = DraftScope(
        DraftKind.valueOf(requireNotNull(value.getString("kind"))),
        DraftOrigin.valueOf(requireNotNull(value.getString("origin"))),
        value.getString("parent"),
        value.getString("request"),
        value.getString("slot")?.let(DraftSlot::valueOf),
        value.getString("transaction"),
        if (value.containsKey("transactionRevision")) value.getLong("transactionRevision") else null,
    )

    fun submission(value: DraftSubmission): Bundle = scope(value.scope).apply {
        putString("id", value.reference.id)
        putLong("revision", value.reference.revision)
    }

    fun submission(value: Bundle): DraftSubmission = DraftSubmission(
        DraftReference(requireNotNull(value.getString("id")), value.getLong("revision")),
        scope(value),
    )
}
