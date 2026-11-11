package com.monit.feature.accounts.list

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme

/** 고정 날짜/빈 UI 자료만 사용하며 ViewModel·DB·네트워크 없이 화면 조합을 검토한다. */
@Preview(name = "Accounts / Empty / Light", widthDp = 360, heightDp = 708)
@Preview(
    name = "Accounts / Empty / Dark",
    widthDp = 360,
    heightDp = 708,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(name = "Accounts / 320dp / 200%", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
internal fun AccountsEmptyPreview() {
    MonitTheme {
        Surface(
            Modifier.fillMaxSize(), color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface
        ) {
            AccountsScreen(
                AccountsState.Ready(AccountsUiState("등록된 계좌가 없어요", emptyList())),
                onRetry = {})
        }
    }
}
