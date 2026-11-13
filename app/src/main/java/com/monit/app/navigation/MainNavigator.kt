package com.monit.app.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.toRoute
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator
import com.monit.feature.accounts.navigation.AccountDetailDestination
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.AccountRegistrationOrigin
import com.monit.feature.accounts.navigation.AccountsDestination
import com.monit.feature.accounts.navigation.ImportPreviewDestination
import com.monit.feature.accounts.navigation.navigateToAccountDetail
import com.monit.feature.accounts.navigation.navigateToAccountRegistration
import com.monit.feature.accounts.navigation.navigateToAccounts
import com.monit.feature.accounts.navigation.navigateToImportPreview
import com.monit.feature.home.navigation.BackupDestination
import com.monit.feature.home.navigation.HomeDestination
import com.monit.feature.home.navigation.PaymentsDestination
import com.monit.feature.home.navigation.ReminderSettingsDestination
import com.monit.feature.home.navigation.navigateToBackup
import com.monit.feature.home.navigation.navigateToHome
import com.monit.feature.home.navigation.navigateToPayments
import com.monit.feature.home.navigation.navigateToReminderSettings
import com.monit.feature.transactions.navigation.RegisteredAccountResult
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import com.monit.feature.transactions.navigation.TransactionEntryOrigin
import com.monit.feature.transactions.navigation.TransactionsDestination
import com.monit.feature.transactions.navigation.navigateToTransactionEntry
import com.monit.feature.transactions.navigation.navigateToTransactions

/** 제품 탐색 정책만 소유하며 금융 성공/복구 여부는 호출 feature가 확인한다. */
class MainNavigator(private val navigator: Navigator) {
    fun openBackup(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<HomeDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToBackup(source)
    }
    fun closeBackup(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<BackupDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.back(source) { it.destination.hasRoute<HomeDestination>() }
    }
    fun openAccountDetail(source: NavBackStackEntry, destination: AccountDetailDestination): NavigationResult {
        if (!source.destination.hasRoute<AccountsDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToAccountDetail(source, destination)
    }
    fun closeAccountDetail(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<AccountDetailDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.back(source) { it.destination.hasRoute<AccountsDestination>() }
    }
    fun openAccountTransactions(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<AccountDetailDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToTransactions(source) {
            popUpTo<HomeDestination> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun openReminderSettings(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<HomeDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToReminderSettings(source)
    }
    fun closeReminderSettings(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<ReminderSettingsDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.back(source) { it.destination.hasRoute<HomeDestination>() }
    }
    fun openReminderPayments(source: NavBackStackEntry, destination: PaymentsDestination): NavigationResult {
        if (!source.destination.hasRoute<HomeDestination>() && !source.destination.hasRoute<TransactionsDestination>() && !source.destination.hasRoute<AccountsDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToPayments(source, destination)
    }
    fun openPayments(source: NavBackStackEntry, destination: PaymentsDestination): NavigationResult {
        if (!source.destination.hasRoute<HomeDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToPayments(source, destination)
    }
    fun closePayments(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<PaymentsDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.back(source) { it.destination.hasRoute<HomeDestination>() || it.destination.hasRoute<TransactionsDestination>() || it.destination.hasRoute<AccountsDestination>() }
    }

    fun openImportPreview(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<AccountsDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.navigateToImportPreview(source)
    }

    fun closeImportPreview(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<ImportPreviewDestination>()) return NavigationResult.UNAVAILABLE
        return navigator.back(source) { it.destination.hasRoute<AccountsDestination>() }
    }

    fun navigateToTopLevel(
        source: NavBackStackEntry,
        destination: TopLevelDestination,
    ): NavigationResult {
        val selected = source.topLevelDestination() ?: return NavigationResult.UNAVAILABLE
        if (selected == destination) return NavigationResult.IGNORED
        val options: NavOptionsBuilder.() -> Unit = {
            popUpTo<HomeDestination> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        return with(navigator) {
            when (destination) {
                TopLevelDestination.HOME -> navigateToHome(source, options)
                TopLevelDestination.TRANSACTIONS -> navigateToTransactions(source, options)
                TopLevelDestination.ACCOUNTS -> navigateToAccounts(source, options)
            }
        }
    }

    /** 루트에만 연결한다. 입력/등록의 포기 확인을 우회하는 범용 Back이 아니다. */
    fun onTopLevelBack(source: NavBackStackEntry): NavigationResult = when (source.topLevelDestination()) {
        TopLevelDestination.TRANSACTIONS, TopLevelDestination.ACCOUNTS ->
            navigateToTopLevel(source, TopLevelDestination.HOME)

        else -> NavigationResult.UNAVAILABLE
    }

    fun openTransactionEntry(
        source: NavBackStackEntry,
        destination: TransactionEntryDestination,
    ): NavigationResult {
        if (!source.destination.hasRoute<HomeDestination>() &&
            !source.destination.hasRoute<TransactionsDestination>() && !source.destination.hasRoute<PaymentsDestination>()
        ) return NavigationResult.UNAVAILABLE
        val origin = when {
            source.destination.hasRoute<HomeDestination>() -> TransactionEntryOrigin.HOME
            source.destination.hasRoute<PaymentsDestination>() -> TransactionEntryOrigin.PAYMENTS
            else -> TransactionEntryOrigin.TRANSACTIONS
        }
        return navigator.navigateToTransactionEntry(source, destination.copy(origin = origin))
    }

    fun openAccountRegistration(
        source: NavBackStackEntry,
        destination: AccountRegistrationDestination,
    ): NavigationResult {
        if (!matchesRegistrationCaller(source, destination)) return NavigationResult.UNAVAILABLE
        return navigator.navigateToAccountRegistration(source, destination)
    }

    /** feature가 영수증과 계좌 ID를 검증한 뒤 호출한다. 결과는 후보이며 적용은 수신 feature가 한다. */
    fun returnFromAccountRegistration(
        source: NavBackStackEntry,
        accountId: String,
    ): NavigationResult {
        if (!source.destination.hasRoute<AccountRegistrationDestination>()) {
            return NavigationResult.UNAVAILABLE
        }
        val registration = source.toRoute<AccountRegistrationDestination>()
        if (registration.origin == AccountRegistrationOrigin.ACCOUNTS) {
            return navigator.back(source) { matchesRegistrationCaller(it, registration) }
        }
        val requestId = requireNotNull(registration.selectionRequestId)
        return navigator.returnWithResult(
            source = source,
            matchesCaller = { matchesRegistrationCaller(it, registration) },
            resultKey = RegisteredAccountResult.key(requestId),
            result = RegisteredAccountResult(requestId, accountId).toBundle(),
        )
    }

    /** 취소/닫기 허용 및 필요한 사용자 확인은 호출 feature의 책임이다. */
    fun closeConfirmedAccountRegistration(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<AccountRegistrationDestination>()) {
            return NavigationResult.UNAVAILABLE
        }
        val registration = source.toRoute<AccountRegistrationDestination>()
        return navigator.back(source) { matchesRegistrationCaller(it, registration) }
    }

    /** P08 확인 등 feature의 이탈 절차를 마친 후 실제 호출 루트 한 단계로 복귀한다. */
    fun closeConfirmedTransactionEntry(source: NavBackStackEntry): NavigationResult {
        if (!source.destination.hasRoute<TransactionEntryDestination>()) {
            return NavigationResult.UNAVAILABLE
        }
        return navigator.back(source) {
            it.destination.hasRoute<HomeDestination>() ||
                it.destination.hasRoute<TransactionsDestination>() || it.destination.hasRoute<PaymentsDestination>()
        }
    }

    private fun matchesRegistrationCaller(
        caller: NavBackStackEntry,
        registration: AccountRegistrationDestination,
    ): Boolean = when (registration.origin) {
        AccountRegistrationOrigin.ACCOUNTS -> caller.destination.hasRoute<AccountsDestination>()

        AccountRegistrationOrigin.TRANSACTION_ENTRY ->
            caller.destination.hasRoute<TransactionEntryDestination>() &&
                caller.toRoute<TransactionEntryDestination>().entryId == registration.callerEntryId
    }
}

internal fun NavBackStackEntry.topLevelDestination(): TopLevelDestination? = when {
    destination.hasRoute<HomeDestination>() -> TopLevelDestination.HOME
    destination.hasRoute<TransactionsDestination>() -> TopLevelDestination.TRANSACTIONS
    destination.hasRoute<AccountsDestination>() -> TopLevelDestination.ACCOUNTS
    else -> null
}
