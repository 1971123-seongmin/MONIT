package com.monit.core.model

/** 수정할 전체 값과 사용자가 확인한 객체 revision. 생성 DTO의 금융 검증을 재사용한다. */
data class UpdateTransaction(val value: CreateTransaction, val expectedRevision: Long) {
    init {
        require(expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
    }
}

data class DeleteTransaction(val id: String, val expectedRevision: Long) {
    init {
        requireLedgerId(id)
        require(expectedRevision > 0 && expectedRevision < Long.MAX_VALUE)
    }
}
