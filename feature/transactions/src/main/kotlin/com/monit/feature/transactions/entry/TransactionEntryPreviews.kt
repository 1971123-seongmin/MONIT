package com.monit.feature.transactions.entry

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.TransactionKind

@Preview(name = "지출", widthDp = 360, heightDp = 708)
@Preview(name = "지출 어두운 테마", widthDp = 360, heightDp = 708, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ExpenseEntryPreview() {
    EntryPreview(
        TransactionEntryState(
            TransactionEntryUiState(
                amount = "20000",
                date = "2026-11-03",
                memo = "합성 점심",
            ),
            EntryPhase.EDITING,
        ),
    )
}

@Preview(name = "이체", widthDp = 360, heightDp = 708)
@Preview(name = "이체 큰 글꼴", widthDp = 320, heightDp = 708, fontScale = 2f)
@Composable
private fun TransferEntryPreview() {
    EntryPreview(
        TransactionEntryState(
            TransactionEntryUiState(
                kind = TransactionKind.TRANSFER,
                amount = "20000",
                date = "2026-11-03",
            ),
            EntryPhase.EDITING,
        ),
    )
}

@Preview(name = "저장 완료", widthDp = 360, heightDp = 708)
@Composable
private fun SavedEntryPreview() {
    EntryPreview(
        TransactionEntryState(
            TransactionEntryUiState(
                amount = "20000",
                date = "2026-11-03",
                memo = "합성 점심",
            ),
            EntryPhase.SAVED,
        ),
    )
}

@Composable
private fun EntryPreview(state: TransactionEntryState) {
    MonitTheme {
        Surface(
            color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface,
        ) {
            TransactionEntryScreen(
                state,
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
                {},
            )
        }
    }
}
