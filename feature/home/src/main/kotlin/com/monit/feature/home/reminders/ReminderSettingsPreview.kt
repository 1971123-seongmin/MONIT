package com.monit.feature.home.reminders

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.*

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderAllowedPreview() {
    ReminderPreview()
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderDisabledPreview() {
    ReminderPreview(enabled = false)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderPermissionDeniedPreview() {
    ReminderPreview(permission = false)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderAppBlockedPreview() {
    ReminderPreview(app = false)
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderChannelBlockedPreview() {
    ReminderPreview(channel = false)
}

@Preview(showBackground = true, widthDp = 320, heightDp = 780, fontScale = 2f)
@Composable
private fun ReminderDarkLargePreview() {
    ReminderPreview(
        dark = true,
        permission = false,
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ReminderReadFailurePreview() {
    ReminderPreview(phase = ReminderSettingsPhase.FAILED)
}

/** 고정 합성 표시 값만 사용하며 플랫폼·Repository·금융 쓰기를 실행하지 않는다. */
@Composable
private fun ReminderPreview(
    enabled: Boolean = true,
    permission: Boolean = true,
    app: Boolean = true,
    channel: Boolean = true,
    dark: Boolean = false,
    phase: ReminderSettingsPhase = ReminderSettingsPhase.READY,
) {
    val context = LedgerContext("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
    val ui = reminderSettingsProjection(
        config = ReminderConfiguration(
            context = context,
            enabled = enabled,
            revision = 1,
            dirty = false,
            failed = false,
            nextAt = null,
            rules = listOf(RuleReminderPreference("00000000-0000-0000-0000-000000000003", "합성 보험", true, 0, true)),
        ),
        access = ReminderAccess(permission, app, channel),
    )

    MonitTheme(darkTheme = dark) {
        ReminderSettingsScreen(
            state = ReminderSettingsState(phase, ui),
            onBack = {},
            onRetry = {},
            onEnabled = {},
            onRuleEnabled = { _, _ -> },
            onRequestPermission = {},
            onOpenSettings = {},
            onReschedule = {},
        )
    }
}
