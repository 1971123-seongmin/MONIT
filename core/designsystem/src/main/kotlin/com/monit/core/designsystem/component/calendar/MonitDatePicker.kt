package com.monit.core.designsystem.component.calendar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.monit.core.designsystem.theme.MonitTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 선택·현재 월·오늘·선택 가능 정책을 소유자가 제공한다. 320dp/확대에서는 터치를 줄이지 않고 스크롤한다. */
@Composable
fun MonitDatePicker(
    displayedMonth: YearMonth,
    selectedDate: LocalDate?,
    today: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    selectableDate: (LocalDate) -> Boolean = { true },
) {
    val cellSize = MonitTheme.sizes.touchTarget * maxOf(
        1f,
        LocalDensity.current.fontScale,
    )
    val firstOffset = displayedMonth.atDay(1).dayOfWeek.value % 7
    val rows = (firstOffset + displayedMonth.lengthOfMonth() + 6) / 7

    Surface(
        modifier,
        color = MonitTheme.colors.surfaceContainer,
        contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            MonitCalendarHeader(
                displayedMonth.format(
                    DateTimeFormatter.ofPattern(
                        "yyyy년 M월",
                        Locale.KOREAN,
                    ),
                ),
                { onMonthChange(displayedMonth.minusMonths(1)) },
                { onMonthChange(displayedMonth.plusMonths(1)) },
            )

            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(cellSize * 7),
                verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
            ) {
                Row {
                    repeat(7) { day ->
                        Text(
                            DayOfWeek.SUNDAY.plus(day.toLong())
                                .getDisplayName(
                                    TextStyle.SHORT,
                                    Locale.KOREAN,
                                ),
                            Modifier.width(cellSize),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MonitTheme.colors.muted,
                        )
                    }
                }

                repeat(rows) { week ->
                    Row {
                        repeat(7) { weekday ->
                            val day = week * 7 + weekday - firstOffset + 1
                            val date =
                                if (day in 1..displayedMonth.lengthOfMonth()) displayedMonth.atDay(
                                    day,
                                ) else null

                            MonitDateCell(
                                date,
                                { date?.let(onDateSelected) },
                                Modifier.width(cellSize),
                                selected = date != null && date == selectedDate,
                                today = date == today,
                                enabled = date != null && selectableDate(date),
                                future = date != null && date.isAfter(today),
                            )
                        }
                    }
                }
            }

            errorMessage?.let {
                Text(
                    it,
                    Modifier.semantics { error(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MonitTheme.colors.error,
                )
            }
        }
    }
}
