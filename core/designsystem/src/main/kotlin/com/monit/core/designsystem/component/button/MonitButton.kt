package com.monit.core.designsystem.component.button

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.monit.core.designsystem.R

/** Loading은 입력을 잠그고 상태를 알린다. 외부 포커스 링을 위해 주변 4dp를 clip하지 않는다. */
@Composable
fun MonitButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    loadingText: String = stringResource(R.string.monit_loading),
    loadingDescription: String = stringResource(R.string.monit_loading),
    type: MonitButtonType = MonitButtonType.Primary,
    size: MonitButtonSize = MonitButtonSize.Regular,
    colors: MonitButtonColors = MonitButtonDefaults.colors(type),
    layout: MonitButtonLayout = MonitButtonDefaults.layout(size),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()

    MonitButtonContent(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        interactionSource = interactionSource,
        loadingText = loadingText,
        loadingDescription = loadingDescription,
        pressed = pressed,
        focused = focused,
        type = type,
        size = size,
        colors = colors,
        layout = layout,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
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
    loadingDescription: String = stringResource(R.string.monit_loading),
    pressed: Boolean = false,
    focused: Boolean = false,
    type: MonitButtonType = MonitButtonType.Primary,
    size: MonitButtonSize = MonitButtonSize.Regular,
    colors: MonitButtonColors = MonitButtonDefaults.colors(type),
    layout: MonitButtonLayout = MonitButtonDefaults.layout(size),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val primary = colors.focusOutline

// MONIT 누름 색상과 포커스 링을 직접 적용하므로 M3 state layer를 중첩하지 않는다.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            interactionSource = interactionSource,
            modifier = modifier
                .heightIn(min = layout.minHeight)
                .drawBehind {
                    if (focused && enabled && !loading) {
                        val stroke = layout.focusOutlineWidth.toPx()
                        val offset = layout.focusGap.toPx() + stroke / 2

                        drawRoundRect(
                            color = primary,
                            topLeft = Offset(
                                -offset,
                                -offset,
                            ),
                            size = Size(
                                this.size.width + offset * 2,
                                this.size.height + offset * 2,
                            ),
                            cornerRadius = CornerRadius(layout.cornerRadius.toPx() + offset),
                            style = Stroke(stroke),
                        )
                    }
                }
                .semantics {
                    if (loading) {
                        stateDescription = loadingDescription
                        liveRegion = LiveRegionMode.Polite
                    }
                },
            shape = RoundedCornerShape(layout.cornerRadius),
            contentPadding = layout.contentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (pressed) colors.pressedContainer else colors.container,
                contentColor = if (pressed) colors.pressedContent else colors.content,
                disabledContainerColor = if (loading && enabled) colors.container else colors.disabledContainer,
                disabledContentColor = if (loading && enabled) colors.content else colors.disabledContent,
            ),
        ) {
            Row(
                Modifier.width(IntrinsicSize.Max),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(layout.iconSpacing),
            ) {
                leadingIcon?.let {
                    Box(
                        Modifier
                            .size(layout.iconSize)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) { it() }
                }

                Text(
                    if (loading) loadingText else text,
                    Modifier.weight(
                        1f,
                        fill = false,
                    ),
                    style = layout.textStyle,
                    textAlign = TextAlign.Center,
                )

                trailingIcon?.let {
                    Box(
                        Modifier
                            .size(layout.iconSize)
                            .clearAndSetSemantics {},
                        contentAlignment = Alignment.Center,
                    ) { it() }
                }
            }
        }
    }
}
