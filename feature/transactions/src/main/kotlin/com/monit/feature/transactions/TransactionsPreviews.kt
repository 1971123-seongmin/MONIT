package com.monit.feature.transactions

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme
import java.time.LocalDate
import java.time.YearMonth

/** 고정 날짜/빈 UI 자료만 사용하며 ViewModel·DB·네트워크 없이 화면 조합을 검토한다. */
@Preview(name = "Transactions / Empty / Light", widthDp = 360, heightDp = 708)
@Preview(
    name = "Transactions / Empty / Dark",
    widthDp = 360,
    heightDp = 708,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(name = "Transactions / 320dp / 200%", widthDp = 320, heightDp = 480, fontScale = 2f)
@Composable
internal fun TransactionsEmptyPreview() {
    MonitTheme {
        Surface(
            Modifier.fillMaxSize(), color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface
        ) {
            TransactionsScreen(
                TransactionsState(
                    TransactionsUiState(
                        YearMonth.of(2026, 11),
                        LocalDate.of(2026, 11, 11),
                        LocalDate.of(2026, 11, 11),
                        income = "0원",
                        expense = "0원",
                        dates = (1..30).map {
                            TransactionDateUiState(
                                LocalDate.of(2026, 11, it),
                                "",
                                ""
                            )
                        },
                    ),
                    TransactionsLoadState.READY,
                ),
                {}, {}, {}, {}, {}, {}, {},
            )
        }
    }
}
