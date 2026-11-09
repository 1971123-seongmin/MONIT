package com.monit.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 종류·부호가 포함된 정확한 금액을 호출자가 전달한다. 긴 내용은 생략하지 않고 행을 늘린다. */
@Composable
fun MonitTransactionRow(
    kindLabel: String,
    title: String,
    amountText: String,
    accountLabel: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text("$kindLabel · $title  $amountText", style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { Text(accountLabel, style = MaterialTheme.typography.bodyMedium) },
        modifier = modifier.heightIn(min = MonitTheme.sizes.rowMinHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {},
        colors = ListItemDefaults.colors(
            containerColor = MonitTheme.colors.surfaceContainer,
            headlineColor = MonitTheme.colors.onSurface, supportingColor = MonitTheme.colors.muted,
        ),
    )
}
