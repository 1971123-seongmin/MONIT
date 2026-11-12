package com.monit.feature.accounts

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme

@Preview(name = "계좌 등록", widthDp = 360, heightDp = 708)
@Preview(name = "계좌 등록 어두운 테마", widthDp = 360, heightDp = 708, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AccountRegistrationPreview() {
    MonitTheme {
        Surface(
            color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface,
        ) {
            AccountRegistrationScreen(
                AccountRegistrationState(
                    AccountDraftUiState(
                        name = "합성 생활비",
                        anchorDate = "2026-11-02",
                    ),
                    AccountSaveState.Editing,
                ),
                {},
                {},
                {},
            )
        }
    }
}
