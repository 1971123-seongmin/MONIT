package com.monit.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.monit.core.designsystem.theme.MonitTheme

/** 표시 내용과 배치를 호출자가 조합하는 공통 컨테이너. */
@Composable
fun MonitCard(
    modifier: Modifier = Modifier,
    color: Color = MonitTheme.colors.surfaceContainer,
    contentColor: Color = MonitTheme.colors.onSurface,
    shape: Shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    contentPadding: PaddingValues = PaddingValues(MonitTheme.spacing.content),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(MonitTheme.spacing.inline),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, color = color, contentColor = contentColor, shape = shape) {
        Column(Modifier.padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
    }
}

/** 선택 값·활성 여부·설명은 소유자가 전달하며 카드 내부에서 선택 정책을 판정하지 않는다. */
@Composable
fun MonitSelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    stateDescription: String? = null,
    color: Color = if (selected) MonitTheme.colors.surface else MonitTheme.colors.surfaceContainer,
    contentColor: Color = MonitTheme.colors.onSurface,
    border: BorderStroke? = if (selected) BorderStroke(MonitTheme.sizes.focusOutline, MonitTheme.colors.primary) else null,
    shape: Shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
    contentPadding: PaddingValues = PaddingValues(MonitTheme.spacing.content),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(selected = selected, onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            this.role = role
            stateDescription?.let { this.stateDescription = it }
        }, color = color, contentColor = contentColor, border = border, shape = shape) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline), content = content)
    }
}
