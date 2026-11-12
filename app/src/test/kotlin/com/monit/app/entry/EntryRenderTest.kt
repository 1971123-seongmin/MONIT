package com.monit.app.entry

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.monit.core.common.draft.DraftEditorPhase
import com.monit.core.common.draft.DraftEditorState
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.TransactionKind
import com.monit.feature.accounts.*
import com.monit.feature.transactions.entry.*
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 360×708 앱 영역의 합성 호스트 렌더. 시스템 바·IME·기기 성능 검증은 아니다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expenseLight() = render("expense", false)

    @Test fun incomeDark() = render("income", true)

    @Test fun transferLight() = render("transfer", false)

    @Test fun accountLight() = render("account", false)

    @Test fun accountDark() = render("account", true)

    @Test fun savedLight() = render("saved", false)

    @Test fun errorDark() = render("error", true)

    @Test fun narrowLargeText() = render("expense", false, 320, 2f)

    @Test fun draftSavePendingLight() = render("draft-pending", false)

    @Test fun draftSaveFailureDarkLargeText() = render("draft-failed", true, 320, 2f)

    private fun render(screen: String, dark: Boolean, width: Int = 360, font: Float = 1f) {
        lateinit var view: View
        val kind = when (screen) {
            "income" -> TransactionKind.INCOME
            "transfer" -> TransactionKind.TRANSFER
            else -> TransactionKind.EXPENSE
        }
        val ui = TransactionEntryUiState(
            kind,
            if (screen == "error") "0" else "20000",
            "2026-11-03",
            "합성 점심",
            "one",
            "two",
            listOf(EntryAccountUiState("one", "합성 생활비"), EntryAccountUiState("two", "합성 저축")),
        )
        val state = TransactionEntryState(
            ui,
            if (screen == "saved") EntryPhase.SAVED else EntryPhase.EDITING,
            inputError = if (screen == "error") TransactionInputError.AMOUNT else null,
            draftPersistence = if (screen.startsWith("draft-")) DraftEditorState(
                DraftEditorPhase.EDITING,
                dirty = true,
                saving = screen == "draft-pending",
                failed = screen == "draft-failed",
            ) else null,
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(font)) {
                    MonitTheme(dark) {
                        val current = LocalView.current
                        SideEffect { view = current }
                        Surface(color = MonitTheme.colors.surfaceContainer, contentColor = MonitTheme.colors.onSurface) {
                            Box(Modifier.fillMaxSize().testTag("entry-render")) {
                                if (screen == "account") AccountRegistrationScreen(AccountRegistrationState(AccountDraftUiState(name = "합성 생활비", anchorDate = "2026-11-02"), AccountSaveState.Editing), {}, {}, {})
                                else TransactionEntryScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (screen == "saved") compose.onNodeWithText("목록 보기").assertExists()
        else if (screen == "account") compose.onNodeWithText("계좌 저장").assertExists()
        else compose.onNodeWithContentDescription("금액 (원)").assertExists()
        val bounds = compose.onNodeWithTag("entry-render").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
        }
        val directory = File("build/reports/entry/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$screen-${if (dark) "dark" else "light"}-$width-$font.png").outputStream()
            .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
