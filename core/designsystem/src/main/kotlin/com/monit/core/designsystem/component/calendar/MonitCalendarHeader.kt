package com.monit.core.designsystem.component.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 월 이동 정책과 응답 순서는 소유자가 관리한다. RTL에서도 이전/다음 행동 의미는 유지한다. */
@Composable
fun MonitCalendarHeader(
    monthLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
) {
    Surface(
        modifier,
        color = MonitTheme.colors.surfaceContainer,
        contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            IconButton(
                onPrevious,
                Modifier.size(MonitTheme.sizes.touchTarget),
                enabled = previousEnabled,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.monit_previous_month),
                    Modifier.size(24.dp),
                )
            }

            Text(
                monthLabel,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )

            IconButton(
                onNext,
                Modifier.size(MonitTheme.sizes.touchTarget),
                enabled = nextEnabled,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    stringResource(R.string.monit_next_month),
                    Modifier.size(24.dp),
                )
            }
        }
    }
}
