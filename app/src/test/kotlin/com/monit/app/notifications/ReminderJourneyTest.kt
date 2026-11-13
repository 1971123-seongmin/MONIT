package com.monit.app.notifications

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monit.app.payments.PaymentTestEnvironment
import com.monit.app.payments.paymentTestId
import com.monit.app.ui.MonitAppShell
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*
import com.monit.feature.home.HomeRoute
import com.monit.feature.home.HomeViewModel
import com.monit.feature.home.payments.PaymentsRoute
import com.monit.feature.home.payments.PaymentsViewModel
import com.monit.feature.home.reminders.*
import java.io.File
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Route/VM/Nav2와 Native Canvas의 합성 시험. 금융·예약 원자성은 별도 data/platform 시험이다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val repo = ReminderTestRepository()
    private val payments = PaymentTestEnvironment()
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private var pending by mutableStateOf<ReminderLink?>(null)
    private var consumed = 0
    private var permissionRequests = 0
    private val settingsRequests = mutableListOf<Boolean>()
    private lateinit var settings: ReminderSettingsViewModel
    private lateinit var root: View
    private lateinit var back: androidx.activity.OnBackPressedDispatcher

    private fun show(dark: Boolean = false, width: Int = 360, font: Float = 1f) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                    MonitTheme(darkTheme = dark) {
                        val view = LocalView.current
                        val owner = LocalOnBackPressedDispatcherOwner.current

                        SideEffect {
                            root = view
                            back = requireNotNull(owner).onBackPressedDispatcher
                        }

                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("reminder-root"),
                        ) {
                            MonitAppShell(
                                reminderLink = pending,
                                onReminderConsumed = { token ->
                                    if (pending?.token == token) {
                                        pending = null
                                        consumed++
                                    }
                                },
                                home = { actions ->
                                    val factory = remember { viewModelFactory { initializer { HomeViewModel(payments.ledger, clock, contexts, payments.payments) } } }
                                    val model: HomeViewModel = viewModel(factory = factory)

                                    HomeRoute(
                                        context = repo.context,
                                        viewModel = model,
                                        onPayments = actions.payments,
                                        onReminders = actions.reminders,
                                    )
                                },
                                transactions = { Text("원래 거래 탭") },
                                accounts = { Text("원래 계좌 탭") },
                                reminderSettings = { entry, navigator ->
                                    val factory = remember(entry) { viewModelFactory { initializer { ReminderSettingsViewModel(repo, repo.source, contexts) } } }
                                    val model: ReminderSettingsViewModel = viewModel(factory = factory)

                                    SideEffect { settings = model }

                                    ReminderSettingsRoute(
                                        context = repo.context,
                                        onBack = { navigator.closeReminderSettings(entry) },
                                        onRequestPermission = { permissionRequests++ },
                                        onOpenSettings = { channel -> settingsRequests += channel },
                                        onReschedule = { model.refresh() },
                                        viewModel = model,
                                    )
                                },
                                payments = { entry, destination, navigator ->
                                    val factory = remember(entry) { viewModelFactory { initializer { PaymentsViewModel(payments.payments, payments.ledger, payments.drafts, clock, contexts, createSavedStateHandle()) } } }
                                    val model: PaymentsViewModel = viewModel(factory = factory)

                                    PaymentsRoute(
                                        context = repo.context,
                                        entryId = destination.entryId,
                                        onClose = { navigator.closePayments(entry) },
                                        onEditTransaction = { error("This notification query never edits a transaction") },
                                        viewModel = model,
                                        initialMonth = destination.month,
                                        initialItem = destination.itemId,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun click(text: String) {
        compose.waitForIdle()
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithTag("reminder-settings-list").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("reminder-settings-list").performScrollToNode(hasText(text))
        }
        awaitText(text)
        val node = compose.onNodeWithText(text)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }
    private fun openSettings() {
        click("알림 설정")
        awaitSettings("전체 알림 켜기") { !it.enabled && it.rules.single().enabled }
    }

    /** READY 단독으로는 저장 전/재조회 전 상태도 통과하므로 기대 projection과 실제 화면을 함께 확인한다. */
    private fun awaitSettings(text: String, expected: (ReminderSettingsUiState) -> Boolean) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            val state = settings.state.value
            state.phase == ReminderSettingsPhase.READY && expected(state.ui)
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reminder-settings-list").performScrollToNode(hasText(text))
        }
        awaitText(text)
        compose.runOnIdle { assertTrue(expected(settings.state.value.ui)) }
    }

    /** 홈 설정 진입→전체 켜기→일정별 끄기→Back의 실제 화면 연결과 선호 보존을 확인한다. */
    @Test fun homeSettingsGlobalAndRuleTogglesReturnToOriginalHome() {
        show()
        openSettings()
        click("전체 알림 켜기")
        awaitSettings("알림 켜짐") { it.enabled && it.allowed }
        capture("reminder-enabled-light")
        click("합성 보험 알림 끄기")
        awaitSettings("개별 설정 · 꺼짐") { it.enabled && !it.rules.single().enabled }
        assertTrue(repo.config.enabled)
        assertFalse(repo.config.rules.single().enabled)
        assertTrue(payments.calls.isEmpty())
        compose.runOnIdle { back.onBackPressed() }
        awaitText("이번 달 루틴")
        assertEquals(2, repo.writeCalls)
    }

    /** 거부 중에도 선호를 유지하며 권한 요청은 명시 버튼에서만 실행한다. */
    @Test fun deniedPermissionAndSettingsReturnNeverDisableStoredPreference() {
        repo.os = ReminderAccess(false, false, true)
        show()
        openSettings()
        click("전체 알림 켜기")
        awaitSettings("알림 불가") { it.enabled && it.permissionDenied }
        assertEquals(0, permissionRequests)
        click("알림 권한 허용")
        assertEquals(1, permissionRequests)
        click("알림 설정 열기")
        assertEquals(listOf(false), settingsRequests)
        compose.runOnIdle {
            repo.os = ReminderAccess(true, true, true)
            settings.refresh()
        }
        awaitSettings("알림 켜짐") { it.enabled && it.allowed }
        assertTrue(repo.config.enabled)
        assertEquals(1, repo.writeCalls)
        assertTrue(payments.calls.isEmpty())
    }

    /** 앱 차단과 채널 차단은 다른 설명/설정 대상이며 실제 재조회로 갱신한다. */
    @Test fun appAndChannelBlockingHaveDistinctCurrentSettingsTargets() {
        repo.config = repo.config.copy(enabled = true, revision = 1)
        repo.os = ReminderAccess(true, false, true)
        show()
        click("알림 설정")
        awaitSettings("알림 불가") { it.enabled && it.appBlocked && !it.channelBlocked }
        compose.onNodeWithText("기기 설정에서 MONIT 앱 알림이 꺼져 있어요.\n기록과 수동 납입 확인은 계속 가능해요.").assertExists()
        click("알림 설정 열기")
        compose.runOnIdle {
            repo.os = ReminderAccess(true, true, false)
            settings.refresh()
        }
        awaitSettings("실제 OS 상태 · 권한 허용 / 앱 허용 / 채널 차단·확인 필요") { it.enabled && !it.appBlocked && it.channelBlocked }
        click("알림 설정 열기")
        assertEquals(listOf(false, true), settingsRequests)
        assertTrue(repo.config.enabled)
        assertEquals(0, repo.writeCalls)
    }

    /**
     * 목적: 320dp·큰 글꼴 Dark에서 일정별 설정의 저장/재조회와 화면 반영 완료를 구분한다.
     * 조건: 쓰기와 이어지는 조회를 서로 다른 gate로 중단하는 합성 Repository다.
     * 절차: 이전 문구 확인→쓰기 진입/SAVING→쓰기 해제→조회 진입/LOADING→조회 해제→새 projection/문구를 기다린다.
     * 기대: 저장 전에는 원래 선호를 유지하고, 재조회 뒤 개별 꺼짐 표시/저장1회/금융 제출0이다. gate는 실패 시에도 해제한다.
     */
    @Test fun darkLargeFontRemainsScrollableAndKeepsMeaningfulActions() {
        repo.os = ReminderAccess(false, true, true)
        show(dark = true, width = 320, font = 2f)
        openSettings()
        capture("reminder-denied-dark-large")
        val writeGate = CompletableDeferred<Unit>()
        val readGate = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>()
        repo.gate = writeGate
        repo.configurationGate = readGate
        repo.configurationEntered = readEntered
        try {
            click("합성 보험 알림 끄기")
            compose.waitUntil(10_000) { repo.writeCalls == 1 }
            compose.runOnIdle {
                assertEquals(ReminderSettingsPhase.SAVING, settings.state.value.phase)
                assertTrue(repo.config.rules.single().enabled)
            }
            compose.onNodeWithText("기본값 · 켜짐").assertExists()
            compose.runOnIdle { writeGate.complete(Unit) }
            compose.waitUntil(10_000) { readEntered.isCompleted }
            compose.runOnIdle {
                assertEquals(ReminderSettingsPhase.LOADING, settings.state.value.phase)
                assertFalse(repo.config.rules.single().enabled)
                assertTrue(settings.state.value.ui.rules.single().enabled)
            }
            compose.onNodeWithText("개별 설정 · 꺼짐").assertDoesNotExist()
            compose.runOnIdle { readGate.complete(Unit) }
            awaitSettings("개별 설정 · 꺼짐") { !it.enabled && !it.rules.single().enabled }
        } finally {
            writeGate.complete(Unit)
            readGate.complete(Unit)
        }
        compose.onNodeWithText("개별 설정 · 꺼짐").assertExists()
        assertEquals(1, repo.writeCalls)
        assertFalse(repo.config.rules.single().enabled)
        assertFalse(repo.config.enabled)
        assertTrue(payments.calls.isEmpty())
    }

    /** 알림 링크는 원래 월·회차 상세로만 이동하고 수동 완료/초안 쓰기를 하지 않는다. */
    @Test fun notificationOpensOriginalMonthAndItemAsReadOnlyQuery() {
        pending = repo.link
        show()
        awaitText("합성 보험 납입 확인")
        assertNull(pending)
        assertEquals(1, consumed)
        assertTrue(payments.calls.isEmpty())
        assertTrue(payments.rows.isEmpty())
        capture("reminder-open-payment-light")
    }

    /**
     * 목적: 알림 클릭이 진행 중 입력을 폐기하거나 새 완료를 자동 실행하지 않게 한다.
     * 조건: 실제 납입 Route의 불완전 금액 입력 중 새 조회 링크가 들어온다.
     * 절차: 비동기 월별 목록의 준비 표시를 기다린 뒤 항목 선택→입력 중 링크 전달→원문 유지 확인→명시 보관·목록·홈 Back 후 새 상세 진입.
     * 기대: 기존 raw 초안 보존, 링크 한 번 소비, 금융 제출0이다.
     */
    @Test fun incomingNotificationDefersUntilEntryIsFinishedAndPreservesRawDraft() {
        show()
        click("월별 납입 보기")
        awaitText("반복 납입 등록")
        compose.onNodeWithTag("payment-row:${paymentTestId(50)}").performScrollTo().performClick()
        click("새 납입 내용 입력")
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithContentDescription("실제 금액 (원)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("실제 금액 (원)").performScrollTo().performTextReplacement("00.입력중")
        compose.runOnIdle { pending = repo.link }
        compose.waitForIdle()
        assertEquals(0, consumed)
        compose.onNodeWithContentDescription("실제 금액 (원)").assertTextContains("00.입력중")
        compose.runOnIdle { back.onBackPressed() }
        click("보관하고 목록으로")
        awaitText("반복 납입 등록")
        compose.runOnIdle { back.onBackPressed() }
        awaitText("합성 보험 납입 확인")
        assertEquals(1, consumed)
        val draft = payments.rows.values.filterIsInstance<DraftSnapshot.Editable>().single().draft.content as DraftContent.Payment
        assertEquals("00.입력중", draft.fields["amount"])
        assertTrue(payments.calls.isEmpty())
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("reminder-root").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            root.draw(canvas)
        }
        val directory = File("build/reports/reminders/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
