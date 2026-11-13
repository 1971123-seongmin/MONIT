package com.monit.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "ledgers")
internal data class LedgerEntity(
    @androidx.room.PrimaryKey val id: String,
    val storageGeneration: String,
    val revision: Long,
)

@Entity(
    tableName = "accounts",
    primaryKeys = ["ledgerId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
        ),
    ],
)
internal data class AccountEntity(
    val ledgerId: String,
    val id: String,
    val name: String,
    val nature: String,
    val status: String,
    val revision: Long,
)

@Entity(
    tableName = "anchors",
    primaryKeys = ["ledgerId", "id"],
    indices = [Index(value = ["ledgerId", "accountId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["ledgerId", "id"],
            childColumns = ["ledgerId", "accountId"],
        ),
    ],
)
internal data class AnchorEntity(
    val ledgerId: String,
    val id: String,
    val accountId: String,
    val date: String,
    val amount: Long?,
    val confirmedAt: String?,
    val unknownReason: String?,
    val revision: Long,
)

@Entity(
    tableName = "transactions",
    primaryKeys = ["ledgerId", "id"],
    indices = [
        Index(value = ["ledgerId", "debitAccountId"]),
        Index(value = ["ledgerId", "creditAccountId"]), Index(value = ["ledgerId", "date"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["ledgerId", "id"],
            childColumns = ["ledgerId", "debitAccountId"],
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["ledgerId", "id"],
            childColumns = ["ledgerId", "creditAccountId"],
        ),
    ],
)
internal data class TransactionEntity(
    val ledgerId: String,
    val id: String,
    val kind: String,
    val date: String,
    val amount: Long,
    val debitAccountId: String?,
    val creditAccountId: String?,
    val debitNature: String?,
    val creditNature: String?,
    val memo: String,
    val revision: Long,
    val financialRevision: Long,
    val deletedAt: String? = null,
)

@Entity(
    tableName = "operations",
    primaryKeys = ["ledgerId", "operationId"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
        ),
    ],
)
internal data class OperationEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
    val kind: String,
    val requestJson: String,
    val requestSha256: String,
    val committedAt: String,
    val ledgerRevision: Long,
    val resultJson: String,
)

/** 제출 의도는 복구 자료이며 성공 원장 operations와 분리한다. */
@Entity(
    tableName = "submissions",
    primaryKeys = ["ledgerId", "operationId"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
        ),
    ],
)
internal data class SubmissionEntity(
    val ledgerId: String,
    val operationId: String,
    val requestJson: String,
    val requestSha256: String,
)

/** 성공 후 pending이 소비되어도 명시적 종료 전까지 최초 제출을 발견한다. */
@Entity(
    tableName = "submission_references",
    primaryKeys = ["ledgerId", "operationId"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
        ),
    ],
)
internal data class SubmissionReferenceEntity(
    val ledgerId: String,
    val operationId: String,
    val storageGeneration: String,
)

@Dao
internal interface LedgerDao {
    @Query("SELECT * FROM ledgers ORDER BY id LIMIT 2")
    suspend fun entryLedgers(): List<LedgerEntity>

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM accounts) OR EXISTS(SELECT 1 FROM anchors)
            OR EXISTS(SELECT 1 FROM payment_rules) OR EXISTS(SELECT 1 FROM payment_rule_versions)
            OR EXISTS(SELECT 1 FROM payment_items) OR EXISTS(SELECT 1 FROM payment_events)
            OR EXISTS(SELECT 1 FROM payment_rejections) OR EXISTS(SELECT 1 FROM payment_intents)
            OR EXISTS(SELECT 1 FROM submission_rejections) OR EXISTS(SELECT 1 FROM transaction_history) OR EXISTS(SELECT 1 FROM transactions) OR EXISTS(SELECT 1 FROM operations)
            OR EXISTS(SELECT 1 FROM submissions)
            OR EXISTS(SELECT 1 FROM submission_references)
            OR EXISTS(SELECT 1 FROM form_drafts)
    """,
    )
    suspend fun hasBusinessRecords(): Boolean

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM accounts b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM anchors b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_rules b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_rule_versions b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_items b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_events b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_rejections b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM payment_intents b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM submission_rejections b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM transaction_history b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM transactions b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM operations b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM submissions b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM submission_references b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
            OR EXISTS(SELECT 1 FROM form_drafts b WHERE NOT EXISTS(SELECT 1 FROM ledgers l WHERE l.id = b.ledgerId))
    """,
    )
    suspend fun hasRecordsWithoutLedger(): Boolean

    @Query("SELECT * FROM ledgers WHERE id = :id")
    suspend fun ledger(id: String): LedgerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedger(value: LedgerEntity)

    @Query("UPDATE ledgers SET revision = revision + 1 WHERE id = :id AND revision = :expected AND revision < 9223372036854775807")
    suspend fun advanceRevision(id: String, expected: Long): Int

    @Query("SELECT * FROM accounts WHERE ledgerId = :ledger AND id = :id")
    suspend fun account(ledger: String, id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE ledgerId = :ledger ORDER BY id")
    suspend fun accounts(ledger: String): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(value: AccountEntity)

    @Query("SELECT * FROM anchors WHERE ledgerId = :ledger ORDER BY id")
    suspend fun anchors(ledger: String): List<AnchorEntity>

    @Query("SELECT * FROM anchors WHERE ledgerId = :ledger AND id = :id")
    suspend fun anchor(ledger: String, id: String): AnchorEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnchor(value: AnchorEntity)

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledger ORDER BY date, id")
    suspend fun transactions(ledger: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE ledgerId = :ledger AND id = :id")
    suspend fun transaction(ledger: String, id: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(value: TransactionEntity)

    @androidx.room.Update
    suspend fun updateTransaction(value: TransactionEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransactionHistory(value: TransactionHistoryEntity)

    @Query("SELECT * FROM transaction_history WHERE ledgerId = :ledger AND id = :id ORDER BY revision")
    suspend fun transactionHistory(ledger: String, id: String): List<TransactionHistoryEntity>

    @Query("SELECT * FROM submission_rejections WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun rejection(ledger: String, operation: String): SubmissionRejectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRejection(value: SubmissionRejectionEntity)

    @Query("SELECT * FROM operations WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun operation(ledger: String, operation: String): OperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(value: OperationEntity)

    @Query("SELECT * FROM submissions WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun submission(ledger: String, operation: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE ledgerId = :ledger ORDER BY operationId")
    suspend fun submissions(ledger: String): List<SubmissionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmission(value: SubmissionEntity)

    @Query("DELETE FROM submissions WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun deleteSubmission(ledger: String, operation: String): Int

    @Query("SELECT * FROM submission_references WHERE ledgerId = :ledger ORDER BY operationId")
    suspend fun submissionReferences(ledger: String): List<SubmissionReferenceEntity>

    @Query("SELECT * FROM submission_references WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun submissionReference(ledger: String, operation: String): SubmissionReferenceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmissionReference(value: SubmissionReferenceEntity)

    @Query("DELETE FROM submission_references WHERE ledgerId = :ledger AND operationId = :operation")
    suspend fun deleteSubmissionReference(ledger: String, operation: String): Int
}

@Database(
    entities = [
        LedgerEntity::class, AccountEntity::class, AnchorEntity::class,
        TransactionEntity::class, OperationEntity::class, SubmissionEntity::class, SubmissionReferenceEntity::class,
        FormDraftEntity::class, TransactionHistoryEntity::class, SubmissionRejectionEntity::class,
        PaymentRuleEntity::class, PaymentRuleVersionEntity::class, PaymentItemEntity::class, PaymentEventEntity::class, PaymentRejectionEntity::class, PaymentIntentEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
internal abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun formDraftDao(): FormDraftDao
    abstract fun paymentDao(): PaymentDao

    /** 현재 트랜잭션의 writer 연결 설정을 읽어 내구성 설정이 유실되면 쓰기를 거부한다. */
    fun requireDurableWriter() {
        val connection = openHelper.writableDatabase
        connection.query("PRAGMA synchronous").use {
            check(it.moveToFirst() && it.getInt(0) == 2) { "FULL synchronous mode is required" }
        }
        connection.query("PRAGMA journal_mode").use {
            check(it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true)) {
                "WAL journal mode is required"
            }
        }
    }
}

internal fun createLedgerDatabase(context: Context, name: String): LedgerDatabase = Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name)
    .addMigrations(LedgerMigration1To2, LedgerMigration2To3, LedgerMigration3To4, LedgerMigration4To5)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .addCallback(object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA synchronous=FULL")
        }
    })
    .build()
