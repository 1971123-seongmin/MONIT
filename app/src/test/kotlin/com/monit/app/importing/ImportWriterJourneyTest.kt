package com.monit.app.importing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.common.io.LocalDataWorkGate
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*
import com.monit.feature.accounts.importing.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** 실제 Route/VM/Compose의 명시 행동을 검증한다. Repository·백업 slot은 합성 대역이며 실제 파일/금융 검증의 증거가 아니다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportWriterJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val repository = WorkflowImports()
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private val model = ImportPreviewViewModel(repository, ImportDocumentReader { _, _ -> byteArrayOf(7) }, contexts, LocalDataWorkGate(), SavedStateHandle()).also { store.put("workflow", it) }
    private lateinit var rootView: View
    private var large by mutableStateOf(false)

    @After fun close() {
        store.clear()
        RuntimeEnvironment.setFontScale(1f)
    }

    /** 목적: 백업 귀환과 최종 확인을 분리하고 원본6/실제2 결과를 큰 글씨에서도 조회한다.
     * 조건: 검토가 끝난 합성 6행·이체를 포함한 예정2거래와 명시 백업 완료 callback.
     * 절차: 파일 review→백업 slot 귀환→320dp/200% dark 최종 dialog→명시 반영→결과 화면.
     * 기대: 최종 버튼 전 apply0, 이후1회이며 원본 대사·부분 지원·실제2 문구를 표시한다. Native host 이미지3장을 생성한다.
     */
    @Test fun backupReturnAndFinalDialogAreSeparateFromApplyingAndResultReconciles() {
        // 별도 Dialog window도 실제 200% 리소스로 만들어야 한다. 렌더 후 구성 변경으로 Activity를 재생성하지 않는다.
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(if (large) 2f else 1f)) {
                    MonitTheme(darkTheme = large) {
                        val view = LocalView.current

                        SideEffect { rootView = view }

                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("import-workflow-root"),
                            color = MonitTheme.colors.surfaceContainer,
                            contentColor = MonitTheme.colors.onSurface,
                        ) {
                            ImportPreviewRoute(
                                context = WorkflowImports.context,
                                onBack = {},
                                backupContent = { _, verified ->
                                    MonitButton(
                                        text = "합성 백업 완료 callback",
                                        onClick = { verified(WorkflowImports.id(700)) },
                                    )
                                },
                                viewModel = model,
                            )
                        }
                    }
                }
            }
        }
        await(ImportPreviewPhase.EMPTY)
        compose.runOnIdle { model.select(Uri.parse("content://synthetic-import/workflow")) }
        await(ImportPreviewPhase.READY)
        assertEquals(0, repository.applies)
        capture("review-light")
        scrollTo("반영 전 현재 장부 백업")
        compose.onNodeWithText("반영 전 현재 장부 백업").performClick()
        compose.onNodeWithText("합성 백업 완료 callback").performClick()
        await(ImportPreviewPhase.READY)
        assertEquals(0, repository.applies)
        scrollTo("최종 영향 확인 후 반영")
        compose.runOnIdle {
            large = true
        }
        compose.waitForIdle()
        scrollTo("최종 영향 확인 후 반영")
        compose.onNodeWithText("최종 영향 확인 후 반영").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val dialog = checkNotNull(ShadowDialog.getLatestDialog())
            assertEquals(320, dialog.context.resources.configuration.screenWidthDp)
            assertEquals(2f, dialog.context.resources.configuration.fontScale)
        }
        compose.onNodeWithText("검토한 집합을 반영할까요?").assertIsDisplayed()
        compose.onNodeWithText("실제 생성 예정 거래 2건").assertIsDisplayed()
        compose.onNodeWithText("검토한 집합 반영").assertIsDisplayed()
        assertEquals(0, repository.applies)
        capture("confirm-dark-large", dialog = true)
        compose.onNodeWithText("검토한 집합 반영").performClick()
        await(ImportPreviewPhase.RESULT)
        assertEquals(1, repository.applies)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("원본 6 = 반영 3 + 기반영 1 + 보류 1 + 제외 1", substring = true))
        compose.onNode(hasText("실제 생성 거래 2건", substring = true)).assertExists()
        compose.onNode(hasText("전체 원본을 모두 이관했다는 뜻은 아니에요.", substring = true)).assertExists()
        capture("result-dark-large")
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
    private fun capture(name: String, dialog: Boolean = false) {
        val bounds = compose.onNodeWithTag("import-workflow-root").fetchSemanticsNode().boundsInRoot
        val view = if (dialog) checkNotNull(ShadowDialog.getLatestDialog()).window!!.decorView else rootView
        val bitmap = Bitmap.createBitmap(if (dialog) view.width else bounds.width.toInt(), if (dialog) view.height else bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            Canvas(bitmap).also {
                if (!dialog) it.translate(-bounds.left, -bounds.top)
                view.draw(it)
            }
        }
        val directory = File("build/reports/import-writer/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}

private class WorkflowImports : ImportRepository {
    var applies = 0
    private var latest: ImportReview? = null
    override suspend fun review(context: LedgerContext, source: ByteArray, options: ImportReviewOptions): ImportReview {
        val sourceRows = (0..5).map { ImportSourceRow("synthetic", "transaction", "S$it", "rows/$it", "{}", "a".repeat(64), true, "wallet") }
        val input = ImportPreviewInput("a".repeat(64), "b".repeat(64), "c".repeat(64), "2026-11-13T00:00:00Z", "합성 UI 경계 · 실제 원문 없음", emptyList(), sourceRows, "test", "test", context, emptyList(), emptyList(), emptyList())
        val rows = sourceRows.mapIndexed { index, row ->
            ImportRowAssessment(
                row,
                when (index) {
                    0, 1, 2 -> ImportDecision.CANDIDATE
                    3 -> ImportDecision.EXISTS
                    4 -> ImportDecision.HELD
                    else -> ImportDecision.EXCLUDED
                },
                emptySet(),
                "합성 원본 $index",
                "1",
                "지출",
                "KRW",
                null,
            )
        }
        val report = ImportPreviewReport(input, context, 4, rows, emptyList(), emptyMap(), emptyMap(), "d".repeat(64), 2)
        return ImportReview("workflow", id(800), report, ImportReviewOptions(targetConfirmed = true), emptyList(), emptyList(), true).also { latest = it }
    }
    override suspend fun revise(context: LedgerContext, token: String, options: ImportReviewOptions) = review(context, byteArrayOf(), options)
    override suspend fun bindBackup(context: LedgerContext, token: String, exportId: String) = checkNotNull(latest).copy(token = "backed", verifiedBackupId = exportId).also { latest = it }
    override suspend fun checkSource(context: LedgerContext, token: String, readBack: ByteArray) = Unit
    override suspend fun apply(context: LedgerContext, token: String, onStage: (ImportStage) -> Unit): ImportResult {
        applies++
        return ImportResult(id(800), context, "2026-11-13T00:00:00Z", 5, 6, 3, 1, 1, 1, 2, 0, true, id(900))
    }
    override suspend fun attempt(context: LedgerContext, batchId: String): ImportAttempt = ImportAttempt.Missing
    override suspend fun pending(context: LedgerContext): List<ImportAttempt> = emptyList()
    override suspend fun acknowledge(context: LedgerContext, batchId: String) = Unit
    override fun discard(token: String) = Unit
    companion object {
        fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
        val context = LedgerContext(id(1), id(2))
    }
}
