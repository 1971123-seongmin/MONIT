package com.monit.feature.transactions.navigation

import android.os.Bundle

/** 입력 화면의 후보 반환 자료다. 금융 영수증이나 계좌 자동 적용 명령이 아니다. */
data class RegisteredAccountResult(val selectionRequestId: String, val accountId: String) {
    init {
        require(selectionRequestId.isNotBlank())
        require(accountId.isNotBlank())
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(REQUEST, selectionRequestId)
        putString(ACCOUNT, accountId)
    }

    companion object {
        private const val REQUEST = "selectionRequestId"
        private const val ACCOUNT = "accountId"

        fun fromBundle(value: Bundle): RegisteredAccountResult? {
            val request = value.getString(REQUEST)?.takeIf { it.isNotBlank() } ?: return null
            val account = value.getString(ACCOUNT)?.takeIf { it.isNotBlank() } ?: return null
            return RegisteredAccountResult(request, account)
        }

        fun key(selectionRequestId: String): String {
            require(selectionRequestId.isNotBlank())
            return "monit.registeredAccount:$selectionRequestId"
        }
    }
}
