package com.monit.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 내부 고정 라벨. 편집 문자열·커서·IME 검증은 소유자가 관리하며 금액을 강제 변환하지 않는다. */
@Composable
fun MonitAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.monit_amount_label),
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focused by interactionSource.collectIsFocusedAsState()
    MonitAmountFieldContent(
        value = value, onValueChange = onValueChange, modifier = modifier, label = label,
        errorMessage = errorMessage, supportingText = supportingText, enabled = enabled, loading = loading,
        keyboardActions = keyboardActions, interactionSource = interactionSource, focused = focused,
    )
}

/** 입력과 정적 상태 Preview의 공통 본문. 편집·IME·접근성 계약도 같은 경로를 사용한다. */
@Composable
internal fun MonitAmountFieldContent(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.monit_amount_label),
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    focused: Boolean = false,
) {
    val colors = MonitTheme.colors
    val active = enabled && !loading
    val saving = stringResource(R.string.monit_saving)
    val borderColor = when {
        !active -> colors.disabledBackground
        errorMessage != null -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    val borderWidth = if (active && (focused || errorMessage != null)) MonitTheme.sizes.focusOutline else MonitTheme.sizes.outline
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        if (!active) disabled()
        if (loading) stateDescription = saving
    }, verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
        Surface(
            shape = RoundedCornerShape(MonitTheme.sizes.fieldRadius),
            color = if (active) colors.surfaceContainer else colors.surface,
            border = BorderStroke(borderWidth, borderColor),
        ) {
            BasicTextField(
                value, onValueChange, enabled = active, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (active) colors.onSurface else colors.disabledText),
                cursorBrush = SolidColor(if (errorMessage != null) colors.error else colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = keyboardActions, interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().heightIn(min = MonitTheme.sizes.inputMinHeight)
                    .padding(horizontal = MonitTheme.spacing.content, vertical = MonitTheme.spacing.inline)
                    .semantics {
                        contentDescription = label
                        errorMessage?.let { error(it) }
                        if (loading) stateDescription = saving
                    },
                decorationBox = { input ->
                    Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.compact)) {
                        Text(label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
                            color = if (errorMessage != null && active) colors.error else colors.muted)
                        input()
                    }
                },
            )
        }
        (errorMessage ?: supportingText)?.let {
            Text(it, color = if (errorMessage != null) colors.error else colors.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
