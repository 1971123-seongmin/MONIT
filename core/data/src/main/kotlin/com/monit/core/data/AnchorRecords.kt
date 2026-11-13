package com.monit.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.Query

@Entity(tableName = "anchor_history", primaryKeys = ["ledgerId", "anchorId", "revision"], foreignKeys = [ForeignKey(entity = AnchorEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "anchorId"])])
internal data class AnchorHistoryEntity(
    val ledgerId: String,
    val anchorId: String,
    val revision: Long,
    val accountId: String,
    val operationId: String,
    val date: String,
    val beforeAmount: Long?,
    val beforeConfirmedAt: String?,
    val beforeUnknownReason: String?,
    val beforeRevision: Long,
    val amount: Long,
    val reason: String,
    val confirmedAt: String,
)

@Entity(tableName = "anchor_rejections", primaryKeys = ["ledgerId", "operationId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class AnchorRejectionEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
    val requestJson: String,
    val requestSha256: String,
    val anchorId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val rejectedAt: String,
)

@Dao
internal interface AnchorDao {
    @Query("UPDATE anchors SET amount=:amount, confirmedAt=:at, unknownReason=NULL, revision=revision+1 WHERE ledgerId=:ledger AND id=:id AND revision=:expected")
    suspend fun confirm(ledger: String, id: String, expected: Long, amount: Long, at: String): Int

    @Insert
    suspend fun insertHistory(value: AnchorHistoryEntity)

    @Query("SELECT * FROM anchor_history WHERE ledgerId=:ledger AND accountId=:account ORDER BY revision")
    suspend fun history(ledger: String, account: String): List<AnchorHistoryEntity>

    @Query("SELECT * FROM anchor_history WHERE ledgerId=:ledger AND anchorId=:anchor AND revision=:revision")
    suspend fun historyAt(ledger: String, anchor: String, revision: Long): AnchorHistoryEntity?

    @Insert
    suspend fun insertRejection(value: AnchorRejectionEntity)

    @Query("SELECT * FROM anchor_rejections WHERE ledgerId=:ledger AND operationId=:operation")
    suspend fun rejection(ledger: String, operation: String): AnchorRejectionEntity?
}
