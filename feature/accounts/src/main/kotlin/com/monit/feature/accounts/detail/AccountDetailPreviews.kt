package com.monit.feature.accounts.detail

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme

@Preview(name = "Anchor unknown / Light", widthDp = 360, heightDp = 708)
@Preview(name = "Anchor unknown / Dark", widthDp = 360, heightDp = 708, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Anchor unknown / 320dp 200%", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
internal fun AccountUnknownPreview() = AccountDetailPreview(AccountDetailPhase.READY)

@Preview(name = "Anchor review / Light", widthDp = 360, heightDp = 708)
@Preview(name = "Anchor review / Dark", widthDp = 360, heightDp = 708, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Anchor review / 320dp 200%", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
internal fun AnchorReviewPreview() = AccountDetailPreview(AccountDetailPhase.PREVIEW)

/** 합성 고정값만 사용하며 UI 확인을 위해 DB/VM/금융 동작을 실행하지 않는다. */
@Composable
private fun AccountDetailPreview(phase: AccountDetailPhase) {
    val ui = AccountDetailUiState(
        title = "합성 생활비", balance = "미확인", asOf = "2026-11-13", anchorDate = "2026-10-31", anchorAmount = "미확인",
        inclusion = "기준액 미확인 · 현재 잔액 합계 제외", month = "2026-11", deposits = "0원", withdrawals = "0원", net = "0원",
        preview = AnchorPreviewUiState("미확인", "790,000원", "1,000,000원", "합성 최초 마감액 확인", "2,680,000원", "3,470,000원", 2, 3),
    )

    MonitTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface,
        ) {
            AccountDetailScreen(
                state = AccountDetailState(phase, ui),
                onBack = {},
                onRetry = {},
                onEdit = {},
                onMonthChange = {},
                onChange = { _, _ -> },
                onPreview = {},
                onBackToEdit = {},
                onConfirm = {},
                onCheckResult = {},
                onFinish = {},
            )
        }
    }
}
