package com.monit.core.model

/** 같은 writer 스냅샷에서 확정한 대상 revision 충돌. 금융 성공 영수증과 구분한다. */
data class TransactionRejection(
    val context: LedgerContext,
    val operationId: String,
    val transactionId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val actualDeletedAt: String?,
    val rejectedAt: String,
)

/** 영속 거절 commit 이후에만 전달한다. 취소·I/O·장부 CAS 실패는 이 예외가 아니다. */
class TransactionRejectedException(val rejection: TransactionRejection) : IllegalStateException("Transaction revision changed")
