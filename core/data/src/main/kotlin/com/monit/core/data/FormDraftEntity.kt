package com.monit.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 종료 후 payload는 비우고 ID/revision fence만 보존한다. */
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
