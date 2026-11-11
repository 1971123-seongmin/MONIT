package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.component.button.MonitButtonContent
import com.monit.core.designsystem.component.calendar.MonitDateCell
import com.monit.core.designsystem.theme.MonitTheme
import java.time.LocalDate

internal enum class CatalogSection { Buttons, Amounts, Accounts, Records }

/** 합성 자료만 쓰는 상태 카탈로그. 제품 화면·저장 성공·탐색 경로를 구현하지 않는다. */
@Composable
internal fun MonitComponentCatalog(
    section: CatalogSection,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.section),
        ) {
            when (section) {
                CatalogSection.Buttons -> {
                    Text(
                        "버튼 상태",
                        style = MaterialTheme.typography.titleLarge,
                    )

                    MonitButtonContent(
                        "계속하기",
                        {},
                        Modifier.fillMaxWidth(),
                    )

                    MonitButtonContent(
                        "계속하기",
                        {},
                        Modifier.fillMaxWidth(),
                        pressed = true,
                    )

                    MonitButtonContent(
                        "계속하기",
                        {},
                        Modifier.fillMaxWidth(),
                        focused = true,
                    )

                    MonitButtonContent(
                        "계속하기",
                        {},
                        Modifier.fillMaxWidth(),
                        enabled = false,
                    )

                    MonitButtonContent(
                        "계속하기",
                        {},
                        Modifier.fillMaxWidth(),
                        loading = true,
                    )
                }

                CatalogSection.Amounts -> {
                    Text(
                        "금액 입력",
                        style = MaterialTheme.typography.titleLarge,
                    )

                    AmountFieldContentSample(
                        "",
                        {},
                        supportingText = "금액을 입력해주세요.",
                    )

                    AmountFieldContentSample(
                        "30000",
                        {},
                    )

                    AmountFieldContentSample(
                        "30000",
                        {},
                        focused = true,
                    )

                    AmountFieldContentSample(
                        "0",
                        {},
                        errorMessage = "0원보다 큰 금액을 입력하세요.",
                    )

                    AmountFieldContentSample(
                        "-",
                        {},
                        label = "기준 잔액",
                        supportingText = "편집 중인 문자열",
                    )

                    AmountFieldContentSample(
                        "30000",
                        {},
                        loading = true,
                    )
                }

                CatalogSection.Accounts -> {
                    Text(
                        "계좌 선택",
                        style = MaterialTheme.typography.titleLarge,
                    )

                    val accounts = listOf(
                        AccountOptionSample(
                            "synthetic-1",
                            "생활비",
                            "일반",
                            "기준일 2026.11.02",
                            null,
                        ),
                        AccountOptionSample(
                            "synthetic-2",
                            "생활비",
                            "저축",
                            "기준일 2026.10.31",
                            "0원",
                        ),
                    )

                    AccountPickerSample(
                        accounts,
                        "synthetic-1",
                        {},
                        {},
                    )

                    AccountPickerSample(
                        emptyList(),
                        null,
                        {},
                        {},
                    )

                    AccountPickerSample(
                        emptyList(),
                        null,
                        {},
                        {},
                        errorMessage = "계좌를 불러오지 못했어요.",
                        onRetry = {},
                    )
                }

                CatalogSection.Records -> {
                    Text(
                        "거래 행 · 날짜 셀",
                        style = MaterialTheme.typography.titleLarge,
                    )

                    TransactionRowSample(
                        "수입",
                        "합성 예시",
                        "+20,000원",
                        "일반 계좌 · 기타 수입",
                    )

                    TransactionRowSample(
                        "지출",
                        "아주 긴 합성 거래 제목도 생략하지 않고 표시합니다",
                        "-999,999,999,999원",
                        "긴 계좌 이름도 다음 줄로 이어서 표시하는 합성 예시",
                    )

                    MonitDateCell(
                        LocalDate.of(
                            2026,
                            11,
                            2,
                        ),
                        {},
                    )

                    MonitDateCell(
                        LocalDate.of(
                            2026,
                            11,
                            3,
                        ),
                        {},
                        today = true,
                    )

                    MonitDateCell(
                        LocalDate.of(
                            2026,
                            11,
                            3,
                        ),
                        {},
                        selected = true,
                        summary = "수입 3만 · 지출 6만 · 이체 1건",
                        accessibilitySummary = "수입 30,000원, 지출 60,000원, 이체 1건",
                    )
                }
            }
        }
    }
}

@Preview(name = "Light · 버튼", widthDp = 390, showBackground = true)
@Composable
private fun LightButtons() = MonitTheme(false) {
    MonitComponentCatalog(CatalogSection.Buttons)
}

@Preview(name = "Dark · 버튼", widthDp = 390, showBackground = true)
@Composable
private fun DarkButtons() = MonitTheme(true) {
    MonitComponentCatalog(CatalogSection.Buttons)
}

@Preview(name = "Light · 금액", widthDp = 390, showBackground = true)
@Composable
private fun LightAmounts() = MonitTheme(false) {
    MonitComponentCatalog(CatalogSection.Amounts)
}

@Preview(name = "Dark · 금액", widthDp = 390, showBackground = true)
@Composable
private fun DarkAmounts() = MonitTheme(true) {
    MonitComponentCatalog(CatalogSection.Amounts)
}

@Preview(name = "Light · 계좌", widthDp = 390, showBackground = true)
@Composable
private fun LightAccounts() = MonitTheme(false) {
    MonitComponentCatalog(CatalogSection.Accounts)
}

@Preview(name = "Dark · 계좌", widthDp = 390, showBackground = true)
@Composable
private fun DarkAccounts() = MonitTheme(true) {
    MonitComponentCatalog(CatalogSection.Accounts)
}

@Preview(name = "Light · 행/날짜", widthDp = 390, showBackground = true)
@Composable
private fun LightRecords() = MonitTheme(false) {
    MonitComponentCatalog(CatalogSection.Records)
}

@Preview(name = "Dark · 행/날짜", widthDp = 390, showBackground = true)
@Composable
private fun DarkRecords() = MonitTheme(true) {
    MonitComponentCatalog(CatalogSection.Records)
}

@Preview(name = "Light · 320dp/200%", widthDp = 320, fontScale = 2f, showBackground = true)
@Composable
private fun LargeLight() = MonitTheme(false) {
    MonitComponentCatalog(CatalogSection.Records)
}

@Preview(name = "Dark · 320dp/200%", widthDp = 320, fontScale = 2f, showBackground = true)
@Composable
private fun LargeDark() = MonitTheme(true) {
    MonitComponentCatalog(CatalogSection.Records)
}
