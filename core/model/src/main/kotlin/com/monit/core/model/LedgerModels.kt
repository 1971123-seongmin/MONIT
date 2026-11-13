package com.monit.core.model

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

fun requireLedgerId(value: String): String {
    require(value.length == 36) { "Expected a lowercase UUID" }
    require(UUID.fromString(value).toString() == value) { "Expected a lowercase UUID" }
    return value
}

fun requireLedgerText(value: String, allowBlank: Boolean = false): String {
    require(allowBlank || value.isNotBlank()) { "Text must not be blank" }
    require(value.length <= 16 * 1024) { "Text exceeds the supported UTF-8 size" }
    val bytes = try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Text must contain valid Unicode", error)
    }
    require(bytes.remaining() <= 16 * 1024) { "Text exceeds the supported UTF-8 size" }
    return value
}

data class LedgerContext(val ledgerId: String, val storageGeneration: String) {
    init {
        requireLedgerId(ledgerId)
        requireLedgerId(storageGeneration)
    }
}

enum class AccountNature { GENERAL, SAVINGS, INVESTMENT }
enum class AccountStatus { ACTIVE, ARCHIVED }
enum class TransactionKind { INCOME, EXPENSE, TRANSFER }

data class CreateAccount(
    val id: String,
    val anchorId: String,
    val name: String,
    val nature: AccountNature,
    val anchorDate: LocalDate,
    val unknownReason: String,
) {
    init {
        requireLedgerId(id)
        requireLedgerId(anchorId)
        requireLedgerText(name)
        requireLedgerText(unknownReason)
    }
}

data class CreateTransaction(
    val id: String,
    val kind: TransactionKind,
    val date: LocalDate,
    val amount: Long,
    val debitAccountId: String? = null,
    val creditAccountId: String? = null,
    val memo: String = "",
) {
    init {
        requireLedgerId(id)
        requireTransactionAmount(amount)
        debitAccountId?.let(::requireLedgerId)
        creditAccountId?.let(::requireLedgerId)
        requireLedgerText(memo, allowBlank = true)
        require(
            when (kind) {
                TransactionKind.INCOME -> debitAccountId == null && creditAccountId != null

                TransactionKind.EXPENSE -> debitAccountId != null && creditAccountId == null

                TransactionKind.TRANSFER ->
                    debitAccountId != null && creditAccountId != null &&
                        debitAccountId != creditAccountId
            },
        ) { "Transaction accounts do not match its kind" }
    }
}

data class BalanceAnchor(
    val id: String,
    val date: LocalDate,
    val amount: Long?,
    val confirmedAt: Instant?,
    val unknownReason: String?,
) {
    init {
        requireLedgerId(id)
        require(date >= LocalDate.of(1900, 1, 1))
        if (amount == null) {
            require(confirmedAt == null && !unknownReason.isNullOrBlank())
        } else {
            require(amount in -999_999_999_999L..999_999_999_999L)
            require(confirmedAt != null && unknownReason == null)
        }
    }
}

data class LedgerAccount(
    val id: String,
    val name: String,
    val nature: AccountNature,
    val status: AccountStatus,
    val revision: Long,
    val anchor: BalanceAnchor,
)

data class LedgerTransaction(
    val id: String,
    val kind: TransactionKind,
    val date: LocalDate,
    val amount: Long,
    val debitAccountId: String?,
    val creditAccountId: String?,
    val debitNature: AccountNature?,
    val creditNature: AccountNature?,
    val memo: String,
    val revision: Long,
    val financialRevision: Long,
)

data class EntityRef(val collection: String, val id: String, val revision: Long)
data class WriteReceipt(
    val operationId: String,
    val ledgerRevision: Long,
    val entities: List<EntityRef>,
)

data class PendingWrite(val operationId: String, val requestJson: String)

/** 같은 DB 스냅샷에서 만든 조회 결과. 미확인 잔액은 null이며 거래 합계와 구분한다. */
data class LedgerSnapshot(
    val revision: Long,
    val accounts: List<LedgerAccount>,
    val transactions: List<LedgerTransaction>,
    val balances: Map<String, Long?>,
    val income: Long,
    val expense: Long,
)

enum class LedgerConflict {
    STALE_GENERATION,
    DUPLICATE_REQUEST,
    DUPLICATE_ENTITY,
    MISSING_REFERENCE,
    ARCHIVED_ACCOUNT,
    STALE_REVISION,
}

class LedgerConflictException(val conflict: LedgerConflict) : IllegalStateException("Ledger conflict: $conflict")

/** 계좌 생성·거래 생성/수정/논리 삭제 경계. 납입·복원은 포함하지 않는다. */
interface LedgerRepository {
    /** 단일 장부를 찾거나, 업무 기록까지 비어 있는 최초 저장소에서만 원자적으로 생성한다. */
    suspend fun openLedger(): LedgerEntryResult

    /** 같은 읽기 트랜잭션의 제출 복구 자료. 조회는 재실행 예약이나 저장 성공 판정이 아니다. */
    suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot

    /** 활성 참조와 이전 버전의 pending을 같은 읽기 스냅샷에서 대조한다. */
    suspend fun submissionRecovery(context: LedgerContext): SubmissionRecoverySnapshot

    /** 명시적인 결과 확인 후 선택한 성공 참조만 종료한다. 금융 원장은 보존한다. */
    suspend fun finishSubmissionRecovery(
        context: LedgerContext,
        operationId: String,
    ): FinishSubmissionRecoveryResult

    suspend fun initialize(context: LedgerContext)
    suspend fun createAccount(
        context: LedgerContext,
        operationId: String,
        value: CreateAccount,
    ): WriteReceipt

    suspend fun createTransaction(
        context: LedgerContext,
        operationId: String,
        value: CreateTransaction,
    ): WriteReceipt

    suspend fun updateTransaction(context: LedgerContext, operationId: String, value: UpdateTransaction): WriteReceipt
    suspend fun deleteTransaction(context: LedgerContext, operationId: String, value: DeleteTransaction): WriteReceipt

    suspend fun paymentRejection(context: LedgerContext, operationId: String): PaymentRejection?
    suspend fun rejection(context: LedgerContext, operationId: String): TransactionRejection?
    suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt?
    suspend fun pendingWrites(context: LedgerContext): List<PendingWrite>
    suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt
    suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot
}
