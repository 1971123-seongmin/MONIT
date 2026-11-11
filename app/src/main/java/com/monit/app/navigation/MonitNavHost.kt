package com.monit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.accountRegistrationScreen
import com.monit.feature.accounts.navigation.accountsScreen
import com.monit.feature.home.navigation.HomeDestination
import com.monit.feature.home.navigation.homeScreen
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import com.monit.feature.transactions.navigation.transactionEntryScreen
import com.monit.feature.transactions.navigation.transactionsScreen

/** 제품 내용은 필수 인자다. 탐색 기반을 위해 가짜/빈 기본 화면을 제공하지 않는다. */
@Composable
fun MonitNavHost(
    controller: NavHostController,
    home: @Composable (NavBackStackEntry) -> Unit,
    transactions: @Composable (NavBackStackEntry) -> Unit,
    accounts: @Composable (NavBackStackEntry) -> Unit,
    transactionEntry: @Composable (NavBackStackEntry, TransactionEntryDestination) -> Unit,
    accountRegistration: @Composable (NavBackStackEntry, AccountRegistrationDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(controller, startDestination = HomeDestination, modifier = modifier) {
        homeScreen(home)
        transactionsScreen(transactions)
        accountsScreen(accounts)
        transactionEntryScreen(transactionEntry)
        accountRegistrationScreen(accountRegistration)
    }
}
