package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.monit.core.designsystem.theme.MonitTheme

/** 목적지·선택·아이콘을 전달받는다. 실제 navigation과 화면별 항목 구성은 소유자가 처리한다. */
@Composable
fun MonitBottomNavigation(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: (@Composable (index: Int) -> Unit)? = null) {
    require(labels.size in 2..5) { "labels.size must be in 2..5; was ${labels.size}" }
    require(selectedIndex in labels.indices) { "selectedIndex must be in ${labels.indices}; was $selectedIndex" }
    val colors = MonitTheme.colors
    Surface(modifier, color = colors.surfaceContainer, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Row(Modifier.padding(vertical = MonitTheme.spacing.inline).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            labels.forEachIndexed { index, label ->
                Surface(selected = index == selectedIndex, onClick = { onSelected(index) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = MonitTheme.sizes.touchTarget).semantics { role = Role.Tab },
                    shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
                    color = if (index == selectedIndex) colors.surface else colors.surfaceContainer,
                    contentColor = if (index == selectedIndex) colors.primary else colors.muted) {
                    Column(Modifier.padding(MonitTheme.spacing.inline), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.compact, Alignment.CenterVertically)) {
                        icon?.invoke(index)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
