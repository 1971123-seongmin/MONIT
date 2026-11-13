package com.monit.feature.home.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator
import kotlinx.serialization.Serializable

@Serializable
data object ReminderSettingsDestination

fun Navigator.navigateToReminderSettings(source: NavBackStackEntry): NavigationResult = navigate(source, ReminderSettingsDestination)

fun NavGraphBuilder.reminderSettingsScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<ReminderSettingsDestination> { content(it) }
}
