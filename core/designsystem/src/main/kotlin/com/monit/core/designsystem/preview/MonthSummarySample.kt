package com.monit.core.designsystem.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.monit.core.designsystem.R
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.theme.MonitTheme

/** 합계는 계산하지 않는다. 정상 조회한 빈 월의 0원과 조회 실패는 호출자가 구분한다. */
@Composable
internal fun MonthSummarySample(
    title: String,
    incomeText: String,
    expenseText: String,
    modifier: Modifier = Modifier,
) {
    MonitCard(
        modifier,
        color = MonitTheme.colors.surface,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
        )

        Text(
            incomeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MonitTheme.colors.primary,
        )

        Text(
            expenseText,
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            stringResource(R.string.monit_transfer_excluded),
            style = MaterialTheme.typography.labelSmall,
            color = MonitTheme.colors.muted,
        )
    }
}
