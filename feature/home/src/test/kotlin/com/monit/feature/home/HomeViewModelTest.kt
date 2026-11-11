package com.monit.feature.home

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val contexts = object : CoroutineContextProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }
    private val clock = Clock.fixed(Instant.parse("2026-11-10T16:00:00Z"), ZoneOffset.UTC)
    private val context = LedgerContext(id(1), id(2))
    private val store = ViewModelStore()
    private fun viewModel(repo: HomeRepository) =
        HomeViewModel(repo, clock, contexts).also { store.put("home", it) }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)
    @After
    fun tearDown() {
        store.clear(); Dispatchers.resetMain()
    }

    /**
     * 목적: 조회 성공 뒤에만 월 요약을 표시하며 실패를 0원으로 바꾸지 않는다.
     * 조건: 응답 gate·고정 UTC 시각과 읽기 전용 Repository를 사용한다.
     * 절차: 대기 → 성공 → 명시 재조회 실패 → 정상 빈 결과의 재시도 완료 순서다.
     * 기대: 서울 오늘 조회, Loading/Ready/Failed 구분, 정상 빈 성공 뒤에만 0원 표시.
     */
    @Test
    fun summaryWaitsForReadAndFailureIsNotZero() = runTest(dispatcher) {
        val gate = CompletableDeferred<LedgerSnapshot>()
        val repo = HomeRepository().apply { onSnapshot = { gate.await() } }
        val vm = viewModel(repo)
        vm.refresh(context)
        runCurrent()
        assertEquals(HomeState.Loading, vm.state.value)
        assertEquals(listOf(LocalDate.of(2026, 11, 11)), repo.dates)
        gate.complete(snapshot(30_000))
        advanceUntilIdle()
        val ui = assertIs<HomeState.Ready>(vm.state.value).ui
        assertEquals("2026년 11월 전체", ui.month)
        assertEquals("30,000원", ui.income)
        repo.onSnapshot = { throw IOException("synthetic") }
        vm.refresh(context)
        advanceUntilIdle()
        assertEquals(HomeState.Failed, vm.state.value)
        repo.onSnapshot = { snapshot(0) }
        vm.refresh(context)
        advanceUntilIdle()
        assertEquals("0원", assertIs<HomeState.Ready>(vm.state.value).ui.income)
    }

    /**
     * 목적: 취소에 늦게 반응하는 이전 조회가 최신 결과를 덮어쓰지 않는다.
     * 조건: 첫 응답은 NonCancellable gate, 두 번째는 42원의 정상 결과다.
     * 절차: 첫 조회 시작 → 새 조회 완료 → 첫 응답 99원 해제 → 모든 작업 완료 순서다.
     * 기대: 최신 42원 유지, 금융 쓰기 호출 없음.
     */
    @Test
    fun lateReadCannotReplaceLatestSummary() = runTest(dispatcher) {
        val old = CompletableDeferred<LedgerSnapshot>()
        val repo = HomeRepository().apply {
            onSnapshot =
                { if (dates.size == 1) withContext(NonCancellable) { old.await() } else snapshot(42) }
        }
        val vm = viewModel(repo)
        vm.refresh(context)
        runCurrent()
        vm.refresh(context)
        advanceUntilIdle()
        assertEquals("42원", assertIs<HomeState.Ready>(vm.state.value).ui.income)
        old.complete(snapshot(99))
        advanceUntilIdle()
        assertEquals("42원", assertIs<HomeState.Ready>(vm.state.value).ui.income)
    }

    /**
     * 목적: entry의 VM 제거가 진행 중 조회를 취소하고 오류로 바꾸지 않는다.
     * 조건: Repository가 awaitCancellation으로 대기하고 finally에서 종료를 알린다.
     * 절차: 조회 진입 확인 → ViewModelStore clear → 취소 완료를 기다린다.
     * 기대: 조회 종료·실패 UI 없음. 공유 scope로 작업이 유출되지 않는다.
     */
    @Test
    fun clearingViewModelCancelsReadWithoutFailureState() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val repo = HomeRepository().apply {
            onSnapshot = {
                try {
                    entered.complete(Unit); awaitCancellation()
                } finally {
                    exited.complete(Unit)
                }
            }
        }
        val vm = viewModel(repo)
        vm.refresh(context)
        runCurrent()
        assertTrue(entered.isCompleted)
        store.clear()
        runCurrent()
        assertTrue(exited.isCompleted)
        assertEquals(HomeState.Loading, vm.state.value)
    }
}

private class HomeRepository : LedgerRepository {
    val dates = mutableListOf<LocalDate>()
    var onSnapshot: suspend () -> LedgerSnapshot = { snapshot(0) }
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        dates += asOf
        return onSnapshot()
    }

    override suspend fun openLedger(): LedgerEntryResult = error("장부 진입은 app의 책임이다")
    override suspend fun initialize(context: LedgerContext): Unit = error("홈 조회에서 초기화하지 않는다")
    override suspend fun createAccount(
        context: LedgerContext,
        operationId: String,
        value: CreateAccount
    ): WriteReceipt = error("읽기 전용")

    override suspend fun createTransaction(
        context: LedgerContext,
        operationId: String,
        value: CreateTransaction
    ): WriteReceipt = error("읽기 전용")

    override suspend fun receipt(context: LedgerContext, operationId: String): WriteReceipt? =
        error("후속 복구 범위")

    override suspend fun pendingWrites(context: LedgerContext): List<PendingWrite> =
        error("후속 복구 범위")

    override suspend fun pendingRecovery(context: LedgerContext): PendingRecoverySnapshot =
        error("후속 복구 범위")

    override suspend fun retryPending(context: LedgerContext, operationId: String): WriteReceipt =
        error("읽기 전용")
}

private fun id(number: Int) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

private fun snapshot(income: Long): LedgerSnapshot {
    val account = LedgerAccount(
        id(11), "합성 계좌", AccountNature.GENERAL, AccountStatus.ACTIVE, 1,
        BalanceAnchor(id(12), LocalDate.of(2026, 1, 1), null, null, "미확인")
    )
    val rows = if (income == 0L) emptyList() else listOf(
        LedgerTransaction(
            id(21),
            TransactionKind.INCOME,
            LocalDate.of(2026, 11, 11),
            income,
            null,
            account.id,
            null,
            AccountNature.GENERAL,
            "합성 수입",
            1,
            1
        )
    )
    return LedgerSnapshot(1, listOf(account), rows, mapOf(account.id to null), income, 0)
}
