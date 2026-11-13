package com.monit.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import com.monit.core.model.*

@Entity(
    tableName = "submission_rejections",
    primaryKeys = ["ledgerId", "operationId"],
    foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])],
)
internal data class SubmissionRejectionEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
    val requestJson: String,
    val requestSha256: String,
    val transactionId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val actualDeletedAt: String?,
    val rejectedAt: String,
)

/** 일반 복구와 인증된 백업 검증에서 같은 순수 판정을 사용한다. DAO/I/O/취소 처리는 호출자 책임이다. */
internal fun validateTransactionRejection(context: LedgerContext, row: SubmissionRejectionEntity, pendingRow: SubmissionEntity?, operation: OperationEntity?, targetPresent: Boolean): TransactionRejection {
    check(row.ledgerId == context.ledgerId && row.storageGeneration == context.storageGeneration)
    requireLedgerId(row.operationId)
    check(operation == null)
    check(requestHash(row.requestJson) == row.requestSha256)
    val pending = checkNotNull(pendingRow)
    check(pending.requestJson == row.requestJson && pending.requestSha256 == row.requestSha256)
    val target = when (val command = decodeWrite(context, row.requestJson)) {
        is WriteCommand.Update -> command.value.value.id to command.value.expectedRevision
        is WriteCommand.Delete -> command.value.id to command.value.expectedRevision
        else -> error("Not a transaction change")
    }
    check(target == (row.transactionId to row.expectedRevision))
    check(row.actualRevision > 0 && (row.actualRevision != row.expectedRevision || row.actualDeletedAt != null))
    java.time.Instant.parse(row.rejectedAt)
    row.actualDeletedAt?.let(java.time.Instant::parse)
    check(targetPresent)
    return TransactionRejection(context, row.operationId, row.transactionId, row.expectedRevision, row.actualRevision, row.actualDeletedAt, row.rejectedAt)
}
