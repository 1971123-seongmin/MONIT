package com.monit.core.designsystem.component.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 날짜 하나가 선택/읽기 대상이다. 미래 날짜도 조회 가능하며 기록 가능 여부는 호출자가 판단한다.
 * null은 이웃 월 빈칸으로 클릭·semantics를 만들지 않는다. 320dp 그리드는 셀을 줄이지 말고 가로 스크롤한다.
 */
@Composable
fun MonitDateCell(
    date: LocalDate?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    today: Boolean = false,
    summary: String = "",
    accessibilitySummary: String = summary,
    enabled: Boolean = true,
    showStateLabel: Boolean = false,
    future: Boolean = false,
) {
    val scale = maxOf(1f, LocalDensity.current.fontScale)
    val minSize = MonitTheme.sizes.touchTarget * scale
    val indicatorSize = MonitTheme.sizes.dateIndicator * scale
    if (date == null) {
        Spacer(modifier.defaultMinSize(minWidth = minSize, minHeight = minSize).clearAndSetSemantics {})
        return
    }
    val colors = MonitTheme.colors
    val todayLabel = stringResource(R.string.monit_today)
    val selectedLabel = stringResource(R.string.monit_selected)
    val spokenDate = date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN))
    val description = listOf(spokenDate, if (today) todayLabel else "", accessibilitySummary)
        .filter { it.isNotBlank() }.joinToString(", ")
    Column(
        modifier.defaultMinSize(minWidth = minSize, minHeight = minSize)
            .selectable(selected, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.compact),
    ) {
        Box(Modifier.sizeIn(minWidth = minSize, minHeight = minSize), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = when { !enabled && selected -> colors.disabledBackground; selected -> colors.primary; else -> Color.Transparent },
                contentColor = when { !enabled -> colors.disabledText; selected -> colors.onPrimary; future -> colors.muted; else -> colors.onSurface },
                border = if ((selected && !enabled) || (today && !selected)) BorderStroke(MonitTheme.sizes.outline, colors.primary) else null,
                modifier = Modifier.clearAndSetSemantics {},
            ) {
                Box(Modifier.sizeIn(minWidth = indicatorSize, minHeight = indicatorSize), contentAlignment = Alignment.Center) {
                    Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (showStateLabel && (today || selected)) Text(
            listOf(if (today) todayLabel else "", if (selected) selectedLabel else "").filter { it.isNotBlank() }.joinToString(" · "),
            modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
        )
        if (summary.isNotBlank()) Text(
            summary, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
        )
    }
}
