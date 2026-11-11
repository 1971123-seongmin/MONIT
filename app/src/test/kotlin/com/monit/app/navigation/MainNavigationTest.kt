package com.monit.app.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import com.monit.app.ui.MonitAppState
import com.monit.app.ui.rememberMonitAppState
import com.monit.core.navigation.NavigationResult
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.AccountRegistrationOrigin
import com.monit.feature.home.navigation.HomeDestination
import com.monit.feature.transactions.navigation.RegisteredAccountResult
import com.monit.feature.transactions.navigation.RegisteredAccountResultEffect
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import com.monit.feature.transactions.navigation.TransactionEntryKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 시험 화면은 이 파일에만 존재한다. 실제 제품 UI·금융 저장·IME 시험을 대신하지 않는다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MainNavigationTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var controller: TestNavHostController
    private lateinit var main: MainNavigator
    private lateinit var appState: MonitAppState
    private val hostVisible = mutableStateOf(true)
    private val request = mutableStateOf("request-one")
    private val accepted = mutableListOf<RegisteredAccountResult>()
    private val handler = mutableStateOf<(RegisteredAccountResult) -> Boolean>({
        accepted += it
        true
    })
    private var invalidResults = 0

    private fun installHost(consumeResult: Boolean = false) {
        compose.setContent {
            if (hostVisible.value) {
                val context = LocalContext.current
                val current = remember(context) {
                    TestNavHostController(context).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }
                val navigation = rememberMainNavigator(current)
                val state = rememberMonitAppState(current)
                SideEffect {
                    controller = current
                    main = navigation
                    appState = state
                }
                MonitNavHost(
                    controller = current,
                    home = { Box(Modifier.size(40.dp).testTag("test-home")) },
                    transactions = { Box(Modifier.size(40.dp).testTag("test-transactions")) },
                    accounts = { Box(Modifier.size(40.dp).testTag("test-accounts")) },
                    transactionEntry = { entry, _ ->
                        if (consumeResult) {
                            RegisteredAccountResultEffect(
                                entry = entry,
                                selectionRequestId = request.value,
                                onResult = handler.value,
                                onInvalidResult = { invalidResults++ },
                            )
                        }
                        Box(Modifier.size(40.dp).testTag("test-entry"))
                    },
                    accountRegistration = { _, _ ->
                        Box(Modifier.size(40.dp).testTag("test-registration"))
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, current().lifecycle.currentState)
        }
    }

    private fun current(): NavBackStackEntry = requireNotNull(controller.currentBackStackEntry)

    private fun move(action: (MainNavigator, NavBackStackEntry) -> NavigationResult): NavigationResult {
        var outcome = NavigationResult.UNAVAILABLE
        compose.runOnIdle { outcome = action(main, current()) }
        compose.waitForIdle()
        return outcome
    }

    private fun openEntry() {
        assertEquals(NavigationResult.EXECUTED, move { navigation, source ->
            navigation.openTransactionEntry(
                source,
                TransactionEntryDestination(
                    "entry-one",
                    TransactionEntryKind.TRANSFER,
                    "2026-11-01"
                )
            )
        })
    }

    private fun openRegistration(registrationId: String = "registration-one") {
        assertEquals(NavigationResult.EXECUTED, move { navigation, source ->
            navigation.openAccountRegistration(
                source, AccountRegistrationDestination(
                    registrationId, AccountRegistrationOrigin.TRANSACTION_ENTRY,
                    callerEntryId = "entry-one", selectionRequestId = request.value,
                )
            )
        })
    }

    /** 기본 진입은 홈이며 같은 탭 재선택은 entry·실행기·AppState 객체를 바꾸지 않는다. */
    @Test
    fun initialHomeAndReselectionKeepIdentity() {
        installHost()
        val root = current()
        val navigator = main
        val state = appState
        assertEquals(TopLevelDestination.HOME, state.currentTopLevelDestination)
        assertTrue(state.showBottomBar)
        assertEquals(
            NavigationResult.IGNORED,
            move { navigation, source ->
                navigation.navigateToTopLevel(
                    source,
                    TopLevelDestination.HOME
                )
            })
        assertSame(root, current())
        assertSame(navigator, main)
        assertSame(state, appState)
    }

    /** 탭 왕복에서 조회 월과 entry ID를 복원하며 같은 탭을 다시 눌러도 초기화하지 않는다. */
    @Test
    fun tabRoundTripRestoresStateWithoutDuplicateEntry() {
        installHost()
        move { navigation, source ->
            navigation.navigateToTopLevel(
                source,
                TopLevelDestination.TRANSACTIONS
            )
        }
        val transactions = current()
        compose.runOnIdle { transactions.savedStateHandle["month"] = "2026-08" }
        move { navigation, source ->
            navigation.navigateToTopLevel(
                source,
                TopLevelDestination.ACCOUNTS
            )
        }
        move { navigation, source ->
            navigation.navigateToTopLevel(
                source,
                TopLevelDestination.TRANSACTIONS
            )
        }
        val restored = current()
        assertEquals(transactions.id, restored.id)
        assertEquals("2026-08", restored.savedStateHandle.get<String>("month"))
        assertEquals(
            NavigationResult.IGNORED,
            move { navigation, source ->
                navigation.navigateToTopLevel(
                    source,
                    TopLevelDestination.TRANSACTIONS
                )
            })
        assertSame(restored, current())
    }

    /** 거래/계좌 루트 Back은 홈 정책을 적용하고 홈은 OS 처리에 맡길 실행 불가 결과다. */
    @Test
    fun topLevelBackUsesHomeAndLeavesOsBackToHost() {
        installHost()
        move { navigation, source ->
            navigation.navigateToTopLevel(
                source,
                TopLevelDestination.ACCOUNTS
            )
        }
        assertEquals(
            NavigationResult.EXECUTED,
            move { navigation, source -> navigation.onTopLevelBack(source) })
        assertTrue(current().destination.hasRoute<HomeDestination>())
        val home = current()
        assertEquals(
            NavigationResult.UNAVAILABLE,
            move { navigation, source -> navigation.onTopLevelBack(source) })
        assertSame(home, current())
    }

    /** 입력 중 탭/루트 Back은 포기 확인을 우회하지 않고 작은 초기 인자를 유지한다. */
    @Test
    fun entryArgumentsAndExitGateArePreserved() {
        installHost()
        openEntry()
        val entry = current()
        assertEquals(
            TransactionEntryDestination("entry-one", TransactionEntryKind.TRANSFER, "2026-11-01"),
            entry.toRoute<TransactionEntryDestination>()
        )
        assertFalse(appState.showBottomBar)
        assertEquals(
            NavigationResult.UNAVAILABLE,
            move { navigation, source ->
                navigation.navigateToTopLevel(
                    source,
                    TopLevelDestination.ACCOUNTS
                )
            })
        assertEquals(
            NavigationResult.UNAVAILABLE,
            move { navigation, source -> navigation.onTopLevelBack(source) })
        assertSame(entry, current())
        assertEquals(
            NavigationResult.EXECUTED,
            move { navigation, source -> navigation.closeConfirmedTransactionEntry(source) })
        assertTrue(appState.showBottomBar)
    }

    /** caller가 다른 신규 등록 요청은 진입 단계에서 거부한다. */
    @Test
    fun registrationFromWrongCallerIsUnavailable() {
        installHost()
        openEntry()
        val caller = current()
        assertEquals(NavigationResult.UNAVAILABLE, move { navigation, source ->
            navigation.openAccountRegistration(
                source, AccountRegistrationDestination(
                    "registration",
                    AccountRegistrationOrigin.TRANSACTION_ENTRY,
                    "another-entry",
                    "request",
                )
            )
        })
        assertSame(caller, current())
    }

    /** 복원된 잘못된 caller 참조도 반환 시 다시 거부하며 다른 입력에 결과를 쓰지 않는다. */
    @Test
    fun restoredMismatchingCallerDoesNotReceiveResult() {
        installHost()
        openEntry()
        val caller = current()
        compose.runOnIdle {
            controller.navigate(
                AccountRegistrationDestination(
                    "restored",
                    AccountRegistrationOrigin.TRANSACTION_ENTRY,
                    "another-entry",
                    "request-one",
                )
            )
        }
        compose.waitForIdle()
        val registration = current()
        assertEquals(
            NavigationResult.UNAVAILABLE,
            move { navigation, source ->
                navigation.returnFromAccountRegistration(
                    source,
                    "account-one"
                )
            })
        assertSame(registration, current())
        assertFalse(caller.savedStateHandle.contains(RegisteredAccountResult.key("request-one")))
    }

    /**
     * 목적: 등록 결과를 원래 caller의 후보로 한 번 소비하고 반대편 계좌를 보존한다.
     * 조건: 실제 host·수신 effect와 합성 계좌 ID를 사용하는 UI 계약 시험이다.
     * 절차: 입력 → 등록 → 반환 → 재수집/옛 source 재호출 순서다.
     * 기대: 후보 한 건·키 제거·다중 pop 없음. 금융 성공 여부는 시험하지 않는다.
     */
    @Test
    fun registrationReturnIsConsumedOnceAndKeepsOtherInput() {
        installHost(consumeResult = true)
        openEntry()
        val caller = current()
        compose.runOnIdle { caller.savedStateHandle["opposite-account"] = "kept" }
        openRegistration()
        val registration = current()
        assertEquals(
            NavigationResult.EXECUTED,
            move { navigation, source ->
                navigation.returnFromAccountRegistration(
                    source,
                    "account-one"
                )
            })
        assertSame(caller, current())
        assertEquals(listOf(RegisteredAccountResult("request-one", "account-one")), accepted)
        assertFalse(caller.savedStateHandle.contains(RegisteredAccountResult.key("request-one")))
        assertEquals("kept", caller.savedStateHandle.get<String>("opposite-account"))
        compose.runOnIdle {
            assertEquals(
                NavigationResult.IGNORED,
                main.returnFromAccountRegistration(registration, "account-one")
            )
        }
        compose.waitForIdle()
        assertSame(caller, current())
        assertEquals(1, accepted.size)
        openRegistration("lifecycle-check")
        move { navigation, source -> navigation.closeConfirmedAccountRegistration(source) }
        assertSame(caller, current())
        assertEquals(1, accepted.size)
    }

    /** 새 UI 요청은 이전에 소비한 키를 재사용하지 않고 새 키로 결과를 수신한다. */
    @Test
    fun nextRequestAndUpdatedCallbackAreObserved() {
        installHost(consumeResult = true)
        openEntry()
        openRegistration()
        move { navigation, source ->
            navigation.returnFromAccountRegistration(
                source,
                "account-one"
            )
        }
        compose.runOnIdle {
            request.value = "request-two"
            handler.value =
                { result -> accepted += result.copy(accountId = "latest-callback"); true }
        }
        compose.waitForIdle()
        openRegistration("registration-two")
        move { navigation, source ->
            navigation.returnFromAccountRegistration(
                source,
                "account-two"
            )
        }
        assertEquals(listOf("request-one", "request-two"), accepted.map { it.selectionRequestId })
        assertEquals("latest-callback", accepted.last().accountId)
        assertFalse(current().savedStateHandle.contains(RegisteredAccountResult.key("request-two")))
    }

    /**
     * 목적: 같은 entry·키의 collector가 재구성 뒤 최신 콜백을 사용한다.
     * 조건: 요청 ID와 entry는 고정하고 콜백만 변경한다.
     * 절차: 활성 수집 중 콜백 교체를 적용한 뒤 같은 키에 결과를 기록하고 idle을 기다린다.
     * 기대: 최신 콜백 한 번 호출·키 제거. 콜백 교체 자체는 결과를 만들지 않는다.
     */
    @Test
    fun recompositionUsesLatestCallbackWithoutChangingRequest() {
        installHost(consumeResult = true)
        openEntry()
        compose.runOnIdle {
            handler.value = { result ->
                accepted += result.copy(accountId = "latest-callback")
                true
            }
        }
        compose.waitForIdle()
        assertTrue(accepted.isEmpty())
        compose.runOnIdle {
            current().savedStateHandle[RegisteredAccountResult.key("request-one")] =
                RegisteredAccountResult("request-one", "original").toBundle()
        }
        compose.waitForIdle()
        assertEquals(listOf(RegisteredAccountResult("request-one", "latest-callback")), accepted)
        assertFalse(current().savedStateHandle.contains(RegisteredAccountResult.key("request-one")))
    }

    /** 수신 feature의 폼/슬롯/context 검증이 거부하면 결과를 성공 소비로 지우지 않는다. */
    @Test
    fun rejectingConsumerRetainsUiResult() {
        handler.value = { false }
        installHost(consumeResult = true)
        openEntry()
        val caller = current()
        openRegistration()
        move { navigation, source ->
            navigation.returnFromAccountRegistration(
                source,
                "account-one"
            )
        }
        assertTrue(accepted.isEmpty())
        assertTrue(caller.savedStateHandle.contains(RegisteredAccountResult.key("request-one")))
    }

    /** 잘못된 저장 UI 값은 callback으로 드러내고 성공 후보로 적용하거나 제거하지 않는다. */
    @Test
    fun malformedUiResultIsVisibleAndPreserved() {
        installHost(consumeResult = true)
        openEntry()
        compose.runOnIdle {
            current().savedStateHandle[RegisteredAccountResult.key("request-one")] = "invalid"
        }
        compose.waitForIdle()
        assertEquals(1, invalidResults)
        assertTrue(accepted.isEmpty())
        assertTrue(current().savedStateHandle.contains(RegisteredAccountResult.key("request-one")))
    }

    /** 숨겨진 caller는 결과를 소비하지 않고 실제 RESUMED 복귀 뒤에만 처리한다. */
    @Test
    fun hiddenCallerWaitsForResume() {
        installHost(consumeResult = true)
        openEntry()
        val caller = current()
        openRegistration()
        compose.runOnIdle {
            caller.savedStateHandle[RegisteredAccountResult.key("request-one")] =
                RegisteredAccountResult("request-one", "account-one").toBundle()
        }
        compose.waitForIdle()
        assertTrue(accepted.isEmpty())
        move { navigation, source -> navigation.closeConfirmedAccountRegistration(source) }
        assertEquals(1, accepted.size)
    }

    /** host 교체 뒤 옛 MainNavigator 요청은 새 host로 전파되지 않는다. */
    @Test
    fun hostReplacementRejectsOldNavigator() {
        installHost()
        val oldController = controller
        val oldMain = main
        val oldSource = current()
        compose.runOnIdle { hostVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { hostVisible.value = true }
        compose.waitForIdle()
        assertNotSame(oldController, controller)
        compose.runOnIdle {
            assertEquals(
                NavigationResult.IGNORED,
                oldMain.navigateToTopLevel(oldSource, TopLevelDestination.ACCOUNTS)
            )
        }
        assertTrue(current().destination.hasRoute<HomeDestination>())
    }

    /** 독립 등록의 성공은 기존 계좌 entry로 돌아가며 거래 선택 결과는 만들지 않는다. */
    @Test
    fun standaloneRegistrationReturnsOnlyToAccounts() {
        installHost()
        move { navigation, source ->
            navigation.navigateToTopLevel(
                source,
                TopLevelDestination.ACCOUNTS
            )
        }
        val accounts = current()
        move { navigation, source ->
            navigation.openAccountRegistration(
                source,
                AccountRegistrationDestination("standalone", AccountRegistrationOrigin.ACCOUNTS)
            )
        }
        move { navigation, source ->
            navigation.returnFromAccountRegistration(
                source,
                "account-one"
            )
        }
        assertSame(accounts, current())
        assertTrue(accepted.isEmpty())
        assertEquals(TopLevelDestination.ACCOUNTS, appState.currentTopLevelDestination)
    }
}
