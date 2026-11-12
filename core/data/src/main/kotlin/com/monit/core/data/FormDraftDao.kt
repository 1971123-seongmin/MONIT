package com.monit.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface FormDraftDao {
    @Query("SELECT * FROM form_drafts WHERE ledgerId = :ledger AND id = :id")
    suspend fun draft(ledger: String, id: String): FormDraftEntity?

    @Query("SELECT * FROM form_drafts WHERE ledgerId = :ledger AND status IN ('EDITING', 'SUBMITTING') ORDER BY id")
    suspend fun drafts(ledger: String): List<FormDraftEntity>

    @Query("SELECT * FROM form_drafts WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun linked(ledger: String, operation: String): FormDraftEntity?

    @Insert
    suspend fun insert(value: FormDraftEntity)

    @Query("UPDATE form_drafts SET revision = revision + 1, payloadJson = :payload, payloadSha256 = :hash WHERE ledgerId = :ledger AND id = :id AND storageGeneration = :generation AND revision = :revision AND status = 'EDITING' AND operationId IS NULL")
    suspend fun update(ledger: String, generation: String, id: String, revision: Long, payload: String, hash: String): Int

    @Query("UPDATE form_drafts SET status = 'SUBMITTING', operationId = :operation WHERE ledgerId = :ledger AND storageGeneration = :generation AND id = :id AND revision = :revision AND status = 'EDITING' AND operationId IS NULL")
    suspend fun lock(ledger: String, generation: String, id: String, revision: Long, operation: String): Int

    @Query("UPDATE form_drafts SET revision = revision + 1, status = :status, payloadJson = NULL, payloadSha256 = NULL WHERE ledgerId = :ledger AND storageGeneration = :generation AND id = :id AND revision = :revision AND status = :expectedStatus")
    suspend fun close(ledger: String, generation: String, id: String, revision: Long, expectedStatus: String, status: String): Int
}
