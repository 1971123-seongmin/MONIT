package com.monit.app.importing

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
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
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.monit.app.query.QueryRepository
import com.monit.app.ui.MonitAppShell
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.data.importing.ImportPreviewEvaluator
import com.monit.core.data.importing.ImportPreviewParser
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*
import com.monit.feature.accounts.importing.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 실제 Route/VM/Nav2와 파일 선택 contract를 연결한다. 실제 Notion/Room/단말 자료를 읽지 않는다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportPreviewJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val context = LedgerContext(id(1), id(2))
    private val ledger = PreviewLedger()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private var bytes = File("../docs/contracts/fixtures/import-preview-v1-synthetic.json").readBytes()
    private var pickerIntent: Intent? = null
    private var chosenUri: Uri? = Uri.parse("content://synthetic-import/preview")
    private lateinit var model: ImportPreviewViewModel
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var rootView: View
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            pickerIntent = contract.createIntent(org.robolectric.RuntimeEnvironment.getApplication(), input)
            dispatchResult(requestCode, chosenUri)
        }
    }

    /** 파일 선택 취소는 정상 0건 결과를 만들거나 장부를 조회하지 않는다. */
    @Test fun cancelingFilePickerKeepsUnselectedState() {
        chosenUri = null
        launch()
        openAndPick()
        await(ImportPreviewPhase.EMPTY)
        assertNull(model.state.value.report)
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.writes)
    }

    /** 실제 OpenDocument contract 선택→지원 후보/보류/제외 표시→시스템 Back 복귀까지 금융 쓰기는 없다. */
    @Test fun filePickerPreviewAndBackNeverWriteFinancialRecords() {
        launch()
        openAndPick()
        await(ImportPreviewPhase.READY)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pickerIntent?.action)
        assertEquals(listOf("application/json", "text/plain"), checkNotNull(pickerIntent).getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertEquals(0, checkNotNull(pickerIntent).flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        compose.onNodeWithText("최종 확인 전에는 금융 기록을 반영하지 않아요. 원본 파일은 수정하지 않아요.").assertExists()
        scrollTo("읽은 원본 행 6건")
        compose.onNodeWithText("반영예정 후보 3건 · 기반영 0건").assertExists()
        compose.onNodeWithText("보류 2건 · 제외 1건").assertExists()
        compose.onNodeWithText("반영하기").assertDoesNotExist()
        capture("preview-light")
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("원래 계좌").assertExists()
        assertEquals(0, ledger.writes)
    }

    /** 파일/장부 변경은 자동 반영 없이 이전 결과의 재검토를 요구하고 명시 재검토 후 새 기준을 표시한다. */
    @Test fun changedLedgerRequiresExplicitReview() {
        launch()
        openAndPick()
        await(ImportPreviewPhase.READY)
        ledger.revision = 5
        scrollTo("장부 변경 여부 확인")
        compose.onNodeWithText("장부 변경 여부 확인").performClick()
        await(ImportPreviewPhase.STALE)
        scrollTo("원본·매핑·장부 재검토가 필요해요")
        compose.onNodeWithText("원본·매핑·장부 재검토가 필요해요").assertExists()
        assertEquals(4, model.state.value.report?.ledgerRevision)
        scrollTo("현재 장부로 다시 검토")
        compose.onNodeWithText("현재 장부로 다시 검토").performClick()
        await(ImportPreviewPhase.READY)
        assertEquals(5, model.state.value.report?.ledgerRevision)
        assertEquals(0, ledger.writes)
    }

    /** 깨진 파일은 전체 행 수 확인 불가이며 기존 기록 보존/실제0을 표시한다. */
    @Test fun invalidFileShowsUnknownCountInDarkLargeText() {
        bytes = byteArrayOf(0xC3.toByte(), 0x28)
        launch(dark = true, width = 320, font = 2f)
        openAndPick()
        await(ImportPreviewPhase.FAILED)
        scrollTo("전체 원본 행 수: 확인 불가 · 금융 반영 0건")
        compose.onNodeWithText("전체 원본 행 수: 확인 불가 · 금융 반영 0건").assertExists()
        capture("invalid-dark-large-font")
        assertEquals(0, ledger.reads)
        assertEquals(0, ledger.writes)
    }

    /** 안정 key 목록의 마지막 보류/원문 근거도 320dp/200% 글꼴에서 도달한다. */
    @Test fun lastHeldRowAndEvidenceAreReachableWithLargeFont() {
        launch(dark = true, width = 320, font = 2f)
        openAndPick()
        await(ImportPreviewPhase.READY)
        scrollTo("합성 관계 미확보")
        compose.onNodeWithText("합성 관계 미확보").assertIsDisplayed()
        compose.onAllNodesWithText("원문 근거 보기").onLast().performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("\"내역\":\"합성 관계 미확보\"", substring = true))
        compose.onNode(hasText("\"내역\":\"합성 관계 미확보\"", substring = true)).assertIsDisplayed()
        capture("held-dark-large-font")
        assertEquals(0, ledger.writes)
    }

    private fun await(phase: ImportPreviewPhase) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            model.state.value.phase == phase
        }
        compose.waitForIdle()
    }
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
    }
    private fun openAndPick() {
        compose.onNodeWithText("계좌").performClick()
        compose.onNodeWithText("이관 미리보기").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("검토 파일 선택").performScrollTo().performClick()
    }
    private fun launch(dark: Boolean = false, width: Int = 360, font: Float = 1f) {
        compose.setContent {
            val view = LocalView.current
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect { rootView = view }
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                },
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                        MonitTheme(dark) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("import-root"),
                            ) {
                                MonitAppShell(
                                    home = { Text("원래 홈") },
                                    transactions = { Text("원래 거래") },
                                    accounts = { actions ->
                                        Text("원래 계좌")
                                        MonitButton("이관 미리보기", onClick = checkNotNull(actions.previewImport))
                                    },
                                    importPreview = { entry, navigator ->
                                        val factory = remember {
                                            viewModelFactory {
                                                initializer {
                                                    ImportPreviewViewModel(PreviewImports(ledger, clock), ImportDocumentReader { _, _ -> bytes.copyOf() }, contexts, LocalDataWorkGate(), SavedStateHandle())
                                                }
                                            }
                                        }
                                        val vm: ImportPreviewViewModel = viewModel(factory = factory)
                                        SideEffect { model = vm }
                                        ImportPreviewRoute(
                                            context = context,
                                            onBack = { navigator.closeImportPreview(entry) },
                                            backupContent = { _, _ -> Text("합성 시험의 백업 슬롯") },
                                            viewModel = vm,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    private fun capture(name: String) {
        val bounds = compose.onNodeWithTag("import-root").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            rootView.draw(canvas)
        }
        val directory = File("build/reports/import-preview/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"

/** 화면/파일 선택 시험의 읽기 전용 Repository 대역. 실제 반영·백업 증명은 Room 통합 시험의 책임이다. */
private class PreviewImports(private val ledger: PreviewLedger, private val clock: Clock) : ImportRepository {
    private var bytes: ByteArray? = null
    private var reviewed: ImportReview? = null
    override suspend fun review(context: LedgerContext, source: ByteArray, options: ImportReviewOptions): ImportReview {
        val input = ImportPreviewParser.parse(source)
        val snapshot = ledger.snapshot(context, LocalDate.now(clock))
        val report = ImportPreviewEvaluator.evaluate(input, context, snapshot, LocalDate.now(clock))
        bytes = source.copyOf()
        return ImportReview("host-preview", id(800), report, options, snapshot.accounts, emptyList(), false, accountLinks = input.accounts).also { reviewed = it }
    }
    override suspend fun revise(context: LedgerContext, token: String, options: ImportReviewOptions) = review(context, checkNotNull(bytes), options)
    override suspend fun bindBackup(context: LedgerContext, token: String, exportId: String): ImportReview = error("이 시험은 실제 백업/반영을 실행하지 않는다")
    override suspend fun checkSource(context: LedgerContext, token: String, readBack: ByteArray) {
        if (!checkNotNull(bytes).contentEquals(readBack) || ledger.snapshot(context, LocalDate.now(clock)).revision != checkNotNull(reviewed).report.ledgerRevision) throw ImportFileException(ImportFileProblem.STALE_REVIEW)
    }
    override suspend fun apply(context: LedgerContext, token: String, onStage: (ImportStage) -> Unit): ImportResult = error("이 시험은 실제 백업/반영을 실행하지 않는다")
    override suspend fun attempt(context: LedgerContext, batchId: String): ImportAttempt = ImportAttempt.Missing
    override suspend fun pending(context: LedgerContext): List<ImportAttempt> = emptyList()
    override suspend fun acknowledge(context: LedgerContext, batchId: String) = Unit
    override fun discard(token: String) = Unit
}

private class PreviewLedger : LedgerRepository by QueryRepository() {
    var revision = 4L
    var reads = 0
    var writes = 0
    override suspend fun snapshot(context: LedgerContext, asOf: LocalDate): LedgerSnapshot {
        reads++
        val accounts = listOf(10, 11).map { number ->
            LedgerAccount(
                id(number),
                "합성 계좌 $number",
                AccountNature.GENERAL,
                AccountStatus.ACTIVE,
                1,
                BalanceAnchor(id(number + 10), LocalDate.of(2026, 1, 1), null, null, "미확인"),
            )
        }
        return LedgerSnapshot(revision, accounts, emptyList(), accounts.associate { it.id to null }, 0, 0)
    }
    override suspend fun createAccount(context: LedgerContext, operationId: String, value: CreateAccount): WriteReceipt {
        writes++
        error("금융 반영 금지")
    }
    override suspend fun createTransaction(context: LedgerContext, operationId: String, value: CreateTransaction): WriteReceipt {
        writes++
        error("금융 반영 금지")
    }
}
