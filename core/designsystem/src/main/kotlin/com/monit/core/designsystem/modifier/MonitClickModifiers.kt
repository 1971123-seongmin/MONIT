package com.monit.core.designsystem.modifier

import android.os.SystemClock
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** 피드백을 직접 제공하는 대상에만 적용한다. 클릭의 focus·keyboard·semantics는 foundation에 맡긴다. */
fun Modifier.clickableWithoutRipple(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interactionSource, indication = null,
    enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick
)

/**
 * 첫 클릭은 즉시 전달하고 수락한 클릭부터 간격을 센다. 억제된 클릭은 간격을 늘리지 않는다.
 * 각 composition 인스턴스가 독립하며 최신 callback을 호출한다. M3 Button에 중첩하지 않는다.
 * loading 잠금·Repository의 operationId 중복 방지를 대신하지 않는다.
 */
@Composable
fun Modifier.clickableWithCooldown(
    cooldownMillis: Long = 600,
    enabled: Boolean = true,
    withoutRipple: Boolean = false,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    require(cooldownMillis >= 0)
    val gate = remember { ClickCooldown() }
    val latestAction by rememberUpdatedState(onClick)
    return clickable(
        interactionSource = interactionSource,
        indication = if (withoutRipple) null else LocalIndication.current,
        enabled = enabled, onClickLabel = onClickLabel, role = role
    ) {
        if (gate.accept(SystemClock.uptimeMillis(), cooldownMillis)) latestAction()
    }
}

internal class ClickCooldown {
    private var lastAccepted: Long? = null
    fun accept(now: Long, interval: Long): Boolean {
        if (lastAccepted?.let { now - it < interval } == true) return false
        lastAccepted = now
        return true
    }
}
