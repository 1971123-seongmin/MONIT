package com.monit.core.designsystem

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.preview.CatalogSection
import com.monit.core.designsystem.preview.MonitComponentCatalog
import com.monit.core.designsystem.theme.MonitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * 실제 Compose의 호스트 렌더를 시각 검토용 PNG로 남긴다. 픽셀 기준 자동 회귀 비교나 기기 렌더 성공을 뜻하지 않는다.
 * 조건별 4개 섹션은 재구성 후 idle을 기다려 캡처하며 한 테스트의 setContent를 반복 등록하지 않는다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h2800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitCatalogRenderTest {
    @get:Rule val compose = createComposeRule()

    /** Light의 기본 폭에서 버튼/입력/계좌/행·날짜 상태를 검토할 렌더를 만든다. */
    @Test fun rendersLightAt390dp() = render(false, 390, 1f)

    /** Dark의 기본 폭에서 semantic 색상과 같은 상태 구성을 검토할 렌더를 만든다. */
    @Test fun rendersDarkAt390dp() = render(true, 390, 1f)

    /** Light 320dp/200%에서 가변 높이·정확한 금액·오류/빈 상태를 검토할 렌더를 만든다. */
    @Test fun rendersLightAt320dpWithLargeText() = render(false, 320, 2f)

    /** Dark 320dp/200%에서 같은 확대 조건의 상태 누락과 대비를 검토할 렌더를 만든다. */
    @Test fun rendersDarkAt320dpWithLargeText() = render(true, 320, 2f)

    private fun render(dark: Boolean, width: Int, scale: Float) {
        var section by mutableStateOf(CatalogSection.Buttons)
        lateinit var view: View
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 2600.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    MonitTheme(dark) {
                        val currentView = LocalView.current
                        SideEffect { view = currentView }
                        Column(Modifier.fillMaxSize()) {
                            MonitComponentCatalog(section, Modifier.testTag("catalog"))
                        }
                    }
                }
            }
        }
        val directory = File(checkNotNull(System.getProperty("monit.screenshot.dir")))
        check(directory.isDirectory || directory.mkdirs())
        for (next in CatalogSection.entries) {
            compose.runOnIdle { section = next }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("catalog").fetchSemanticsNode().boundsInRoot
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            // API 28 호스트의 PixelCopy 비동기 콜백 대신 실제 View를 Native Canvas에 동기 렌더한다.
            compose.runOnIdle {
                val canvas = Canvas(bitmap)
                canvas.translate(-bounds.left, -bounds.top)
                view.draw(canvas)
            }
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            val output = File(directory, "${if (dark) "dark" else "light"}-$width-${scale.toInt()}-${next.name.lowercase()}.png")
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
