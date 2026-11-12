package com.monit.feature.transactions.navigation

import java.time.LocalDate
import kotlinx.serialization.Serializable

/** 신규 입력의 UI 초기값. 기존 폼·금융 제출·장부 context를 route에 복제하지 않는다. */
@Serializable
data class TransactionEntryDestination(
    val entryId: String,
    val initialKind: TransactionEntryKind,
    val initialDate: String? = null,
    val origin: TransactionEntryOrigin? = null,
) {
    init {
        require(entryId.isNotBlank())
        initialDate?.let {
            require(LocalDate.parse(it).toString() == it) { "Expected an ISO date" }
        }
    }
}
