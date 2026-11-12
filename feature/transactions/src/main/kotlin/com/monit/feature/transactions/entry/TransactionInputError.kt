package com.monit.feature.transactions.entry

enum class TransactionInputError(val message: String) {
    AMOUNT("1~999,999,999,999원 범위의 정수 금액을 입력해 주세요."),
    DATE("1900년부터 오늘까지의 날짜를 YYYY-MM-DD로 입력해 주세요."),
    DEBIT_ACCOUNT("사용할 활성 출금 계좌를 선택해 주세요."),
    CREDIT_ACCOUNT("출금 계좌와 다른 활성 받는 계좌를 선택해 주세요."),
    MEMO("메모 길이와 문자를 확인해 주세요."),
}
