package com.monit.core.data

import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class AnchorStore(private val database: LedgerDatabase, private val clock: Clock) {
    private val dao get() = database.anchorDao()
    private val records get() = database.ledgerDao()

    suspend fun target(context: LedgerContext, command: ConfirmAnchor): AnchorEntity {
        val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
        require(command.date < today)
        val account = records.account(context.ledgerId, command.accountId) ?: throw LedgerConflictException(LedgerConflict.MISSING_REFERENCE)
        val anchor = records.anchor(context.ledgerId, command.anchorId) ?: throw LedgerConflictException(LedgerConflict.MISSING_REFERENCE)
        check(account.id == anchor.accountId && anchor.date == command.date.toString())
        check(anchor.revision > 0)
        return anchor
    }

    suspend fun apply(context: LedgerContext, operation: String, command: ConfirmAnchor, before: AnchorEntity): List<EntityRef> {
        check(before.revision == command.expectedRevision)
        requireLedgerText(command.reason, allowBlank = before.amount == null)
        val revision = Math.addExact(before.revision, 1)
        val at = clock.instant().toString()
        check(dao.confirm(context.ledgerId, before.id, before.revision, command.amount, at) == 1)
        dao.insertHistory(AnchorHistoryEntity(context.ledgerId, before.id, revision, before.accountId, operation, before.date, before.amount, before.confirmedAt, before.unknownReason, before.revision, command.amount, command.reason, at))
        return listOf(EntityRef("anchors", before.id, revision))
    }

    suspend fun verifiedRejection(context: LedgerContext, row: AnchorRejectionEntity): AnchorRejection {
        val pending = records.submission(context.ledgerId, row.operationId)
        val operation = records.operation(context.ledgerId, row.operationId)
        val other = records.rejection(context.ledgerId, row.operationId)
        val payment = database.paymentDao().rejection(context.ledgerId, row.operationId)
        val anchor = records.anchor(context.ledgerId, row.anchorId)
        return validateAnchorRejection(context, row, pending, operation, other == null && payment == null, anchor)
    }
}

internal fun AnchorHistoryEntity.model(): AnchorChange {
    requireLedgerId(operationId)
    check(revision == Math.addExact(beforeRevision, 1))
    requireLedgerText(reason, allowBlank = beforeAmount == null)
    require(amount in -999_999_999_999L..999_999_999_999L)
    return AnchorChange(operationId, requireLedgerId(accountId), BalanceAnchor(anchorId, LocalDate.parse(date), beforeAmount, beforeConfirmedAt?.let(Instant::parse), beforeUnknownReason, beforeRevision), amount, revision, reason, Instant.parse(confirmedAt))
}

/** 성공 판정의 순수 부분. DAO 조회는 호출자의 일관된 읽기 트랜잭션에서 먼저 끝낸다. */
internal fun validAnchorCommit(value: ConfirmAnchor, operation: OperationEntity, history: AnchorHistoryEntity?, anchor: AnchorEntity?): Boolean = try {
    check(history != null && anchor != null)
    history.model()
    history.ledgerId == operation.ledgerId && history.anchorId == value.anchorId && history.operationId == operation.operationId && history.accountId == value.accountId && history.date == value.date.toString() && history.amount == value.amount && history.reason == value.reason && history.beforeRevision == value.expectedRevision && anchor.accountId == value.accountId && anchor.date == value.date.toString() && anchor.revision >= history.revision
} catch (_: IllegalArgumentException) {
    false
} catch (_: IllegalStateException) {
    false
} catch (_: ArithmeticException) {
    false
} catch (_: java.time.DateTimeException) {
    false
}

/** DAO 실패/취소는 호출자에서 전파하고 저장값 손상만 순수 검증한다. */
internal fun validateAnchorRejection(context: LedgerContext, row: AnchorRejectionEntity, pending: SubmissionEntity?, operation: OperationEntity?, noOtherRejection: Boolean, anchor: AnchorEntity?): AnchorRejection {
    requireLedgerId(row.operationId)
    check(row.ledgerId == context.ledgerId && row.storageGeneration == context.storageGeneration)
    check(operation == null && noOtherRejection && pending != null && anchor != null)
    check(requestHash(row.requestJson) == row.requestSha256 && pending.requestJson == row.requestJson && pending.requestSha256 == row.requestSha256)
    val value = decodeAnchor(context, row.requestJson)
    check(value.anchorId == row.anchorId && value.accountId == anchor.accountId && value.date.toString() == anchor.date && value.expectedRevision == row.expectedRevision)
    check(row.actualRevision > 0 && row.actualRevision != row.expectedRevision && row.actualRevision <= anchor.revision)
    return AnchorRejection(context, row.operationId, row.anchorId, row.expectedRevision, row.actualRevision, Instant.parse(row.rejectedAt))
}
