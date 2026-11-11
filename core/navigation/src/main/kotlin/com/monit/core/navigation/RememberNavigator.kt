package com.monit.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavController

/** 목적지/매 프레임 상태가 아닌 controller 수명으로 실행기를 유지한다. */
@Composable
fun rememberNavigator(controller: NavController): Navigator {
    val navigator = remember(controller) { NavControllerNavigator(controller) }
    DisposableEffect(navigator) {
        onDispose { navigator.close() }
    }
    return navigator
}
