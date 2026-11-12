package com.monit.app.entry

import android.app.Application
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monit.app.query.QueryRepository
import com.monit.app.query.testContext
import com.monit.app.ui.MonitAppShell
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*
import com.monit.feature.accounts.AccountRegistrationRoute
import com.monit.feature.accounts.AccountRegistrationViewModel
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.AccountRegistrationOrigin
import com.monit.feature.transactions.entry.TransactionEntryRoute
import com.monit.feature.transactions.entry.TransactionEntryViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 실제 Route·VM·Nav2를 연결한 합성 Repository UI 시험. Room·기기 쓰기 시험과 구분한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EntryJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val repo = JourneyRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var back: androidx.activity.OnBackPressedDispatcher

    /**
     * 목적: 입력 중 계좌 등록 왕복과 명시 적용, 저장 완료 복귀를 실제 탐색에서 검증한다.
     * 조건: 실제 셸·입력/등록 Route·VM과 합성 장부 대역이다.
     * 절차: 금액/메모 입력 → 계좌 등록 → 등록 확인 → 후보 직접 적용 → 저장 → 목록 복귀.
     * 기대: 원래 입력 보존, 자동 적용 없음, 계좌·거래 각 한 번 저장, 명시 확인된 참조만 종료한다.
     */
    @Test fun registerAccountThenExplicitlyApplyAndSaveTransaction() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("2000")
        compose.onNodeWithContentDescription("메모 (선택)").performTextInput("합성 점심")
        compose.onNodeWithText("계좌 선택").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("새 계좌 추가").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("계좌 이름").performTextInput("합성 생활비")
        compose.onNodeWithText("계좌 저장").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("등록 확인").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(repo.transactions.isEmpty())
        compose.onNodeWithText("이 계좌 사용").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("2000")
        compose.onNodeWithContentDescription("메모 (선택)").assertTextContains("합성 점심")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("기록을 저장했어요").assertExists()
        compose.onNodeWithText("목록 보기").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("원래 홈").assertExists()
        assertEquals(1, repo.accounts.size)
        assertEquals(1, repo.transactions.size)
        assertEquals(2000L, repo.transactions.single().amount)
        assertEquals("합성 점심", repo.transactions.single().memo)
        assertEquals(repo.accounts.single().id, repo.transactions.single().debitAccountId)
        assertEquals(2, repo.finished.size)
    }

    /** 실제 시스템 Back은 확인 대화상자를 거치며 나가기를 저장 취소나 금융 쓰기로 취급하지 않는다. */
    @Test fun systemBackRequiresExplicitExitWithoutWriting() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("1000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("거래 입력에서 나갈까요?").assertExists()
        compose.onNodeWithText("계속 입력하기").performClick()
        compose.onNodeWithContentDescription("금액 (원)").assertTextContains("1000")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("나가기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("원래 홈").assertExists()
        assertTrue(repo.accounts.isEmpty())
        assertTrue(repo.transactions.isEmpty())
        assertTrue(repo.finished.isEmpty())
    }

    /** 첫 오류 입력으로 초점을 돌리고 오류 값·다른 필드를 지우지 않는다. */
    @Test fun invalidAmountFocusesFieldAndPreservesMemo() {
        launch()
        compose.onNodeWithText("새 거래").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("금액 (원)").performTextInput("0")
        compose.onNodeWithContentDescription("메모 (선택)").performTextInput("보존")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithContentDescription("금액 (원)").assertIsFocused().assertTextContains("0")
        compose.onNodeWithContentDescription("메모 (선택)").assertTextContains("보존")
        assertTrue(repo.transactions.isEmpty())
    }

    private fun launch() {
        compose.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MonitTheme {
                MonitAppShell(
                    home = { actions ->
                        Text("원래 홈")
                        MonitButton("새 거래", onClick = { actions.newTransaction(null) })
                    },
                    transactions = { Text("원래 거래") },
                    accounts = { Text("원래 계좌") },
                    transactionEntry = { entry, destination, navigator ->
                        val factory = remember { viewModelFactory { initializer { TransactionEntryViewModel(repo, clock, createSavedStateHandle()) } } }
                        val vm: TransactionEntryViewModel = viewModel(factory = factory)
                        TransactionEntryRoute(
                            testContext,
                            destination,
                            entry,
                            onRegisterAccount = { request ->
                                navigator.openAccountRegistration(
                                    entry,
                                    AccountRegistrationDestination(UUID.randomUUID().toString(), AccountRegistrationOrigin.TRANSACTION_ENTRY, destination.entryId, request),
                                )
                            },
                            onClose = { navigator.closeConfirmedTransactionEntry(entry) },
                            viewModel = vm,
                        )
                    },
                    accountRegistration = { entry, _, navigator ->
                        val factory = remember { viewModelFactory { initializer { AccountRegistrationViewModel(repo, clock, createSavedStateHandle()) } } }
                        val vm: AccountRegistrationViewModel = viewModel(factory = factory)
                        AccountRegistrationRoute(
                            testContext,
                            { navigator.closeConfirmedAccountRegistration(entry) },
                            { navigator.returnFromAccountRegistration(entry, it) },
                            vm,
                        )
                    },
                )
            }
        }
    }
}

private class JourneyRepository : LedgerRepository by QueryRepository() {
    val accounts = mutableListOf<LedgerAccount>()
    val transactions = mutableListOf<CreateTransaction>()
    val finished = mutableListOf<String>()
    private val receipts = mutableMapOf<String, WriteReceipt>()
    override suspend fun submissionRecovery(context: LedgerContext) = SubmissionRecoverySnapshot(context, emptyList())
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate) = LedgerSnapshot(1, accounts.toList(), emptyList(), accounts.associate { it.id to null }, 0, 0)
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt {
        accounts += LedgerAccount(
            value.id,
            value.name,
            value.nature,
            AccountStatus.ACTIVE,
            1,
            BalanceAnchor(value.anchorId, value.anchorDate, null, null, value.unknownReason),
        )
        return WriteReceipt(operationId, 1, listOf(EntityRef("accounts", value.id, 1), EntityRef("anchors", value.anchorId, 1))).also { receipts[operationId] = it }
    }
    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt {
        transactions += value
        return WriteReceipt(operationId, 2, listOf(EntityRef("transactions", value.id, 1))).also { receipts[operationId] = it }
    }
    override suspend fun receipt(context: LedgerContext, operationId: String) = receipts[operationId]
    override suspend fun finishSubmissionRecovery(context: LedgerContext, operationId: String): FinishSubmissionRecoveryResult {
        finished += operationId
        return FinishSubmissionRecoveryResult.Finished
    }
}
