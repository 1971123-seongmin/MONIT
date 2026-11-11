package com.monit.core.designsystem.component.textfield

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.R

/** 편집 값·검증·키보드 정책은 호출자가 관리하는 공통 입력. 문자열을 임의 변환하지 않는다. */
@Composable
fun MonitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    loadingDescription: String = stringResource(R.string.monit_loading),
    colors: MonitTextFieldColors = MonitTextFieldDefaults.colors(),
    layout: MonitTextFieldLayout = MonitTextFieldDefaults.layout(),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    MonitTextFieldContent(
        value = value, onValueChange = onValueChange, modifier = modifier, label = label,
        errorMessage = errorMessage, supportingText = supportingText, enabled = enabled, loading = loading,
        keyboardActions = keyboardActions, interactionSource = interactionSource, focused = focused,
        keyboardOptions = keyboardOptions, readOnly = readOnly, singleLine = singleLine, maxLines = maxLines,
        loadingDescription = loadingDescription, colors = colors, layout = layout,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, placeholder = placeholder,
    )
}

/** 입력과 정적 상태 Preview의 공통 본문. 편집·IME·접근성 계약도 같은 경로를 사용한다. */
@Composable
internal fun MonitTextFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    focused: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    loadingDescription: String = stringResource(R.string.monit_loading),
    colors: MonitTextFieldColors = MonitTextFieldDefaults.colors(),
    layout: MonitTextFieldLayout = MonitTextFieldDefaults.layout(),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val active = enabled && !loading
    val borderColor = when {
        !active -> colors.disabledOutline
        errorMessage != null -> colors.error
        focused -> colors.focusedOutline
        else -> colors.outline
    }
    val borderWidth = if (active && (focused || errorMessage != null)) layout.focusedOutlineWidth else layout.outlineWidth
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        if (!active) disabled()
    }, verticalArrangement = Arrangement.spacedBy(layout.supportingSpacing)) {
        Surface(
            shape = layout.shape,
            color = if (active) colors.container else colors.disabledContainer,
            border = BorderStroke(borderWidth, borderColor),
        ) {
            BasicTextField(
                value, onValueChange, enabled = active, readOnly = readOnly, singleLine = singleLine, maxLines = maxLines,
                textStyle = layout.textStyle.copy(color = if (active) colors.text else colors.disabledText),
                cursorBrush = SolidColor(if (errorMessage != null) colors.error else colors.focusedOutline),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions, interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().heightIn(min = layout.minHeight)
                    .padding(layout.contentPadding)
                    .semantics {
                        contentDescription = if (loading) "$label, $loadingDescription" else label
                        errorMessage?.let { error(it) }
                    },
                decorationBox = { input ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(layout.iconSpacing)) {
                        CompositionLocalProvider(LocalContentColor provides colors.label) { leadingIcon?.invoke() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(layout.labelSpacing)) {
                            Text(label, Modifier.clearAndSetSemantics {}, style = layout.labelStyle,
                                color = if (errorMessage != null && active) colors.error else colors.label)
                            Box {
                                if (value.isEmpty()) CompositionLocalProvider(LocalContentColor provides colors.label) { placeholder?.invoke() }
                                input()
                            }
                        }
                        CompositionLocalProvider(LocalContentColor provides colors.label) { trailingIcon?.invoke() }
                    }
                },
            )
        }
        (errorMessage ?: supportingText)?.let {
            Text(it, color = if (errorMessage != null) colors.error else colors.label, style = layout.supportingStyle)
        }
    }
}
