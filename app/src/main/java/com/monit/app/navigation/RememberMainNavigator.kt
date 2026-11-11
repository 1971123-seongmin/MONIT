package com.monit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import com.monit.core.navigation.rememberNavigator

@Composable
fun rememberMainNavigator(controller: NavHostController): MainNavigator {
    val navigator = rememberNavigator(controller)

    return remember(navigator) {
        MainNavigator(navigator)
    }
}
