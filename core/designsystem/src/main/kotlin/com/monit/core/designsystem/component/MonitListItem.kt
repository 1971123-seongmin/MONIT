package com.monit.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 행의 텍스트·앞뒤 콘텐츠를 슬롯으로 받는다. 항목 선택과 데이터 변환은 호출자가 담당한다. */
@Composable
fun MonitListItem(
    headline: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    detailContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        color = MonitTheme.colors.surfaceContainer,
        contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    ) {
        Row(
            Modifier
                .then(
                    if (onClick != null) Modifier.clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    ) else Modifier,
                )
                .padding(MonitTheme.spacing.content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            leadingContent?.invoke()

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelLarge,
                    headline,
                )

                supportingContent?.let {
                    ProvideTextStyle(
                        MaterialTheme.typography.bodyLarge,
                        it,
                    )
                }

                detailContent?.let {
                    CompositionLocalProvider(LocalContentColor provides MonitTheme.colors.muted) {
                        ProvideTextStyle(
                            MaterialTheme.typography.labelSmall,
                            it,
                        )
                    }
                }
            }

            trailingContent?.let {
                Box(contentAlignment = Alignment.Center) { it() }
            }
        }
    }
}
