package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.monit.core.designsystem.theme.MonitTheme

/** 플랫폼 모달 focus·뒤로·바깥 클릭을 M3에 맡긴다. 후속 삭제 기능을 활성화하지 않는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitDialog(title: String, description: String, primaryLabel: String, secondaryLabel: String,
    onPrimary: () -> Unit, onSecondary: () -> Unit, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, primaryEnabled: Boolean = true) {
    BasicAlertDialog(onDismissRequest, modifier = modifier,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MonitDialogContent(title, description, primaryLabel, secondaryLabel, onPrimary, onSecondary,
            Modifier.padding(horizontal = MonitTheme.spacing.content).widthIn(max = 304.dp), primaryEnabled)
    }
}

/** Preview에서는 모달 창 대신 같은 본문을 렌더한다. */
@Composable
fun MonitDialogContent(title: String, description: String, primaryLabel: String, secondaryLabel: String,
    onPrimary: () -> Unit, onSecondary: () -> Unit, modifier: Modifier = Modifier, primaryEnabled: Boolean = true) {
    MonitPanel(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MonitTheme.colors.muted)
        MonitButton(primaryLabel, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
        MonitSecondaryButton(secondaryLabel, onSecondary, Modifier.fillMaxWidth())
    }
}

/** 별도 ActionButton variant를 만들지 않고 기존 M3 보조 역할을 쓴다. */
@Composable
internal fun MonitSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = MonitTheme.sizes.touchTarget), enabled = enabled, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
        colors = ButtonDefaults.textButtonColors(containerColor = MonitTheme.colors.surface, contentColor = MonitTheme.colors.primary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
