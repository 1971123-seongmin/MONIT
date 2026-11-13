package com.monit.app.backup

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
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.home.backup.BackupPhase
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 승인 디자인의 앱 영역을 Native Canvas로 확인한다. OS 표시줄·실제 SAF·Keystore 시험과 구분한다. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h780dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exportPasswordLight() = render(BackupPhase.EXPORT_PASSWORD, false)

    @Test fun restorePasswordDark() = render(BackupPhase.RESTORE_PASSWORD, true)

    @Test fun replacementPreviewLight() = render(BackupPhase.PREVIEW, false)

    @Test fun exportedUnverifiedLight() = render(BackupPhase.EXPORTED_UNVERIFIED, false)

    @Test fun restoredNotificationFailureDark() = render(BackupPhase.RESULT, true)

    @Test fun replacementPreviewNarrowLargeText() = render(BackupPhase.PREVIEW, false, 320, 2f)

    private fun render(phase: BackupPhase, dark: Boolean, width: Int = 360, scale: Float = 1f) {
        lateinit var view: View
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 708.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    MonitTheme(dark) {
                        Surface(
                            color = MonitTheme.colors.surfaceContainer,
                            contentColor = MonitTheme.colors.onSurface,
                        ) {
                            val current = LocalView.current
                            SideEffect { view = current }

                            Box(Modifier.fillMaxSize().testTag("backup-render")) { TestBackupScreen(phase) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (phase == BackupPhase.PREVIEW) compose.onNodeWithText("현재 자료 전체를 교체").assertIsDisplayed()
        capture(view, "${phase.name.lowercase()}-${if (dark) "dark" else "light"}-$width-${scale.toInt()}")
        if (phase == BackupPhase.PREVIEW && scale > 1f) {
            compose.onNodeWithText("현재 자료의 복구용 사본을 확보한 뒤 교체해요. 위 영향을 확인했다면 아래 버튼을 눌러주세요.").performScrollTo().assertIsDisplayed()
            capture(view, "preview-large-bottom")
        }
    }

    private fun capture(view: View, name: String) {
        val bounds = compose.onNodeWithTag("backup-render").fetchSemanticsNode().boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
        }
        val directory = File("build/reports/backup/screenshots")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
