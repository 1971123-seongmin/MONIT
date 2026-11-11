package com.monit.core.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** Loading은 입력을 잠그고 상태를 알린다. 외부 포커스 링을 위해 주변 4dp를 clip하지 않는다. */
@Composable
fun MonitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    loadingText: String = stringResource(R.string.monit_loading),
    loadingDescription: String = stringResource(R.string.monit_saving),
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    MonitButtonContent(
        text = text, onClick = onClick, modifier = modifier, enabled = enabled, loading = loading,
        interactionSource = interactionSource, loadingText = loadingText, loadingDescription = loadingDescription,
        pressed = pressed, focused = focused,
    )
}

/** 실제 버튼과 정적 상태 Preview가 공유하는 시각 본문. 입력 상태 수집은 공개 컴포넌트가 담당한다. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MonitButtonContent(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    loadingText: String = stringResource(R.string.monit_loading),
    loadingDescription: String = stringResource(R.string.monit_saving),
    pressed: Boolean = false,
    focused: Boolean = false,
) {
    val colors = MonitTheme.colors
    val sizes = MonitTheme.sizes
    val primary = colors.primary
    // MONIT 누름 색상과 포커스 링을 직접 적용하므로 M3 state layer를 중첩하지 않는다.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(
            onClick = onClick, enabled = enabled && !loading, interactionSource = interactionSource,
            modifier = modifier.heightIn(min = sizes.touchTarget)
                .drawBehind {
                    if (focused && enabled && !loading) {
                        val stroke = sizes.focusOutline.toPx()
                        val offset = sizes.focusGap.toPx() + stroke / 2
                        drawRoundRect(
                            color = primary, topLeft = Offset(-offset, -offset),
                            size = Size(size.width + offset * 2, size.height + offset * 2),
                            cornerRadius = CornerRadius(sizes.controlRadius.toPx() + offset), style = Stroke(stroke),
                        )
                    }
                }
                .semantics {
                    if (loading) {
                        stateDescription = loadingDescription
                        liveRegion = LiveRegionMode.Polite
                    }
                },
            shape = RoundedCornerShape(sizes.controlRadius),
            contentPadding = PaddingValues(horizontal = MonitTheme.spacing.section, vertical = sizes.controlRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (pressed) colors.pressed else colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor = if (loading && enabled) colors.primary else colors.disabledBackground,
                disabledContentColor = if (loading && enabled) colors.onPrimary else colors.disabledText,
            ),
        ) {
            Text(if (loading) loadingText else text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
