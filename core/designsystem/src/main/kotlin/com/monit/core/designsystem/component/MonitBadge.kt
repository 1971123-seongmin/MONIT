package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.monit.core.designsystem.theme.MonitTheme

enum class MonitBadgeTone { Neutral, Selected, Error, Disabled }

/** 장식용 상태 배지다. 비활성도 별도의 텍스트로 전달하며 클릭 대상을 만들지 않는다. */
@Composable
fun MonitBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: MonitBadgeTone = MonitBadgeTone.Neutral
) {
    val colors = MonitTheme.colors
    Surface(
        modifier, shape = RoundedCornerShape(MonitTheme.sizes.controlRadius), color = when (tone) {
            MonitBadgeTone.Selected -> colors.primary
            MonitBadgeTone.Disabled -> colors.disabledBackground
            else -> colors.surface
        }
    ) {
        Text(
            text,
            Modifier.padding(MonitTheme.spacing.content),
            style = MaterialTheme.typography.labelSmall,
            color = when (tone) {
                MonitBadgeTone.Selected -> colors.onPrimary
                MonitBadgeTone.Error -> colors.error
                else -> colors.muted
            }
        )
    }
}
