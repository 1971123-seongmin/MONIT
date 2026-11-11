package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

enum class MonitNavigationAction { Back, Close }

@Composable
fun MonitTopBar(title: String, modifier: Modifier = Modifier, navigationAction: MonitNavigationAction? = null,
    onNavigate: () -> Unit = {}) {
    Surface(modifier, color = MonitTheme.colors.surfaceContainer, contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
        Row(Modifier.padding(MonitTheme.spacing.content), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            navigationAction?.let {
                IconButton(onNavigate, Modifier.size(MonitTheme.sizes.touchTarget)) {
                    Icon(if (it == MonitNavigationAction.Back) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                        contentDescription = stringResource(if (it == MonitNavigationAction.Back) R.string.monit_back else R.string.monit_close),
                        modifier = Modifier.size(24.dp))
                }
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
    }
}
