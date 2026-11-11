package com.monit.core.designsystem.preview

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.monit.core.designsystem.R
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.component.textfield.MonitTextFieldContent

/** 금액 라벨·Decimal/Done 조합 예시. 공통 입력은 금액·통화 정책을 알지 못한다. */
@Composable
internal fun AmountFieldSample(
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
    MonitTextField(
        value,
        onValueChange,
        modifier,
        label,
        errorMessage,
        supportingText,
        enabled,
        loading,
        keyboardActions,
        interactionSource,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        loadingDescription = stringResource(R.string.monit_saving),
    )
}

@Composable
internal fun AmountFieldContentSample(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.monit_amount_label),
    errorMessage: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    focused: Boolean = false,
) {
    MonitTextFieldContent(
        value,
        onValueChange,
        modifier,
        label,
        errorMessage,
        supportingText,
        enabled,
        loading,
        focused = focused,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        loadingDescription = stringResource(R.string.monit_saving),
    )
}
