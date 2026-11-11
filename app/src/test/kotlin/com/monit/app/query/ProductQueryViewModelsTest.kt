package com.monit.app.query

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.monit.app.LedgerEntryState
import com.monit.app.LedgerEntryViewModel
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import com.monit.feature.accounts.list.AccountsState
import com.monit.feature.accounts.list.AccountsViewModel
import com.monit.feature.transactions.TransactionsLoadState
import com.monit.feature.transactions.TransactionsViewModel
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProductQueryViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-10T16:00:00Z"), ZoneOffset.UTC)
    private val store = ViewModelStore()
    private var nextKey = 0
    private fun <T : ViewModel> own(vm: T) = vm.also { store.put("query-" + nextKey++, it) }
    private fun transactions(repo: QueryRepository, state: SavedStateHandle = SavedStateHandle()) = own(TransactionsViewModel(repo, clock, state, contexts))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    /**
     * 목적: 장부 확인 중 중복 open을 막고 복구 필요를 신규 빈 장부로 바꾸지 않는다.
     * 조건: 실제 VM과 완료를 제어한 읽기 전용 Repository다.
     * 절차: gate 대기 중 retry → 복구 필요 응답 → 명시 재확인으로 Ready 순서다.
     * 기대: 중복 open 없음, 원인 보존, Ready 뒤 재확인으로 탐색 진입을 초기화하지 않는다.
     */
    @Test
    fun entryGatePreservesRecoveryAndPreventsDuplicateOpen() = runTest(dispatcher) {
        val gate = CompletableDeferred<LedgerEntryResult>()
        val repo = QueryRepository().apply { onOpen = { gate.await() } }
        val vm = own(LedgerEntryViewModel(repo))
        runCurrent()
        vm.retry()
        assertEquals(1, repo.openCalls)
        assertEquals(LedgerEntryState.Loading, vm.state.value)
        gate.complete(LedgerEntryResult.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA))
        advanceUntilIdle()
        assertEquals(
            LedgerEntryState.RecoveryRequired(LedgerEntryProblem.MISSING_METADATA),
            vm.state.value,
        )
        repo.onOpen = { LedgerEntryResult.Ready(testContext) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(LedgerEntryState.Ready(testContext), vm.state.value)
        vm.retry()
        assertEquals(2, repo.openCalls)
    }

    /** 장부 조회 실패는 제품 Ready/정상 빈 화면이 아니며 명시 재확인으로만 다시 연다. */
    @Test
    fun entryFailureCanBeRetriedWithoutWrite() = runTest(dispatcher) {
        val repo = QueryRepository().apply { onOpen = { throw IOException("synthetic") } }
        val vm = own(LedgerEntryViewModel(repo))
        advanceUntilIdle()
        assertEquals(LedgerEntryState.Failed, vm.state.value)
        repo.onOpen = { LedgerEntryResult.Ready(testContext) }
        vm.retry()
        advanceUntilIdle()
        assertIs<LedgerEntryState.Ready>(vm.state.value)
    }

    /** UTC 전날이어도 서울 오늘·현재 월이 최초 선택이며 미래 월의 조회 기준일은 오늘을 넘지 않는다. */
    @Test
    fun initialSelectionUsesSeoulTodayAndFutureMonthRemainsHonestEmpty() = runTest(dispatcher) {
        val repo = QueryRepository()
        val vm = transactions(repo)
        assertEquals(LocalDate.of(2026, 11, 11), vm.state.value.ui.selectedDate)
        vm.refresh(testContext)
        advanceUntilIdle()
        assertEquals(TransactionsLoadState.READY, vm.state.value.load)
        assertTrue(vm.state.value.ui.rows.isEmpty())
        vm.changeMonth(YearMonth.of(2027, 1))
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 11), repo.snapshotDates.last())
        assertEquals("0원", vm.state.value.ui.income)
        assertEquals(31, vm.state.value.ui.dates.size)
    }

    /** 월말·윤년·연도 경계에서 선택 일을 말일로 보정하고 오늘 행동은 서울 오늘로 복귀한다. */
    @Test
    fun monthBoundariesClampAndTodayResetsSelection() = runTest(dispatcher) {
        val vm = transactions(QueryRepository())
        vm.refresh(testContext)
        vm.selectDate(LocalDate.of(2027, 1, 31))
        vm.changeMonth(YearMonth.of(2027, 2))
        assertEquals(LocalDate.of(2027, 2, 28), vm.state.value.ui.selectedDate)
        vm.selectDate(LocalDate.of(2028, 1, 31))
        vm.changeMonth(YearMonth.of(2028, 2))
        assertEquals(LocalDate.of(2028, 2, 29), vm.state.value.ui.selectedDate)
        vm.changeMonth(YearMonth.of(2028, 12))
        vm.changeMonth(YearMonth.of(2029, 1))
        assertEquals(LocalDate.of(2029, 1, 29), vm.state.value.ui.selectedDate)
        vm.goToToday()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 11), vm.state.value.ui.selectedDate)
    }

    /**
     * 목적: 달력/목록·종류/계좌 필터는 같은 원본을 쓰며 월 전체 합계는 변하지 않는다.
     * 조건: 수입 30,000원, 지출 20,000원과 전날 5,000원, 이체 90,000원의 합성 장부다.
     * 절차: 초기 선택일 → 목록 → 지출 → 입금 계좌 필터 → 전체 계좌 순으로 projection 완료를 기다린다.
     * 기대: 이체 제외 수입/지출, 필터 일치, 동일 월 전환의 추가 DB 조회 없음.
     */
    @Test
    fun filteringAndViewChangesReuseSnapshotAndKeepWholeMonthTotals() = runTest(dispatcher) {
        val repo = seededRepository()
        val vm = transactions(repo)
        vm.refresh(testContext)
        advanceUntilIdle()
        assertEquals("30,000원", vm.state.value.ui.income)
        assertEquals("25,000원", vm.state.value.ui.expense)
        assertEquals(3, vm.state.value.ui.rows.size)
        val day = vm.state.value.ui.dates.single { it.date.dayOfMonth == 11 }
        assertTrue(day.spokenSummary.contains("이체 1건"))
        assertTrue(day.spokenSummary.contains("수입 30,000원"))
        vm.selectCalendar(false)
        advanceUntilIdle()
        assertEquals(4, vm.state.value.ui.rows.size)
        vm.selectKind(1)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.ui.rows.size)
        vm.selectAccount(queryId(12))
        advanceUntilIdle()
        assertTrue(vm.state.value.ui.rows.isEmpty())
        vm.selectAccount(null)
        advanceUntilIdle()
        assertEquals("25,000원", vm.state.value.ui.expense)
        assertEquals(1, repo.snapshotDates.size)
    }

    /** primitive 선택값을 새 VM에서 복원하며 보기 전환/재조회가 날짜·필터를 초기화하지 않는다. */
    @Test
    fun savedSelectionRestoresInNewViewModel() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = transactions(seededRepository(), saved)
        vm.refresh(testContext)
        advanceUntilIdle()
        vm.selectDate(LocalDate.of(2026, 10, 8))
        vm.selectCalendar(false)
        vm.selectKind(3)
        vm.selectAccount(queryId(12))
        advanceUntilIdle()
        val restored = transactions(seededRepository(), saved)
        restored.refresh(testContext)
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 8), restored.state.value.ui.selectedDate)
        assertFalse(restored.state.value.ui.calendar)
        assertEquals(3, restored.state.value.ui.selectedKindIndex)
        assertEquals(queryId(12), restored.state.value.ui.selectedAccountId)
    }

    /**
     * 목적: 취소에 늦게 반응하는 이전 월 조회가 새 월의 Ready를 덮어쓰지 않는다.
     * 조건: 첫 응답을 NonCancellable gate로 지연하고 다음 월은 즉시 정상 빈 결과다.
     * 절차: 첫 조회 시작 → 월 이동·완료 → 이전 gate 해제 → 남은 작업 완료 순서다.
     * 기대: 새 월/정상 빈 상태 유지. 실패나 기존 월의 99원 요약으로 바뀌지 않는다.
     */
    @Test
    fun latePreviousMonthResponseDoesNotReplaceCurrentMonth() = runTest(dispatcher) {
        val old = CompletableDeferred<LedgerSnapshot>()
        val repo = QueryRepository().apply {
            onSnapshot = {
                if (snapshotDates.size == 1) withContext(NonCancellable) { old.await() }
                else LedgerSnapshot(1, emptyList(), emptyList(), emptyMap(), 0, 0)
            }
        }
        val vm = transactions(repo)
        vm.refresh(testContext)
        runCurrent()
        vm.changeMonth(YearMonth.of(2026, 10))
        advanceUntilIdle()
        assertEquals(TransactionsLoadState.READY, vm.state.value.load)
        old.complete(LedgerSnapshot(1, emptyList(), emptyList(), emptyMap(), 99, 99))
        advanceUntilIdle()
        assertEquals(YearMonth.of(2026, 10), vm.state.value.ui.month)
        assertEquals("0원", vm.state.value.ui.income)
        assertEquals(TransactionsLoadState.READY, vm.state.value.load)
    }

    /** 실패 상태는 정상 빈 결과와 구별되며 재시도 성공 뒤에만 0원/빈 목록을 표시한다. */
    @Test
    fun failedSnapshotRemainsFailureUntilExplicitRetrySucceeds() = runTest(dispatcher) {
        val repo = QueryRepository().apply { onSnapshot = { throw IOException("synthetic") } }
        val vm = transactions(repo)
        vm.refresh(testContext)
        advanceUntilIdle()
        assertEquals(TransactionsLoadState.FAILED, vm.state.value.load)
        assertEquals("", vm.state.value.ui.income)
        repo.onSnapshot = { LedgerSnapshot(1, emptyList(), emptyList(), emptyMap(), 0, 0) }
        vm.refresh()
        advanceUntilIdle()
        assertEquals(TransactionsLoadState.READY, vm.state.value.load)
        assertEquals("0원", vm.state.value.ui.income)
    }

    /** 잔액 미확인과 확인한 0원을 다르게 표시하며 보관 계좌는 현재 합계에서 제외한다. */
    @Test
    fun accountsDistinguishUnknownAndZeroWithoutCountingArchived() = runTest(dispatcher) {
        val repo = QueryRepository().apply {
            onSnapshot = {
                LedgerSnapshot(
                    1,
                    listOf(account(11), account(12), account(13, AccountStatus.ARCHIVED)),
                    emptyList(),
                    mapOf(queryId(11) to null, queryId(12) to 0L, queryId(13) to 999L),
                    0,
                    0,
                )
            }
        }
        val vm = own(AccountsViewModel(repo, clock, contexts))
        vm.refresh(testContext)
        advanceUntilIdle()
        val ui = assertIs<AccountsState.Ready>(vm.state.value).ui
        assertEquals("잔액 미확인", ui.accounts[0].balance)
        assertEquals("0원", ui.accounts[1].balance)
        assertEquals("확인된 잔액 합계 0원 · 잔액 미확인 1개 제외", ui.balanceSummary)
        assertTrue(ui.accounts[2].description.startsWith("보관된 계좌"))
    }

    private fun account(id: Int, status: AccountStatus = AccountStatus.ACTIVE) = LedgerAccount(
        queryId(id),
        "합성 계좌 $id",
        AccountNature.GENERAL,
        status,
        1,
        BalanceAnchor(queryId(id + 100), LocalDate.of(2026, 1, 1), null, null, "미확인"),
    )

    private fun seededRepository(): QueryRepository = QueryRepository().apply {
        val transactions = listOf(
            LedgerTransaction(
                queryId(21),
                TransactionKind.INCOME,
                LocalDate.of(2026, 11, 11),
                30_000,
                null,
                queryId(11),
                null,
                AccountNature.GENERAL,
                "합성 수입",
                1,
                1,
            ),
            LedgerTransaction(
                queryId(22),
                TransactionKind.EXPENSE,
                LocalDate.of(2026, 11, 11),
                20_000,
                queryId(11),
                null,
                AccountNature.GENERAL,
                null,
                "",
                1,
                1,
            ),
            LedgerTransaction(
                queryId(23),
                TransactionKind.TRANSFER,
                LocalDate.of(2026, 11, 11),
                90_000,
                queryId(11),
                queryId(12),
                AccountNature.GENERAL,
                AccountNature.GENERAL,
                "",
                1,
                1,
            ),
            LedgerTransaction(
                queryId(24),
                TransactionKind.EXPENSE,
                LocalDate.of(2026, 11, 10),
                5_000,
                queryId(11),
                null,
                AccountNature.GENERAL,
                null,
                "",
                1,
                1,
            ),
        )
        onSnapshot = { asOf ->
            val rows = transactions.filter { it.date <= asOf }
            val monthly = rows.filter { YearMonth.from(it.date) == YearMonth.from(asOf) }
            LedgerSnapshot(
                1,
                listOf(account(11), account(12)),
                rows,
                emptyMap(),
                monthly.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amount },
                monthly.filter { it.kind == TransactionKind.EXPENSE }.sumOf { it.amount },
            )
        }
    }
}
