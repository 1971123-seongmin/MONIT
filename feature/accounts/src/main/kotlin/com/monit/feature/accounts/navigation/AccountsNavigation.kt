package com.monit.feature.accounts.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator

fun Navigator.navigateToAccountDetail(source: NavBackStackEntry, destination: AccountDetailDestination): NavigationResult = navigate(source, destination)

fun NavGraphBuilder.accountDetailScreen(content: @Composable (NavBackStackEntry, AccountDetailDestination) -> Unit) {
    composable<AccountDetailDestination> { content(it, it.toRoute()) }
}

fun Navigator.navigateToAccounts(
    source: NavBackStackEntry,
    options: NavOptionsBuilder.() -> Unit = {},
): NavigationResult = navigate(source, AccountsDestination, options)

fun Navigator.navigateToAccountRegistration(
    source: NavBackStackEntry,
    destination: AccountRegistrationDestination,
): NavigationResult = navigate(source, destination)

fun NavGraphBuilder.accountsScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<AccountsDestination> { content(it) }
}

fun NavGraphBuilder.accountRegistrationScreen(
    content: @Composable (NavBackStackEntry, AccountRegistrationDestination) -> Unit,
) {
    composable<AccountRegistrationDestination> { content(it, it.toRoute()) }
}

fun Navigator.navigateToImportPreview(source: NavBackStackEntry): NavigationResult = navigate(source, ImportPreviewDestination)

fun NavGraphBuilder.importPreviewScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<ImportPreviewDestination> { content(it) }
}
