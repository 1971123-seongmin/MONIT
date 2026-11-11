package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.monit.core.designsystem.theme.MonitTheme

/** 카드·상태·피드백 원본이 공유하는 가변 높이 컨테이너. */
@Composable
internal fun MonitPanel(
    modifier: Modifier = Modifier,
    color: Color = MonitTheme.colors.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, color = color, contentColor = MonitTheme.colors.onSurface, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Column(Modifier.padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline), content = content)
    }
}
