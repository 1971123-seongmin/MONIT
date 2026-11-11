package com.monit.feature.accounts.navigation

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class AccountRegistrationDestinationTest {
    /** 독립 등록은 입력 화면의 호출 ID와 요청 ID를 갖지 않는다. */
    @Test
    fun standaloneRegistrationRejectsInputCallerFields() {
        val route =
            AccountRegistrationDestination("registration", AccountRegistrationOrigin.ACCOUNTS)
        assertNull(route.callerEntryId)
        assertNull(route.selectionRequestId)
        assertFailsWith<IllegalArgumentException> {
            route.copy(callerEntryId = "entry", selectionRequestId = "request")
        }
        assertFailsWith<IllegalArgumentException> { route.copy(registrationEntryId = " ") }
    }

    /** 입력 경유 등록은 정확한 호출 인스턴스와 선택 요청을 모두 요구한다. */
    @Test
    fun inputRegistrationRequiresBothCallerAndRequest() {
        val route = AccountRegistrationDestination(
            "registration", AccountRegistrationOrigin.TRANSACTION_ENTRY, "entry", "request",
        )
        assertEquals("entry", route.callerEntryId)
        assertEquals("request", route.selectionRequestId)
        assertFailsWith<IllegalArgumentException> { route.copy(callerEntryId = null) }
        assertFailsWith<IllegalArgumentException> { route.copy(selectionRequestId = " ") }
    }
}
