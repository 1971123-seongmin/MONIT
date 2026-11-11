package com.monit.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.monit.app.navigation.TopLevelDestination
import com.monit.app.navigation.topLevelDestination

/** 상태를 읽는 UI 범위에만 관찰을 등록한다. 명령·별도 back stack·금융 구독은 없다. */
class MonitAppState internal constructor(private val entry: State<NavBackStackEntry?>) {
    val currentTopLevelDestination: TopLevelDestination?
        get() = entry.value?.topLevelDestination()

    val showBottomBar: Boolean
        get() = currentTopLevelDestination != null
}

@Composable
fun rememberMonitAppState(controller: NavHostController): MonitAppState {
    val entry = controller.currentBackStackEntryAsState()
    return remember(controller, entry) { MonitAppState(entry) }
}
