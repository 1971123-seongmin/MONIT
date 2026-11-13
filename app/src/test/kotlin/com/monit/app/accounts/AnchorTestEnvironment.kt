package com.monit.app.accounts

import com.monit.app.payments.PaymentTestEnvironment
import com.monit.app.payments.paymentTestId
import com.monit.core.model.*
import java.io.IOException
import java.time.Instant

/** VM/탐색 프로토콜 대역이다. 실제 원자성·revision·금액 계산은 Room/model 시험으로 검증한다. */
internal class AnchorTestEnvironment {
    val base = PaymentTestEnvironment()
    val context = base.context
    val drafts = base.drafts
    var loseReply = false
    var failBefore = false
    var reject = false
    var beforeWrite: suspend () -> Unit = {}
    val commands = mutableListOf<Pair<String, ConfirmAnchor>>()
    val receipts = mutableMapOf<String, WriteReceipt>()
    val histories = mutableListOf<AnchorChange>()
    val ledger = object : LedgerRepository by base.ledger {
        override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = receipts[operationId]
    }
    val anchors = object : AnchorRepository {
        override suspend fun anchorHistory(context: LedgerContext, accountId: String) = histories.filter { it.accountId == accountId }
        override suspend fun anchorRejection(context: LedgerContext, operationId: String): AnchorRejection? = null
        override suspend fun confirmAnchor(context: LedgerContext, operationId: String, value: ConfirmAnchor, draft: DraftSubmission?): WriteReceipt {
            commands += operationId to value
            beforeWrite()
            if (failBefore) throw IOException("synthetic pre-commit failure")
            if (reject) throw AnchorRejectedException(AnchorRejection(context, operationId, value.anchorId, value.expectedRevision, value.expectedRevision + 1, Instant.EPOCH))
            receipts[operationId]?.let { return it }
            val selected = base.ledgerValue.accounts.single { it.id == value.accountId }
            val changed = selected.copy(anchor = selected.anchor.copy(amount = value.amount, confirmedAt = Instant.EPOCH, unknownReason = null, revision = value.expectedRevision + 1))
            base.ledgerValue = base.ledgerValue.copy(revision = base.ledgerValue.revision + 1, accounts = base.ledgerValue.accounts.map { if (it.id == selected.id) changed else it }, balances = base.ledgerValue.balances + (selected.id to value.amount))
            histories += AnchorChange(operationId, value.accountId, selected.anchor, value.amount, changed.anchor.revision, value.reason, Instant.EPOCH)
            val receipt = WriteReceipt(operationId, base.ledgerValue.revision, listOf(EntityRef("anchors", value.anchorId, changed.anchor.revision)))
            receipts[operationId] = receipt
            draft?.let { base.rows[it.reference.id] = DraftSnapshot.Closed(it.reference.copy(revision = it.reference.revision + 1), operationId) }
            if (loseReply) throw IOException("synthetic lost reply")
            return receipt
        }
    }
    val accountId = paymentTestId(10)
}
