package com.monit.feature.transactions.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator

fun Navigator.navigateToTransactions(
    source: NavBackStackEntry,
    options: NavOptionsBuilder.() -> Unit = {},
): NavigationResult = navigate(source, TransactionsDestination, options)

fun Navigator.navigateToTransactionEntry(
    source: NavBackStackEntry,
    destination: TransactionEntryDestination,
): NavigationResult = navigate(source, destination)

fun NavGraphBuilder.transactionsScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<TransactionsDestination> { content(it) }
}

fun NavGraphBuilder.transactionEntryScreen(
    content: @Composable (NavBackStackEntry, TransactionEntryDestination) -> Unit,
) {
    composable<TransactionEntryDestination> { content(it, it.toRoute()) }
}
