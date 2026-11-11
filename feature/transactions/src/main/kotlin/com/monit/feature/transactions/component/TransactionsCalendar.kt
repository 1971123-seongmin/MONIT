package com.monit.feature.transactions.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.monit.core.designsystem.component.calendar.MonitCalendarHeader
import com.monit.core.designsystem.component.calendar.MonitDateCell
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.transactions.TransactionsUiState
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun TransactionsMonthHeader(
    ui: TransactionsUiState,
    onMonth: (YearMonth) -> Unit,
    onToday: () -> Unit,
) {
    Column {
        MonitCalendarHeader(
            "${ui.month.year}년 ${ui.month.monthValue}월",
            { onMonth(ui.month.minusMonths(1)) },
            { onMonth(ui.month.plusMonths(1)) },
            Modifier.fillMaxWidth(),
            previousEnabled = ui.month > YearMonth.of(
                1900,
                1,
            ),
            nextEnabled = ui.month < YearMonth.of(
                9999,
                12,
            ),
        )

        TextButton(onClick = onToday) {
            Text("오늘")
        }
    }
}

/** 유한한 한 달만 조합한다. 7열의 최소 터치 크기를 지키며 좁은 창/큰 글꼴은 가로 스크롤한다. */
@Composable
internal fun TransactionsCalendar(
    ui: TransactionsUiState,
    onDate: (LocalDate) -> Unit,
) {
    val cellSize = MonitTheme.sizes.touchTarget * maxOf(
        1f,
        LocalDensity.current.fontScale,
    )
    val offset = ui.month.atDay(1).dayOfWeek.value % 7
    val weeks = (offset + ui.month.lengthOfMonth() + 6) / 7

    Column {
        Text(
            "＋수입 · −지출 · 이체 건수 / 달력 금액은 요약 표기",
            style = MaterialTheme.typography.labelSmall,
        )

        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .width(cellSize * 7),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            Row {
                listOf(
                    "일",
                    "월",
                    "화",
                    "수",
                    "목",
                    "금",
                    "토",
                ).forEach {
                    Text(
                        it,
                        Modifier.width(cellSize),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MonitTheme.colors.muted,
                    )
                }
            }

            repeat(weeks) { week ->
                Row {
                    repeat(7) { weekday ->
                        val day = week * 7 + weekday - offset + 1
                        val cell = ui.dates.getOrNull(day - 1)

                        MonitDateCell(
                            date = cell?.date,
                            onClick = { cell?.let { onDate(it.date) } },
                            modifier = Modifier.width(cellSize),
                            selected = cell?.date == ui.selectedDate,
                            today = cell?.date == ui.today,
                            future = cell?.date?.isAfter(ui.today) == true,
                            summary = cell?.summary.orEmpty(),
                            accessibilitySummary = cell?.spokenSummary.orEmpty(),
                        )
                    }
                }
            }
        }
    }
}
