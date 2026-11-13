package com.monit.core.data.importing

import androidx.room.*
import com.monit.core.data.LedgerEntity

@Entity(tableName = "import_batches", primaryKeys = ["ledgerId", "batchId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class ImportBatchEntity(val ledgerId: String, val batchId: String, val storageGeneration: String, val requestJson: String, val requestSha256: String, val originalId: String, val expectedRevision: Long, val fingerprint: String, val status: String, val resultJson: String?, val createdAt: String, val committedAt: String?, val acknowledged: Boolean, val evidenceOnly: Boolean)

@Entity(tableName = "import_rows", primaryKeys = ["ledgerId", "batchId", "locator"], foreignKeys = [ForeignKey(entity = ImportBatchEntity::class, parentColumns = ["ledgerId", "batchId"], childColumns = ["ledgerId", "batchId"])])
internal data class ImportRowEntity(val ledgerId: String, val batchId: String, val locator: String, val position: Int, val identity: String, val keyJson: String, val rawSha256: String, val decision: String, val reasonsJson: String, val groupId: String?, val transactionId: String?)

@Entity(tableName = "import_source_mappings", primaryKeys = ["ledgerId", "identity"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class ImportMappingEntity(val ledgerId: String, val identity: String, val keyJson: String, val rawSha256: String, val fileSha256: String, val snapshotSha256: String, val fileMappingSha256: String, val mappingSha256: String, val locator: String, val position: Int, val originalId: String, val batchId: String, val storageGeneration: String, val groupId: String, val transactionId: String, val appliedRevision: Long, val appliedFinancialRevision: Long, val payloadJson: String, val payloadSha256: String)

@Entity(tableName = "import_child_commands", primaryKeys = ["ledgerId", "operationId"], foreignKeys = [ForeignKey(entity = ImportBatchEntity::class, parentColumns = ["ledgerId", "batchId"], childColumns = ["ledgerId", "batchId"])], indices = [Index(value = ["ledgerId", "batchId"])])
internal data class ImportChildEntity(val ledgerId: String, val batchId: String, val operationId: String, val requestSha256: String, val kind: String, val entityId: String)

/** 활성 mapping과 분리한 append-only 출처 이력. 복원에서도 현재/입력 합집합을 보존한다. */
@Entity(tableName = "import_source_fences")
internal data class ImportSourceFenceEntity(@PrimaryKey val identity: String, val keyJson: String, val ledgerId: String, val storageGeneration: String, val rawSha256: String, val fileSha256: String, val snapshotSha256: String, val fileMappingSha256: String, val locator: String, val position: Int, val batchId: String, val transactionId: String, val payloadSha256: String)

@Dao
internal interface ImportDao {
    @Query("SELECT * FROM import_rows WHERE ledgerId=:ledger AND batchId=:id ORDER BY position")
    suspend fun rows(ledger: String, id: String): List<ImportRowEntity>

    @Query("SELECT * FROM import_source_mappings WHERE ledgerId=:ledger ORDER BY identity")
    suspend fun mappings(ledger: String): List<ImportMappingEntity>

    @Query("SELECT * FROM import_source_fences ORDER BY identity")
    suspend fun fences(): List<ImportSourceFenceEntity>

    @Query("SELECT * FROM import_child_commands WHERE ledgerId=:ledger AND batchId=:id ORDER BY operationId")
    suspend fun children(ledger: String, id: String): List<ImportChildEntity>

    @Insert suspend fun insertBatch(value: ImportBatchEntity)

    @Update suspend fun updateBatch(value: ImportBatchEntity): Int

    @Insert suspend fun insertRow(value: ImportRowEntity)

    @Update suspend fun updateRow(value: ImportRowEntity): Int

    @Insert suspend fun insertMapping(value: ImportMappingEntity)

    @Insert suspend fun insertChild(value: ImportChildEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFence(value: ImportSourceFenceEntity)
}
