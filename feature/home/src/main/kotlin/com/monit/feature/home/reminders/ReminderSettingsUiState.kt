package com.monit.feature.home.reminders

data class ReminderSettingsUiState(
    val enabled: Boolean = false,
    val allowed: Boolean = false,
    val status: String = "",
    val explanation: String = "",
    val permission: String = "",
    val app: String = "",
    val channel: String = "",
    val permissionDenied: Boolean = false,
    val appBlocked: Boolean = false,
    val channelBlocked: Boolean = false,
    val dirty: Boolean = false,
    val failed: Boolean = false,
    val next: String = "",
    val rules: List<ReminderRuleUiState> = emptyList(),
    val message: String? = null,
)

data class ReminderRuleUiState(val id: String, val name: String, val enabled: Boolean, val preference: String)
