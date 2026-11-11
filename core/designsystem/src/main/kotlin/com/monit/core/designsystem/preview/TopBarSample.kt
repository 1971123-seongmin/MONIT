package com.monit.core.designsystem.preview

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.monit.core.designsystem.component.MonitTopBar
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

internal enum class NavigationActionSample { Back, Close }

@Composable
internal fun TopBarSample(
    title: String,
    modifier: Modifier = Modifier,
    action: NavigationActionSample? = null
) {
    MonitTopBar(title, modifier, navigationIcon = action?.let { navigation ->
        {
            IconButton({}, Modifier.size(MonitTheme.sizes.touchTarget)) {
                Icon(
                    if (navigation == NavigationActionSample.Back) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                    contentDescription = stringResource(if (navigation == NavigationActionSample.Back) R.string.monit_back else R.string.monit_close),
                    modifier = Modifier.size(MonitTheme.sizes.icon)
                )
            }
        }
    })
}
