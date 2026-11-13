package com.monit.feature.home.reminders

enum class ReminderSettingsPhase { LOADING, READY, SAVING, FAILED }
data class ReminderSettingsState(val phase: ReminderSettingsPhase = ReminderSettingsPhase.LOADING, val ui: ReminderSettingsUiState = ReminderSettingsUiState())
