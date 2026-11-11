package com.monit.core.designsystem

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.preview.AmountFieldSample
import com.monit.core.designsystem.theme.MonitTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/** 공개 API의 실제 interaction 수집이 공유 본문의 픽셀에 반영되는지 검사한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitRuntimeVisualStateTest {
    @get:Rule val compose = createComposeRule()

    /**
     * 목적: 정적 Preview 본문 분리 후에도 공개 컴포넌트가 입력 상태 변화를 반영한다.
     * 조건: Light 테마의 실제 버튼·필드에 독립 interaction source를 전달한다.
     * 절차: 구독 준비 뒤 누름/포커스와 해제 이벤트를 보내고 Native Canvas의 배경·테두리를 읽는다.
     * 기대: 버튼은 primary→pressed→primary, 필드는 outline→primary→outline으로 복귀한다.
     */
    @Test fun publicComponentsReflectAndReleaseRuntimeInteractions() {
        val buttonSource = MutableInteractionSource()
        val fieldSource = MutableInteractionSource()
        lateinit var view: View
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            MonitTheme(false) {
                Column(Modifier.padding(16.dp)) {
                    MonitButton("계속하기", Modifier.fillMaxWidth().testTag("button"), interactionSource = buttonSource) {}
                    AmountFieldSample("30000", {}, Modifier.testTag("field"), interactionSource = fieldSource)
                }
            }
        }

        fun pixel(tag: String, x: Int): Int {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            return try {
                compose.runOnIdle { view.draw(Canvas(bitmap)) }
                bitmap.getPixel(bounds.left.toInt() + x, bounds.center.y.toInt())
            } finally {
                bitmap.recycle()
            }
        }

        assertEquals(0xFF2563EB.toInt(), pixel("button", 16))
        assertEquals(0xFF5B6573.toInt(), pixel("field", 0))
        val press = PressInteraction.Press(Offset.Zero)
        val focus = FocusInteraction.Focus()
        compose.runOnIdle {
            assertTrue(buttonSource.tryEmit(press))
            assertTrue(fieldSource.tryEmit(focus))
        }
        assertEquals(0xFF1D4ED8.toInt(), pixel("button", 16))
        assertEquals(0xFF2563EB.toInt(), pixel("field", 0))
        compose.runOnIdle {
            assertTrue(buttonSource.tryEmit(PressInteraction.Release(press)))
            assertTrue(fieldSource.tryEmit(FocusInteraction.Unfocus(focus)))
        }
        assertEquals(0xFF2563EB.toInt(), pixel("button", 16))
        assertEquals(0xFF5B6573.toInt(), pixel("field", 0))
    }
}
