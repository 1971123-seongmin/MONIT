package com.monit.core.data.backup

import androidx.room.*
import com.monit.core.data.LedgerEntity

/** 원래 세대의 불변 실행 근거다. 현재 실행 대기열과 테이블도 분리한다. */
@Entity(tableName = "restored_evidence", primaryKeys = ["ledgerId", "operationId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class RestoredEvidenceEntity(val ledgerId: String, val operationId: String, val evidenceJson: String)

@Entity(tableName = "operation_fences")
internal data class OperationFenceEntity(@PrimaryKey val operationId: String)

@Entity(tableName = "backup_originals", primaryKeys = ["ledgerId", "id"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class BackupOriginalEntity(val ledgerId: String, val id: String, val sourceNamespace: String, val fileName: String, val byteLength: Long, val sha256: String, val bytes: ByteArray)

/** 금융 실행 근거와 다른 로컬 작업 결과다. 파일 URI·비밀번호·평문은 넣지 않는다. */
@Entity(tableName = "backup_status")
internal data class BackupStatusEntity(@PrimaryKey val id: String = "local", val lastBackupAt: String?, val lastBackupLedger: String?, val lastBackupGeneration: String?, val restoreResultJson: String?, val restoreAcknowledged: Boolean, val submissionCoverage: String = "RECORDED", val reminderCoverage: String = "RECORDED", val sourceMappingsCoverage: String = "NONE_PRESENT", val lastBackupExportId: String? = null, val lastBackupRevision: Long? = null, val importCoverage: String? = null)

@Dao
internal interface BackupDao {
    @Query("SELECT * FROM backup_status WHERE id='local'")
    suspend fun status(): BackupStatusEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun status(value: BackupStatusEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM operation_fences WHERE operationId=:operation)")
    suspend fun fenced(operation: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun fence(value: OperationFenceEntity)

    @Insert
    suspend fun evidence(value: RestoredEvidenceEntity)

    @Insert
    suspend fun original(value: BackupOriginalEntity)

    @Query("SELECT * FROM restored_evidence WHERE ledgerId=:ledger ORDER BY operationId")
    suspend fun evidence(ledger: String): List<RestoredEvidenceEntity>
}
