package com.monit.core.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey

/** 변경 직전 값만 추가한다. 원행과 성공 원장은 삭제하거나 덮어쓰지 않는다. */
@Entity(
    tableName = "transaction_history",
    primaryKeys = ["ledgerId", "id", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["ledgerId", "id"],
            childColumns = ["ledgerId", "id"],
        ),
    ],
)
internal data class TransactionHistoryEntity(
    @Embedded val before: TransactionEntity,
    val operationId: String,
    val changedAt: String,
    val changeKind: String,
)
