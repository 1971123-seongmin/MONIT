package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.monit.core.designsystem.theme.MonitTheme

/** 본문·행동 슬롯을 받으며 모달 focus·뒤로·바깥 클릭은 플랫폼에 맡긴다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    title: @Composable () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest, modifier = modifier, properties = properties) {
        MonitDialogContent(Modifier.padding(horizontal = MonitTheme.spacing.content).widthIn(max = 304.dp),
            title = title, actions = actions, content = content)
    }
}

/** 모달과 Preview가 공유하는 본문. 행동의 종류·개수·활성 여부를 가정하지 않는다. */
@Composable
internal fun MonitDialogContent(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, color = MonitTheme.colors.surfaceContainer, contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Column(Modifier.padding(MonitTheme.spacing.content), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            title()
            content()
            actions()
        }
    }
}
