package com.monit.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.component.MonitAccountCard
import com.monit.core.designsystem.component.MonitAccountOption
import com.monit.core.designsystem.component.MonitAccountSheetContent
import com.monit.core.designsystem.component.MonitAmountField
import com.monit.core.designsystem.component.MonitBadge
import com.monit.core.designsystem.component.MonitBadgeTone
import com.monit.core.designsystem.component.MonitBottomNavigation
import com.monit.core.designsystem.component.MonitButton
import com.monit.core.designsystem.component.MonitCalendarHeader
import com.monit.core.designsystem.component.MonitDateCell
import com.monit.core.designsystem.component.MonitDatePicker
import com.monit.core.designsystem.component.MonitDialogContent
import com.monit.core.designsystem.component.MonitFilterChip
import com.monit.core.designsystem.component.MonitMonthSummary
import com.monit.core.designsystem.component.MonitNavigationAction
import com.monit.core.designsystem.component.MonitPaymentStatusRow
import com.monit.core.designsystem.component.MonitSegmentedControl
import com.monit.core.designsystem.component.MonitSkeleton
import com.monit.core.designsystem.component.MonitSnackbar
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.component.MonitTransactionRow
import com.monit.core.designsystem.theme.MonitTheme
import java.time.LocalDate
import java.time.YearMonth

/** Figma 19 family를 빠짐없이 선택할 수 있는 합성 렌더 입력. 제품 화면이 아니다. */
internal enum class ComponentFamily {
    ActionButton, Field, AccountCard, TransactionRow, CalendarCell, Badge, FilterChip, TopBar,
    SegmentedControl, StatusPanel, MonthSummary, BottomNavigation, BottomSheet, Dialog, Snackbar,
    Skeleton, CalendarHeader, PaymentStatusRow, DatePicker,
}

@Preview(name = "Light 360", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark 360", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Light 320 200%", widthDp = 320, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark 320 200%", widthDp = 320, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class FamilyPreview

@Composable
internal fun MonitFamilyCatalog(family: ComponentFamily, modifier: Modifier = Modifier) {
    val today = LocalDate.of(2026, 11, 3)
    val full = Modifier.fillMaxWidth()
    Surface(modifier.fillMaxWidth(), color = MonitTheme.colors.surface) {
        val horizontalPadding = if (family == ComponentFamily.DatePicker || family == ComponentFamily.CalendarHeader) 12.dp else MonitTheme.spacing.content
        Column(Modifier.padding(horizontal = horizontalPadding, vertical = MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.section)) {
            when (family) {
                ComponentFamily.ActionButton -> {
                    val pressed = remember { MutableInteractionSource() }
                    val focused = remember { MutableInteractionSource() }
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        pressed.emit(PressInteraction.Press(Offset.Zero))
                        focused.emit(FocusInteraction.Focus())
                    }
                    MonitButton("계속하기", {}, full)
                    MonitButton("계속하기", {}, full, interactionSource = pressed)
                    MonitButton("계속하기", {}, full, interactionSource = focused)
                    MonitButton("계속하기", {}, full, enabled = false)
                    MonitButton("계속하기", {}, full, loading = true)
                }
                ComponentFamily.Field -> {
                    val focused = remember { MutableInteractionSource() }
                    LaunchedEffect(Unit) { withFrameNanos { }; focused.emit(FocusInteraction.Focus()) }
                    MonitAmountField("30,000", {})
                    MonitAmountField("30,000", {}, interactionSource = focused)
                    MonitAmountField("0", {}, errorMessage = "0원보다 큰 금액을 입력해주세요.")
                    MonitAmountField("30,000", {}, enabled = false)
                }
                ComponentFamily.AccountCard -> {
                    MonitAccountCard("생활비 · 일반", null, "현재 잔액 합계에서 제외", false, {})
                    MonitAccountCard("생활비 · 일반", null, "선택됨 · 현재 잔액 합계에서 제외", true, {})
                    MonitAccountCard("생활비 · 일반", null, "", false, {}, disabledReason = "이체의 반대편 계좌로 선택할 수 없어요")
                    MonitAccountCard("생활비 · 일반", "250,000원", "사용자 확인 장부 잔액 · 후속 설계", false, {})
                }
                ComponentFamily.TransactionRow -> {
                    MonitTransactionRow("수입", "중고 판매", "+20,000원", "생활비 · 2026.11.03", full, income = true)
                    MonitTransactionRow("지출", "점심", "−20,000원", "생활비 · 2026.11.03", full)
                    MonitTransactionRow("이체", "저축으로", "20,000원", "생활비 → 저축", full)
                }
                ComponentFamily.CalendarCell -> {
                    MonitDateCell(today.minusDays(1), {})
                    MonitDateCell(today, {}, today = true)
                    MonitDateCell(today, {}, selected = true)
                    MonitDateCell(today.plusDays(1), {}, future = true)
                    MonitDateCell(null, {})
                }
                ComponentFamily.Badge -> MonitBadgeTone.entries.forEachIndexed { i, tone ->
                    MonitBadge(listOf("미확인", "선택됨", "확인 필요", "미제공")[i], tone = tone)
                }
                ComponentFamily.FilterChip -> {
                    MonitFilterChip("전체 계좌", false, {})
                    MonitFilterChip("생활비 계좌", true, {})
                    MonitFilterChip("선택 불가", false, {}, enabled = false)
                }
                ComponentFamily.TopBar -> {
                    MonitTopBar("거래", full)
                    MonitTopBar("거래 기록", full, MonitNavigationAction.Back)
                    MonitTopBar("계좌 선택", full, MonitNavigationAction.Close)
                }
                ComponentFamily.SegmentedControl -> {
                    repeat(3) { MonitSegmentedControl(listOf("지출", "수입", "이체"), it, {}, full) }
                    repeat(2) { MonitSegmentedControl(listOf("달력", "목록"), it, {}, full) }
                }
                ComponentFamily.StatusPanel -> {
                    val examples = listOf(
                        Triple("아직 기록이 없어요", "첫 거래를 기록해보세요.", "거래 기록"),
                        Triple("기록을 불러오는 중", "잠시만 기다려주세요.", "불러오는 중…"),
                        Triple("불러오지 못했어요", "선택한 날짜와 필터는 유지돼요.", "다시 불러오기"),
                        Triple("저장 중이에요", "같은 기록이 중복되지 않도록 처리해요.", "저장 중…"),
                        Triple("저장 결과 확인 중", "완료 여부를 확인하고 있어요.", "결과 확인 중…"),
                        Triple("결과를 확인하지 못했어요", "새 기록을 만들기 전에 다시 확인해주세요.", "결과 다시 확인"),
                        Triple("기록을 저장했어요", "확인된 거래를 목록에 반영했어요.", "목록 보기"),
                    )
                    examples.forEachIndexed { i, (title, body, action) ->
                        MonitStatusPanel(title, body, action, {}, full, busy = i in listOf(1, 3, 4), error = i == 2)
                    }
                }
                ComponentFamily.MonthSummary -> {
                    MonitMonthSummary("이번 달 전체", "수입 30,000원", "지출 60,000원", full)
                    MonitMonthSummary("이번 달 전체", "수입 0원", "지출 0원", full)
                }
                ComponentFamily.BottomNavigation -> repeat(3) { MonitBottomNavigation(listOf("홈", "거래", "계좌"), it, {}, full) }
                ComponentFamily.BottomSheet -> {
                    MonitAccountSheetContent(listOf(MonitAccountOption("sample", "생활비", "일반", "", null)),
                        "sample", {}, {}, {}, {}, full)
                    MonitAccountSheetContent(emptyList(), null, {}, {}, {}, {}, full)
                }
                ComponentFamily.Dialog -> {
                    val dialogWidth = Modifier.widthIn(max = 304.dp).fillMaxWidth()
                    MonitDialogContent("기록을 그만둘까요?", "저장하지 않은 입력 내용은 사라져요.", "계속 입력", "그만두기", {}, {}, dialogWidth)
                    MonitDialogContent("이 거래를 삭제할까요?", "삭제 기능은 후속 설계예요. 현재 첫 동선에서 제공하지 않아요.",
                        "삭제 · 미제공", "닫기", {}, {}, dialogWidth, primaryEnabled = false)
                }
                ComponentFamily.Snackbar -> {
                    MonitSnackbar("거래 기록을 저장했어요", full)
                    MonitSnackbar("불러오지 못했어요. 다시 시도해주세요.", full)
                }
                ComponentFamily.Skeleton -> { MonitSkeleton(full); MonitSkeleton(full, card = true) }
                ComponentFamily.CalendarHeader -> MonitCalendarHeader("2026년 11월", {}, {}, full)
                ComponentFamily.PaymentStatusRow -> {
                    MonitPaymentStatusRow("보험료", "매월 3일 · 30,000원", "미완료 · 직접 납입 여부를 확인해주세요", full)
                    MonitPaymentStatusRow("보험료", "매월 3일 · 30,000원", "완료 · 2026.11.03 확인", full, completed = true)
                    MonitPaymentStatusRow("보험료", "매월 3일 · 30,000원", "이번 달 건너뜀 · 완료와 별도", full)
                }
                ComponentFamily.DatePicker -> {
                    MonitDatePicker(YearMonth.of(2026, 11), today, today, {}, {}, full)
                    MonitDatePicker(YearMonth.of(2026, 11), today, today, {}, {}, full,
                        errorMessage = "선택 가능한 날짜를 확인해주세요.")
                }
            }
        }
    }
}

@FamilyPreview @Composable internal fun ActionButtonPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.ActionButton) }
@FamilyPreview @Composable internal fun FieldPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.Field) }
@FamilyPreview @Composable internal fun AccountCardPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.AccountCard) }
@FamilyPreview @Composable internal fun TransactionRowPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.TransactionRow) }
@FamilyPreview @Composable internal fun CalendarCellPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.CalendarCell) }
@FamilyPreview @Composable internal fun BadgePreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.Badge) }
@FamilyPreview @Composable internal fun FilterChipPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.FilterChip) }
@FamilyPreview @Composable internal fun TopBarPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.TopBar) }
@FamilyPreview @Composable internal fun SegmentedControlPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.SegmentedControl) }
@FamilyPreview @Composable internal fun StatusPanelPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.StatusPanel) }
@FamilyPreview @Composable internal fun MonthSummaryPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.MonthSummary) }
@FamilyPreview @Composable internal fun BottomNavigationPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.BottomNavigation) }
@FamilyPreview @Composable internal fun BottomSheetPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.BottomSheet) }
@FamilyPreview @Composable internal fun DialogPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.Dialog) }
@FamilyPreview @Composable internal fun SnackbarPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.Snackbar) }
@FamilyPreview @Composable internal fun SkeletonPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.Skeleton) }
@FamilyPreview @Composable internal fun CalendarHeaderPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.CalendarHeader) }
@FamilyPreview @Composable internal fun PaymentStatusRowPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.PaymentStatusRow) }
@FamilyPreview @Composable internal fun DatePickerPreview() = MonitTheme { MonitFamilyCatalog(ComponentFamily.DatePicker) }
