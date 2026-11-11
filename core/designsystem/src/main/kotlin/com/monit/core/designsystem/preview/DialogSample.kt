package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonSize
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.MonitDialogContent
import com.monit.core.designsystem.theme.MonitTheme

/** 버튼 의미·허용 여부는 화면 소유자가 조합하는 예시다. 공통 모달은 이를 판정하지 않는다. */
@Composable
internal fun DialogSample(
    title: String,
    description: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    MonitDialogContent(
        modifier, title = { Text(title, style = MaterialTheme.typography.labelLarge) },
        actions = {
            MonitButton(
                primaryLabel,
                Modifier.fillMaxWidth(),
                enabled = primaryEnabled,
                onClick = onPrimary
            )
            MonitButton(
                secondaryLabel, Modifier.fillMaxWidth(), type = MonitButtonType.Secondary,
                size = MonitButtonSize.Compact, onClick = onSecondary
            )
        }) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MonitTheme.colors.muted
        )
    }
}
