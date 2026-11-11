package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.monit.core.designsystem.R
import com.monit.core.designsystem.component.MonitBottomSheetContent
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonSize
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme

/** 합성 계좌 선택 조합 예시. 제품 화면에서는 feature가 선택·검증·적용 정책을 구현한다. */
@Composable
internal fun AccountSheetSample(
    accounts: List<AccountOptionSample>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onApply: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val selectable = accounts.singleOrNull { it.id == selectedId && it.disabledReason == null }
    val empty = accounts.isEmpty() && !loading && errorMessage == null

    MonitBottomSheetContent(
        modifier,
        title = {
            Text(
                stringResource(R.string.monit_account_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        actions = {
            if (!empty) MonitButton(
                stringResource(R.string.monit_apply_account),
                Modifier.fillMaxWidth(),
                enabled = selectable != null && errorMessage == null,
                loading = loading,
                loadingDescription = stringResource(R.string.monit_saving),
                onClick = { selectable?.let { onApply(it.id) } },
            )

            MonitButton(
                stringResource(R.string.monit_close),
                Modifier.fillMaxWidth(),
                enabled = !loading,
                type = MonitButtonType.Secondary,
                size = MonitButtonSize.Compact,
                onClick = onDismiss,
            )
        },
    ) {
        if (!empty && !loading && errorMessage == null && accounts.any { it.id == selectedId }) {
            Text(
                stringResource(R.string.monit_choose_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MonitTheme.colors.muted,
            )
        }

        AccountPickerSample(
            accounts,
            selectedId,
            onSelect,
            onAddAccount,
            loading = loading,
            errorMessage = errorMessage,
            onRetry = onRetry,
        )
    }
}
