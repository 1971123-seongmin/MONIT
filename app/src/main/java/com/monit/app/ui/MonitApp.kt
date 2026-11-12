package com.monit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.rememberNavController
import com.monit.app.entry.EntryRecoveryGate
import com.monit.app.navigation.MainNavigator
import com.monit.app.navigation.MonitNavHost
import com.monit.app.navigation.TopLevelDestination
import com.monit.app.navigation.rememberMainNavigator
import com.monit.core.designsystem.component.MonitBottomNavigation
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.DraftKind
import com.monit.core.model.DraftOrigin
import com.monit.core.model.DraftScope
import com.monit.core.model.DraftSlot
import com.monit.core.model.LedgerContext
import com.monit.core.navigation.NavigationResult
import com.monit.feature.accounts.AccountRegistrationRoute
import com.monit.feature.accounts.importing.ImportPreviewRoute
import com.monit.feature.accounts.list.AccountsRoute
import com.monit.feature.accounts.navigation.AccountRegistrationDestination
import com.monit.feature.accounts.navigation.AccountRegistrationOrigin
import com.monit.feature.home.HomeRoute
import com.monit.feature.transactions.TransactionsRoute
import com.monit.feature.transactions.entry.TransactionEntryRoute
import com.monit.feature.transactions.navigation.TransactionEntryDestination
import com.monit.feature.transactions.navigation.TransactionEntryKind
import java.time.LocalDate
import java.util.UUID

@Composable
fun MonitApp(context: LedgerContext) {
    MonitAppShell(
        home = { actions ->
            HomeRoute(
                context,
                onNewTransaction = { actions.newTransaction(null) },
            )
        },
        transactions = { actions ->
            TransactionsRoute(
                context,
                onNewTransaction = actions.newTransaction,
            )
        },
        accounts = { actions ->
            AccountsRoute(
                context,
                onRegisterAccount = actions.registerAccount,
                onImportPreview = actions.previewImport,
            )
        },
        importPreview = { entry, navigator ->
            var navigationFailed by remember { mutableStateOf(false) }

            ImportPreviewRoute(
                context = context,
                onBack = { navigationFailed = navigator.closeImportPreview(entry) == NavigationResult.UNAVAILABLE },
            )

            if (navigationFailed) NavigationFailureNotice { navigationFailed = false }
        },
        transactionEntry = { entry, destination, navigator ->
            var navigationFailed by remember { mutableStateOf(false) }

            EntryRecoveryGate(
                context,
                { navigationFailed = navigator.closeConfirmedTransactionEntry(entry) == NavigationResult.UNAVAILABLE },
            ) {
                TransactionEntryRoute(
                    context,
                    destination,
                    entry,
                    onRegisterAccount = { request, parent, slot ->
                        navigationFailed = navigator.openAccountRegistration(
                            entry,
                            AccountRegistrationDestination(
                                UUID.randomUUID().toString(),
                                AccountRegistrationOrigin.TRANSACTION_ENTRY,
                                destination.entryId,
                                request,
                                parent,
                                slot.name,
                            ),
                        ) == NavigationResult.UNAVAILABLE
                    },
                    onClose = { navigationFailed = navigator.closeConfirmedTransactionEntry(entry) == NavigationResult.UNAVAILABLE },
                )
            }

            if (navigationFailed) NavigationFailureNotice { navigationFailed = false }
        },
        accountRegistration = { entry, destination, navigator ->
            var navigationFailed by remember { mutableStateOf(false) }

            EntryRecoveryGate(
                context,
                { navigationFailed = navigator.closeConfirmedAccountRegistration(entry) == NavigationResult.UNAVAILABLE },
            ) {
                AccountRegistrationRoute(
                    context,
                    onClose = { navigationFailed = navigator.closeConfirmedAccountRegistration(entry) == NavigationResult.UNAVAILABLE },
                    onRegistered = { id ->
                        navigationFailed = navigator.returnFromAccountRegistration(
                            entry,
                            id,
                        ) == NavigationResult.UNAVAILABLE
                    },
                    draftScope = remember(destination) {
                        runCatching {
                            if (destination.origin == AccountRegistrationOrigin.ACCOUNTS) DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS)
                            else DraftScope(
                                DraftKind.ACCOUNT,
                                DraftOrigin.TRANSACTION_ENTRY,
                                requireNotNull(destination.callerDraftId),
                                requireNotNull(destination.selectionRequestId),
                                DraftSlot.valueOf(requireNotNull(destination.selectionSlot)),
                            )
                        }.getOrNull()
                    },
                )
            }

            if (navigationFailed) NavigationFailureNotice { navigationFailed = false }
        },
    )
}

@Composable
private fun NavigationFailureNotice(onDismiss: () -> Unit) {
    MonitDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("복귀 경로를 확인해 주세요")
        },
        actions = {
            MonitButton(
                "현재 화면에서 확인",
                onClick = onDismiss,
            )
        },
    ) {
        Text("원래 화면을 찾지 못했어요. 저장된 금융 기록은 그대로이며 다시 저장하지 않아요.")
    }
}

/**
 * 공통 셸은 feature의 상태/조회/금융 모델을 직접 소유하지 않는다.
 * 탭 Back은 현재 entry의 목적지 내용 안에 등록해 탭 상태 저장 경로를 사용한다.
 */
@Composable
internal fun MonitAppShell(
    home: @Composable (EntryActions) -> Unit,
    transactions: @Composable (EntryActions) -> Unit,
    accounts: @Composable (EntryActions) -> Unit,
    transactionEntry: (@Composable (NavBackStackEntry, TransactionEntryDestination, MainNavigator) -> Unit)? = null,
    accountRegistration: (@Composable (NavBackStackEntry, AccountRegistrationDestination, MainNavigator) -> Unit)? = null,
    importPreview: (@Composable (NavBackStackEntry, MainNavigator) -> Unit)? = null,
) {
    val controller = rememberNavController()
    val navigator = rememberMainNavigator(controller)
    val appState = rememberMonitAppState(controller)
    val currentEntry = appState.currentEntry
    val selected = appState.currentTopLevelDestination

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        containerColor = MonitTheme.colors.surfaceContainer,
        contentColor = MonitTheme.colors.onSurface,
        contentWindowInsets = WindowInsets(
            0,
            0,
            0,
            0,
        ),
        bottomBar = {
            if (appState.showBottomBar && selected != null) {
                Column(Modifier.padding(MonitTheme.spacing.content)) {
                    MonitBottomNavigation(
                        labels = listOf(
                            "홈",
                            "거래",
                            "계좌",
                        ),
                        selectedIndex = selected.ordinal,
                        onSelected = { index ->
                            currentEntry?.let {
                                navigator.navigateToTopLevel(
                                    it,
                                    TopLevelDestination.entries[index],
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        MonitNavHost(
            controller = controller,
            home = { entry ->
                home(
                    entryActions(
                        entry,
                        navigator,
                        importPreview != null,
                    ),
                )
            },
            transactions = { entry ->
                BackHandler(enabled = appState.currentEntry === entry) {
                    navigator.onTopLevelBack(
                        entry,
                    )
                }

                transactions(
                    entryActions(
                        entry,
                        navigator,
                        importPreview != null,
                    ),
                )
            },
            accounts = { entry ->
                BackHandler(enabled = appState.currentEntry === entry) {
                    navigator.onTopLevelBack(
                        entry,
                    )
                }

                accounts(
                    entryActions(
                        entry,
                        navigator,
                        importPreview != null,
                    ),
                )
            },
            modifier = Modifier.padding(padding),
            transactionEntry = transactionEntry?.let { content ->
                { entry, destination ->
                    content(
                        entry,
                        destination,
                        navigator,
                    )
                }
            },
            accountRegistration = accountRegistration?.let { content ->
                { entry, destination ->
                    content(
                        entry,
                        destination,
                        navigator,
                    )
                }
            },
            importPreview = importPreview?.let { content ->
                { entry -> content(entry, navigator) }
            },
        )
    }
}

internal data class EntryActions(
    val newTransaction: (LocalDate?) -> Unit,
    val registerAccount: () -> Unit,
    val previewImport: (() -> Unit)?,
)

private fun entryActions(entry: NavBackStackEntry, navigator: MainNavigator, importEnabled: Boolean) = EntryActions(
    newTransaction = { date ->
        navigator.openTransactionEntry(entry, TransactionEntryDestination(UUID.randomUUID().toString(), TransactionEntryKind.EXPENSE, date?.toString()))
    },
    registerAccount = {
        navigator.openAccountRegistration(entry, AccountRegistrationDestination(UUID.randomUUID().toString(), AccountRegistrationOrigin.ACCOUNTS))
    },
    previewImport = if (importEnabled) (
        {
            navigator.openImportPreview(entry)
            Unit
        }
        ) else null,
)
