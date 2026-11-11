package com.monit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.monit.app.navigation.MonitNavHost
import com.monit.app.navigation.TopLevelDestination
import com.monit.app.navigation.rememberMainNavigator
import com.monit.core.designsystem.component.MonitBottomNavigation
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.LedgerContext
import com.monit.feature.accounts.list.AccountsRoute
import com.monit.feature.home.HomeRoute
import com.monit.feature.transactions.TransactionsRoute

@Composable
fun MonitApp(context: LedgerContext) {
    MonitAppShell(
        home = { HomeRoute(context) },
        transactions = { TransactionsRoute(context) },
        accounts = { AccountsRoute(context) },
    )
}

/**
 * 공통 셸은 feature의 상태/조회/금융 모델을 직접 소유하지 않는다.
 * 탭 Back은 현재 entry의 목적지 내용 안에 등록해 탭 상태 저장 경로를 사용한다.
 */
@Composable
internal fun MonitAppShell(
    home: @Composable () -> Unit,
    transactions: @Composable () -> Unit,
    accounts: @Composable () -> Unit,
) {
    val controller = rememberNavController()
    val navigator = rememberMainNavigator(controller)
    val appState = rememberMonitAppState(controller)
    val currentEntry = appState.currentEntry
    val selected = appState.currentTopLevelDestination
    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = MonitTheme.colors.surfaceContainer,
        contentColor = MonitTheme.colors.onSurface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (appState.showBottomBar && selected != null) {
                Column(Modifier.padding(MonitTheme.spacing.content)) {
                    MonitBottomNavigation(
                        labels = listOf("홈", "거래", "계좌"),
                        selectedIndex = selected.ordinal,
                        onSelected = { index ->
                            currentEntry?.let {
                                navigator.navigateToTopLevel(
                                    it,
                                    TopLevelDestination.entries[index]
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
            home = { home() },
            transactions = { entry ->
                BackHandler(enabled = appState.currentEntry === entry) {
                    navigator.onTopLevelBack(
                        entry
                    )
                }
                transactions()
            },
            accounts = { entry ->
                BackHandler(enabled = appState.currentEntry === entry) {
                    navigator.onTopLevelBack(
                        entry
                    )
                }
                accounts()
            },
            modifier = Modifier.padding(padding),
        )
    }
}
