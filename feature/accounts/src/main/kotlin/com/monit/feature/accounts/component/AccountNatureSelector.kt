package com.monit.feature.accounts.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.AccountNature

/** 등록 초안의 값만 표시한다. 저장·초안·호출 화면 처리는 기존 VM에 맡긴다. */
@Composable
internal fun AccountNatureSelector(
    value: AccountNature,
    enabled: Boolean,
    onSelect: (AccountNature) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.selectableGroup(),
    ) {
        Text(
            text = "계좌 성격",
            style = MaterialTheme.typography.labelLarge,
        )

        for (nature in AccountNature.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MonitTheme.sizes.touchTarget)
                    .selectable(
                        selected = value == nature,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(nature) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
            ) {
                RadioButton(
                    selected = value == nature,
                    onClick = null,
                    enabled = enabled,
                )

                Text(
                    text = when (nature) {
                        AccountNature.GENERAL -> "일반"
                        AccountNature.SAVINGS -> "저축"
                        AccountNature.INVESTMENT -> "투자"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
