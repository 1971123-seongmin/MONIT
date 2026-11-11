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

/** 루트 내용은 필수다. 후속 입력/등록은 실제 화면을 전달한 경우에만 graph에 추가한다. */
@Composable
fun MonitNavHost(
    controller: NavHostController,
    home: @Composable (NavBackStackEntry) -> Unit,
    transactions: @Composable (NavBackStackEntry) -> Unit,
    accounts: @Composable (NavBackStackEntry) -> Unit,
    modifier: Modifier = Modifier,
    transactionEntry: (@Composable (NavBackStackEntry, TransactionEntryDestination) -> Unit)? = null,
    accountRegistration: (@Composable (NavBackStackEntry, AccountRegistrationDestination) -> Unit)? = null,
) {
    NavHost(controller, startDestination = HomeDestination, modifier = modifier) {
        homeScreen(home)
        transactionsScreen(transactions)
        accountsScreen(accounts)
        transactionEntry?.let { transactionEntryScreen(it) }
        accountRegistration?.let { accountRegistrationScreen(it) }
    }
}
