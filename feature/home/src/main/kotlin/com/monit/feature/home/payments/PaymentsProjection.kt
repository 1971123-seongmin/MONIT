package com.monit.feature.home.payments

import com.monit.core.model.*
import java.text.NumberFormat
import java.util.Locale

internal fun paymentMoney(value: Long): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원"
internal fun paymentPurpose(value: PaymentPurpose): String = when (value) {
    PaymentPurpose.GENERAL -> "일반 납입"
    PaymentPurpose.INSURANCE -> "보험"
    PaymentPurpose.SAVINGS -> "저축"
    PaymentPurpose.INVESTMENT -> "투자"
}
internal fun paymentProjection(snapshot: PaymentMonthSnapshot, ledger: LedgerSnapshot, prepared: List<PreparedPayment>): PaymentsUiState {
    val accounts = ledger.accounts.associateBy { it.id }
    fun transaction(value: CreateTransaction): String = "${value.date} · ${paymentMoney(value.amount)} · ${accounts[value.debitAccountId]?.name ?: "출금 계좌"}" + (value.creditAccountId?.let { " → ${accounts[it]?.name ?: "입금 계좌"}" } ?: "")
    val items = snapshot.items.map { item ->
        val label = when {
            !item.active -> "일정 종료로 비활성"
            item.status == PaymentStatus.COMPLETED -> "완료 · 직접 확인한 기록"
            item.status == PaymentStatus.SKIPPED -> "건너뜀 · 완료가 아니에요"
            item.status == PaymentStatus.RECONFIRM -> "재확인 필요 · 금융 근거가 바뀌었어요"
            else -> "미완료 · 직접 납입 여부를 확인해 주세요"
        }
        val endedException = snapshot.rules.find { it.id == item.reference.ruleId }?.endMonth?.let { snapshot.month > it && item.active } == true
        MonthlyPaymentUiState(
            item.reference.id, item.terms.name, "${snapshot.month} · ${item.dueDate} · ${paymentMoney(item.terms.amount)}", label + if (endedException) " · 종료 일정 이후 보존된 이력" else "",
            item.status == PaymentStatus.COMPLETED, item.status == PaymentStatus.SKIPPED, item.active, item.transactionId != null, item.status == PaymentStatus.RECONFIRM, item.confirmedTransaction?.let(::transaction),
        )
    }
    return PaymentsUiState(
        snapshot.month,
        "${snapshot.month.take(4)}년 ${snapshot.month.takeLast(2).toInt()}월",
        "미완료 ${items.count { it.active && !it.completed && !it.skipped && !it.needsReconfirmation }} · 완료 ${items.count { it.active && it.completed }} · 재확인 ${items.count { it.active && it.needsReconfirmation }}",
        items,
        snapshot.rules.map { PaymentRuleUiState(it.id, it.terms.name, "${paymentPurpose(it.terms.purpose)} · 매월 ${it.terms.day}일 · ${paymentMoney(it.terms.amount)} · ${it.startMonth}~${it.endMonth ?: "종료 미정"}") },
        ledger.accounts.filter { it.status == AccountStatus.ACTIVE }.map { PaymentAccountUiState(it.id, it.name, it.nature.name) },
        ledger.transactions.sortedWith(compareByDescending<LedgerTransaction> { it.date }.thenBy { it.id }).map {
            PaymentTransactionUiState(
                it.id,
                "${when (it.kind) {
                    TransactionKind.INCOME -> "수입"
                    TransactionKind.EXPENSE -> "지출"
                    TransactionKind.TRANSFER -> "이체"
                }} · ${paymentMoney(it.amount)}",
                transaction(CreateTransaction(it.id, it.kind, it.date, it.amount, it.debitAccountId, it.creditAccountId, it.memo)),
            )
        },
        prepared.map {
            PaymentRequestUiState(
                it.operationId,
                when (it.command) {
                    is PaymentCommand.Confirm -> "이전 납입 확인 요청"
                    is PaymentCommand.ChangeItem -> "이전 납입 상태 변경"
                    else -> "이전 반복 규칙 요청"
                },
            )
        },
    )
}

internal fun paymentReview(command: PaymentCommand, selected: MonthlyPaymentUiState?, accounts: List<PaymentAccountUiState>, fields: Map<String, String> = emptyMap()): PaymentReviewUiState = when (command) {
    is PaymentCommand.RegisterRule -> PaymentReviewUiState("반복 납입 등록 확인", command.terms.name, "매월 ${command.terms.day}일 · ${paymentMoney(command.terms.amount)}", "${command.startMonth}~${command.endMonth ?: "종료 미정"}", "예정만 등록합니다. 자동 송금·거래 생성·납입 완료는 하지 않아요.", "반복 납입 등록")

    is PaymentCommand.ChangeRule -> PaymentReviewUiState("반복 규칙 변경 확인", command.terms.name, "매월 ${command.terms.day}일 · ${paymentMoney(command.terms.amount)}", "적용 시작 ${command.effectiveMonth}", "해당 월 이후 미래 미완료만 갱신하며 현재·과거·완료·건너뜀·재확인은 보존해요.", "규칙 변경 확정")

    is PaymentCommand.EndRule -> PaymentReviewUiState("일정 종료 확인", "종료 월 ${command.endMonth} 포함", "거래와 확인 이력은 보존해요", "", "종료 이후 미완료는 비활성화합니다. 완료·건너뜀·재확인 이력은 유지하며 자동 재활성화하지 않아요.", "일정 종료 확정")

    is PaymentCommand.ChangeItem -> PaymentReviewUiState(
        "납입 상태 변경 확인",
        selected?.name ?: "월별 납입",
        selected?.schedule ?: command.item.month,
        "",
        when (command.action) {
            PaymentAction.UNLINK -> "완료 연결만 해제합니다. 거래·잔액·실제 월 합계는 그대로예요."
            PaymentAction.SKIP -> "건너뜀은 완료가 아니며 거래와 납입액을 만들지 않아요."
            PaymentAction.UNSKIP -> "다시 미완료로 바꿉니다. 거래는 자동 생성하지 않아요."
        },
        when (command.action) {
            PaymentAction.UNLINK -> "연결 해제 확인"
            PaymentAction.SKIP -> "건너뛰기 확인"
            PaymentAction.UNSKIP -> "건너뜀 해제 확인"
        },
    )

    is PaymentCommand.Confirm -> {
        val tx = command.transaction.value
        val existing = command.transaction.financialRevision != null
        val schedule = if (fields["plannedName"] != null) "${fields["plannedName"]} · ${command.item.month}\n${fields["plannedDate"] ?: ""} · ${fields["plannedAmount"]?.toLongOrNull()?.let(::paymentMoney) ?: "예정액 확인 필요"}" else "${selected?.name ?: "월별 납입"} · ${command.item.month}\n${selected?.schedule ?: ""}"
        PaymentReviewUiState(
            "납입 내용 확인",
            schedule,
            "${if (existing) "확인할 거래" else "기록할 거래"} · ${if (tx.kind == TransactionKind.TRANSFER) "이체" else "지출"}\n${paymentMoney(tx.amount)}",
            "${tx.date} · ${accounts.find { it.id == tx.debitAccountId }?.name ?: "출금 계좌"}" + (tx.creditAccountId?.let { " → ${accounts.find { account -> account.id == it }?.name ?: "입금 계좌"}" } ?: ""),
            if (command.full) "이번 회차에 낼 금액을 모두 냈음을 확인해요.${command.reason.takeIf { it.isNotBlank() }?.let { "\n차이 사유: $it" } ?: ""}"
            else "일부만 기록하며 이번 회차는 미완료로 유지해요. 분할 합산·잔여 거래 자동 생성은 하지 않아요.\n사유: ${command.reason}",
            if (!command.full) "거래만 보존하고 미완료 유지" else if (existing) "확인하고 납입 완료" else "기록하고 납입 완료",
        )
    }
}
