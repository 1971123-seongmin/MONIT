package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.withContext

internal class RoomPaymentRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val ledger: RoomLedgerRepository,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) : PaymentRepository {
    private val writer get() = PaymentWriter(database, clock, ledger)
    override suspend fun month(context: LedgerContext, month: String): PaymentMonthSnapshot = withContext(contexts.default) {
        database.withTransaction { PaymentStore(database, clock).month(context, month, ledger.requireContext(context).revision) }
    }
    override suspend fun submit(context: LedgerContext, operationId: String, command: PaymentCommand, draft: DraftSubmission?): WriteReceipt = withContext(contexts.default) { writer.submit(context, operationId, command, draft) }
    override suspend fun rejection(context: LedgerContext, operationId: String): PaymentRejection? = ledger.paymentRejection(context, operationId)
    override suspend fun prepared(context: LedgerContext): List<PreparedPayment> = writer.prepared(context)
    override suspend fun attempt(context: LedgerContext, operationId: String): PaymentAttempt = writer.attempt(context, operationId)
    override suspend fun retry(context: LedgerContext, operationId: String): WriteReceipt = withContext(contexts.default) { writer.retry(context, operationId) }
    override suspend fun release(context: LedgerContext, operationId: String, command: PaymentCommand?): PaymentAttempt = writer.release(context, operationId, command)
}
