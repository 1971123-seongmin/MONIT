package com.monit.feature.home.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator
import kotlinx.serialization.Serializable

@Serializable
data object BackupDestination

fun Navigator.navigateToBackup(source: NavBackStackEntry): NavigationResult = navigate(source, BackupDestination)

fun NavGraphBuilder.backupScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<BackupDestination> { content(it) }
}
