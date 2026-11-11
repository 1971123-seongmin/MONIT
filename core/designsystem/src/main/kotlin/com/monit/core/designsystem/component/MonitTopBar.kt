package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.monit.core.designsystem.theme.MonitTheme

/** 탐색 아이콘과 우측 행동을 호출자가 조합하며 특정 화면의 navigation을 알지 못한다. */
@Composable
fun MonitTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    color: Color = MonitTheme.colors.surfaceContainer,
    contentColor: Color = MonitTheme.colors.onSurface,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier, color = color, contentColor = contentColor,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)
    ) {
        Row(
            Modifier.padding(MonitTheme.spacing.content),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)
        ) {
            navigationIcon?.invoke()
            Text(title, Modifier.weight(1f).semantics { heading() }, style = titleStyle)
            actions()
        }
    }
}
