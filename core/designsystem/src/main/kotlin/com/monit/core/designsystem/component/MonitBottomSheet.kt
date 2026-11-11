package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.monit.core.designsystem.theme.MonitTheme

/** 닫기 허용 여부와 제목·행동·본문을 받는 공통 모달. 선택/적용 정책은 호출자에게 둔다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    title: @Composable () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val state = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true, confirmValueChange = { canDismiss || it != SheetValue.Hidden },
    )
    ModalBottomSheet(onDismissRequest = { if (canDismiss) onDismissRequest() }, sheetState = state,
        dragHandle = null, containerColor = MonitTheme.colors.surfaceContainer,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius), modifier = modifier) {
        MonitBottomSheetContent(title = title, actions = actions, scrollContent = scrollContent, content = content)
    }
}

/** 제목·행동은 고정하고 본문만 스크롤한다. Lazy 목록은 scrollContent=false로 직접 구성한다. */
@Composable
fun MonitBottomSheetContent(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .9f }
    Surface(modifier, color = MonitTheme.colors.surfaceContainer, contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Column(Modifier.heightIn(max = maxHeight).padding(MonitTheme.spacing.content),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            title()
            Column(Modifier.weight(1f, fill = false)
                .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline), content = content)
            actions()
        }
    }
}
