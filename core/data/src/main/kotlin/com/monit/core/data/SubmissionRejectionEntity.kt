package com.monit.core.data

import androidx.room.Entity
import androidx.room.ForeignKey

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
