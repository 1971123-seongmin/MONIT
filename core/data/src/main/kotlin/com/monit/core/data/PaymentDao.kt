package com.monit.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface PaymentDao {
    @Query("SELECT * FROM payment_intents WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun intent(ledger: String, operation: String): PaymentIntentEntity?

    @Query("SELECT * FROM payment_intents WHERE ledgerId = :ledger AND status = 'READY' ORDER BY operationId")
    suspend fun intents(ledger: String): List<PaymentIntentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntent(value: PaymentIntentEntity)

    @Query("UPDATE payment_intents SET status = :status WHERE ledgerId = :ledger AND operationId = :operation AND status = 'READY'")
    suspend fun closeIntent(ledger: String, operation: String, status: String): Int

    @Query("SELECT * FROM payment_rules WHERE ledgerId = :ledger ORDER BY id")
    suspend fun rules(ledger: String): List<PaymentRuleEntity>

    @Query("SELECT * FROM payment_rules WHERE ledgerId = :ledger AND id = :id")
    suspend fun rule(ledger: String, id: String): PaymentRuleEntity?

    @Query("SELECT * FROM payment_rule_versions WHERE ledgerId = :ledger AND ruleId = :rule ORDER BY effectiveMonth, revision")
    suspend fun versions(ledger: String, rule: String): List<PaymentRuleVersionEntity>

    @Query("SELECT * FROM payment_items WHERE ledgerId = :ledger AND month = :month ORDER BY dueDate, id")
    suspend fun month(ledger: String, month: String): List<PaymentItemEntity>

    @Query("SELECT * FROM payment_items WHERE ledgerId = :ledger AND ruleId = :rule ORDER BY month, id")
    suspend fun ruleItems(ledger: String, rule: String): List<PaymentItemEntity>

    @Query("SELECT * FROM payment_items WHERE ledgerId = :ledger AND id = :id")
    suspend fun item(ledger: String, id: String): PaymentItemEntity?

    @Query("SELECT * FROM payment_items WHERE ledgerId = :ledger AND transactionId = :transactionId")
    suspend fun linked(ledger: String, transactionId: String): PaymentItemEntity?

    @Query("SELECT * FROM payment_items WHERE ledgerId = :ledger AND transactionId IS NOT NULL ORDER BY id")
    suspend fun reservations(ledger: String): List<PaymentItemEntity>

    @Query("SELECT DISTINCT transactionId FROM payment_events WHERE ledgerId = :ledger AND kind = 'PARTIAL' AND transactionId IS NOT NULL")
    suspend fun partialTransactions(ledger: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM payment_events WHERE ledgerId = :ledger AND transactionId = :transactionId AND kind = 'PARTIAL')")
    suspend fun hasPartial(ledger: String, transactionId: String): Boolean

    @Query("SELECT * FROM payment_events WHERE ledgerId = :ledger AND itemId = :id ORDER BY changedAt, operationId")
    suspend fun events(ledger: String, id: String): List<PaymentEventEntity>

    @Query("SELECT * FROM payment_rejections WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun rejection(ledger: String, operation: String): PaymentRejectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(value: PaymentRuleEntity)

    @Update suspend fun updateRule(value: PaymentRuleEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(value: PaymentRuleVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(value: PaymentItemEntity)

    @Update suspend fun updateItem(value: PaymentItemEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(value: PaymentEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRejection(value: PaymentRejectionEntity)
}
