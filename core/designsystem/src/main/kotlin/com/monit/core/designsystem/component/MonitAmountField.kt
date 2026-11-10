package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/**
 * 편집 문자열을 그대로 전달한다. 빈칸·단독 '-'·붙여넣기를 0원으로 바꾸거나 숫자로 확정하지 않는다.
 * 오류 공개 시점과 L1 검증·IME 제출·첫 오류 포커스는 호출자가 담당한다. 천 단위 변환으로 커서를 이동시키지 않는다.
 */
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
    val colors = MonitTheme.colors
    val saving = stringResource(R.string.monit_saving)
    val supporting = errorMessage ?: supportingText
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = MonitTheme.sizes.inputMinHeight).semantics {
            if (errorMessage != null) error(errorMessage)
            if (loading) stateDescription = saving
        },
        enabled = enabled && !loading, isError = errorMessage != null,
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        suffix = { Text(stringResource(R.string.monit_currency_won), style = MaterialTheme.typography.bodyLarge) },
        supportingText = supporting?.let { message -> {
            Text(message, style = MaterialTheme.typography.labelSmall)
        } },
        singleLine = true,
        shape = RoundedCornerShape(MonitTheme.sizes.fieldRadius),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface,
            disabledTextColor = colors.disabledText, errorTextColor = colors.onSurface,
            focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer,
            disabledContainerColor = colors.disabledBackground, errorContainerColor = colors.surfaceContainer,
            cursorColor = colors.primary, errorCursorColor = colors.error,
            focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
            disabledBorderColor = colors.disabledBackground, errorBorderColor = colors.error,
            focusedLabelColor = colors.primary, unfocusedLabelColor = colors.muted,
            disabledLabelColor = colors.disabledText, errorLabelColor = colors.error,
            focusedSuffixColor = colors.muted, unfocusedSuffixColor = colors.muted,
            disabledSuffixColor = colors.disabledText, errorSuffixColor = colors.error,
            focusedSupportingTextColor = colors.muted, unfocusedSupportingTextColor = colors.muted,
            disabledSupportingTextColor = colors.disabledText, errorSupportingTextColor = colors.error,
        ),
    )
}
