package com.monit.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 소비·폐기 후 payload를 비운다. 확정 충돌(REJECTED)은 이전 입력 원문까지 보존한다. */
@Entity(
    tableName = "form_drafts",
    primaryKeys = ["ledgerId", "id"],
    foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])],
    indices = [Index(value = ["ledgerId", "operationId"], unique = true)],
)
internal data class FormDraftEntity(
    val ledgerId: String,
    val storageGeneration: String,
    val id: String,
    val revision: Long,
    val scopeJson: String,
    val payloadJson: String?,
    val payloadSha256: String?,
    val status: String,
    val operationId: String?,
)
