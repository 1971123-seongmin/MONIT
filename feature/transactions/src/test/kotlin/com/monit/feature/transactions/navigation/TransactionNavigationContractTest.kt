package com.monit.feature.transactions.navigation

import android.app.Application
import android.os.Bundle
import java.time.format.DateTimeParseException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TransactionNavigationContractTest {
    /** 초기 날짜가 없거나 실제 ISO 날짜인 경우에만 신규 입력 경로를 구성한다. */
    @Test
    fun entryAcceptsOptionalIsoDateAndRejectsInvalidArguments() {
        assertNull(TransactionEntryDestination("entry", TransactionEntryKind.EXPENSE).initialDate)
        assertEquals(
            "2028-02-29", TransactionEntryDestination(
                "entry", TransactionEntryKind.INCOME, "2028-02-29",
            ).initialDate
        )
        assertFailsWith<IllegalArgumentException> {
            TransactionEntryDestination(" ", TransactionEntryKind.TRANSFER)
        }
        assertFailsWith<DateTimeParseException> {
            TransactionEntryDestination("entry", TransactionEntryKind.EXPENSE, "2026-02-29")
        }
        assertFailsWith<DateTimeParseException> {
            TransactionEntryDestination("entry", TransactionEntryKind.EXPENSE, "2026-1-1")
        }
    }

    /** 반환 후보는 요청·계좌 ID를 보존하며 요청별 저장 키가 서로 다르다. */
    @Test
    fun resultRoundTripKeepsRequestAndAccount() {
        val result = RegisteredAccountResult("request-one", "synthetic-account")
        assertEquals(result, RegisteredAccountResult.fromBundle(result.toBundle()))
        assertNotEquals(
            RegisteredAccountResult.key("request-one"),
            RegisteredAccountResult.key("request-two")
        )
    }

    /** 누락·빈 값·잘못된 타입은 적용 가능한 후보로 해석하지 않는다. */
    @Test
    fun malformedBundleDoesNotProduceCandidate() {
        assertNull(RegisteredAccountResult.fromBundle(Bundle()))
        assertNull(RegisteredAccountResult.fromBundle(Bundle().apply {
            putString("selectionRequestId", "request")
            putString("accountId", " ")
        }))
        assertNull(RegisteredAccountResult.fromBundle(Bundle().apply {
            putString("selectionRequestId", "request")
            putInt("accountId", 7)
        }))
        assertFailsWith<IllegalArgumentException> { RegisteredAccountResult("", "account") }
        assertFailsWith<IllegalArgumentException> { RegisteredAccountResult("request", "") }
        assertFailsWith<IllegalArgumentException> { RegisteredAccountResult.key(" ") }
    }
}
