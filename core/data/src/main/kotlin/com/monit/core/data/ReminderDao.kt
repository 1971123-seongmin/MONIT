package com.monit.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminder_settings", primaryKeys = ["ledgerId"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])])
internal data class ReminderSettingsEntity(
    val ledgerId: String,
    val storageGeneration: String,
    val enabled: Boolean,
    val revision: Long,
    val dirty: Boolean,
    val failed: Boolean,
    val syncToken: String?,
    val nextAt: String?,
    val appliedLedgerRevision: Long,
)

@Entity(tableName = "reminder_days", primaryKeys = ["ledgerId", "storageGeneration", "day"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"])], indices = [Index(value = ["ledgerId", "token"], unique = true)])
internal data class ReminderDayEntity(
    val ledgerId: String,
    val storageGeneration: String,
    val day: String,
    val token: String,
    val status: String,
    val membersJson: String,
    val createdAt: String,
    val postedAt: String?,
)

internal data class ReminderSignal(val ledgerId: String, val storageGeneration: String, val ledgerRevision: Long, val settingsRevision: Long, val dirty: Boolean)

@Entity(tableName = "reminder_rules", primaryKeys = ["ledgerId", "ruleId"], foreignKeys = [ForeignKey(entity = PaymentRuleEntity::class, parentColumns = ["ledgerId", "id"], childColumns = ["ledgerId", "ruleId"])])
internal data class ReminderRuleEntity(val ledgerId: String, val storageGeneration: String, val ruleId: String, val enabled: Boolean, val revision: Long)

@Dao
internal interface ReminderDao {
    @Query("SELECT * FROM reminder_rules WHERE ledgerId=:ledger ORDER BY ruleId")
    suspend fun rules(ledger: String): List<ReminderRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(value: ReminderRuleEntity)

    @Update suspend fun updateRule(value: ReminderRuleEntity): Int

    @Query("SELECT l.id AS ledgerId,l.storageGeneration,l.revision AS ledgerRevision,COALESCE(s.revision,0) AS settingsRevision,COALESCE(s.dirty,1) AS dirty FROM ledgers l LEFT JOIN reminder_settings s ON s.ledgerId=l.id ORDER BY l.id")
    fun signals(): Flow<List<ReminderSignal>>

    @Query("SELECT * FROM reminder_settings WHERE ledgerId=:ledger")
    suspend fun settings(ledger: String): ReminderSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettings(value: ReminderSettingsEntity)

    @Update suspend fun updateSettings(value: ReminderSettingsEntity): Int

    @Query("UPDATE reminder_settings SET dirty=1 WHERE ledgerId=:ledger")
    suspend fun dirty(ledger: String)

    @Query("SELECT * FROM reminder_days WHERE ledgerId=:ledger AND storageGeneration=:generation ORDER BY day,token")
    suspend fun days(ledger: String, generation: String): List<ReminderDayEntity>

    @Query("SELECT * FROM reminder_days WHERE ledgerId=:ledger AND token=:token")
    suspend fun day(ledger: String, token: String): ReminderDayEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDay(value: ReminderDayEntity)

    @Update suspend fun updateDay(value: ReminderDayEntity): Int
}
