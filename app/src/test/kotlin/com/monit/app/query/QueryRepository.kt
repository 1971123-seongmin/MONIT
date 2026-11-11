package com.monit.app.query

import com.monit.core.model.*
import java.time.LocalDate

/** 읽기 결과와 완료 순서만 제어한다. 조회 기능에서 어떤 금융 쓰기든 호출하면 즉시 실패한다. */
internal class QueryRepository : LedgerRepository {
    override suspend fun submissionRecovery(context: LedgerContext): com.monit.core.model.SubmissionRecoverySnapshot = error("이 테스트는 제출 발견을 호출하지 않는다")

    override suspend fun finishSubmissionRecovery(
        context: LedgerContext,
        operationId: String,
    ): com.monit.core.model.FinishSubmissionRecoveryResult = error("이 테스트는 제출 종료를 호출하지 않는다")

    var openCalls = 0
    val snapshotDates = mutableListOf<LocalDate>()
    var onOpen: suspend () -> LedgerEntryResult = { LedgerEntryResult.Ready(testContext) }
    var onSnapshot: suspend (LocalDate) -> LedgerSnapshot = {
        LedgerSnapshot(1, emptyList(), emptyList(), emptyMap(), 0, 0)
    }

    override suspend fun openLedger(): LedgerEntryResult {
        openCalls++
        return onOpen()
    }

    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        snapshotDates += asOf
        return onSnapshot(asOf)
    }

    override suspend fun initialize(context: LedgerContext): Unit = error("조회는 initialize를 호출하지 않는다")

    override suspend fun createAccount(
        context: LedgerContext,
        operationId: String,
        value: CreateAccount,
    ): WriteReceipt = error("조회는 계좌를 저장하지 않는다")

    override suspend fun createTransaction(
        context: LedgerContext,
        operationId: String,
        value: CreateTransaction,
    ): WriteReceipt = error("조회는 거래를 저장하지 않는다")

    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? = error("제출 복구는 별도 범위다")

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> = error("제출 복구는 별도 범위다")

    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot = error("제출 복구는 별도 범위다")

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt = error("제출 재실행은 별도 범위다")
}

internal val testContext = LedgerContext(queryId(1), queryId(2))
internal fun queryId(number: Int) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')
