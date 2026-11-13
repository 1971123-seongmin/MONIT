package com.monit.feature.home.payments

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme

@Preview(showBackground = true)
@Composable
private fun PaymentDetailLightPreview() {
    PaymentPreview(PaymentsMode.DETAIL)
}

@Preview(showBackground = true)
@Composable
private fun PaymentDetailDarkPreview() {
    PaymentPreview(
        PaymentsMode.DETAIL,
        dark = true,
    )
}

@Preview(showBackground = true)
@Composable
private fun PaymentReviewLightPreview() {
    PaymentPreview(PaymentsMode.REVIEW)
}

@Preview(showBackground = true)
@Composable
private fun PaymentReviewDarkPreview() {
    PaymentPreview(
        PaymentsMode.REVIEW,
        dark = true,
    )
}

@Preview(showBackground = true, widthDp = 320, fontScale = 2f)
@Composable
private fun PaymentPartialLargePreview() {
    PaymentPreview(
        PaymentsMode.REVIEW,
        partial = true,
    )
}

@Preview(showBackground = true)
@Composable
private fun PaymentReconfirmationPreview() {
    PaymentPreview(
        PaymentsMode.DETAIL,
        reconfirm = true,
    )
}

/** 고정된 합성 표시 값만 사용하며 ViewModel·Repository나 금융 쓰기를 실행하지 않는다. */
@Composable
private fun PaymentPreview(
    mode: PaymentsMode,
    dark: Boolean = false,
    partial: Boolean = false,
    reconfirm: Boolean = false,
) {
    val selected = MonthlyPaymentUiState(
        id = "preview",
        name = "합성 보험",
        schedule = "2026-11 · 2026-11-30 · 30,000원",
        status = if (reconfirm) "재확인 필요 · 금융 근거가 바뀌었어요" else "미완료 · 직접 납입 여부를 확인해 주세요",
        completed = false,
        skipped = false,
        active = true,
        hasTransaction = reconfirm,
        needsReconfirmation = reconfirm,
        confirmed = if (reconfirm) "2026-11-12 · 30,000원 · 합성 출금 계좌" else null,
    )

    MonitTheme(darkTheme = dark) {
        PaymentsScreen(
            state = PaymentsState(
                mode = mode,
                phase = PaymentsPhase.READY,
                ui = PaymentsUiState(
                    selected = selected,
                    review = PaymentReviewUiState(
                        title = "납입 내용 확인",
                        schedule = selected.name + " · " + selected.schedule,
                        transaction = if (partial) "기록할 거래 · 지출\n25,000원" else "기록할 거래 · 지출\n30,000원",
                        detail = "2026-11-12 · 합성 출금 계좌",
                        explanation = if (partial) "일부만 기록하고 이번 회차는 미완료로 유지해요.\n사유: 일부만 납입" else "이번 회차에 낼 금액을 모두 냈음을 확인해요.",
                        action = if (partial) "거래만 보존하고 미완료 유지" else "기록하고 납입 완료",
                    ),
                ),
            ),
            onBack = {},
            onReload = {},
            onMonth = {},
            onSelect = {},
            onRegister = {},
            onRule = { _, _ -> },
            onNewPayment = {},
            onChooseExisting = {},
            onTransaction = {},
            onEditTransaction = {},
            onAction = {},
            onEdit = { _, _ -> },
            onPicker = {},
            onReview = {},
            onSubmit = {},
            onCancel = {},
            onCheck = {},
            onRetrySubmit = {},
            onRelease = {},
            onFinish = {},
            onRecover = {},
            onFlush = {},
            onDismissMessage = {},
        )
    }
}
