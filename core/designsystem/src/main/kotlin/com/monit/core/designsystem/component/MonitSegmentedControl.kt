package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 2~4개 상호 배타 선택. 목적지 전환은 BottomNavigation에서 구분한다. */
@Composable
fun MonitSegmentedControl(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    require(labels.size in 2..4 && selectedIndex in labels.indices)
    val colors = MonitTheme.colors
    Surface(modifier, color = colors.surface, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Row(Modifier.padding(MonitTheme.spacing.compact).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            labels.forEachIndexed { index, label ->
                Surface(selected = index == selectedIndex, onClick = { onSelected(index) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = MonitTheme.sizes.touchTarget)
                        .semantics { role = Role.RadioButton },
                    shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
                    color = if (index == selectedIndex) colors.surfaceContainer else colors.surface,
                    contentColor = when { !enabled -> colors.disabledText; index == selectedIndex -> colors.primary; else -> colors.muted }) {
                    Box(Modifier.padding(MonitTheme.spacing.inline), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
