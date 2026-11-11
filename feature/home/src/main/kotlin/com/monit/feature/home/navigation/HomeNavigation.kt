package com.monit.feature.home.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable
import com.monit.core.navigation.NavigationResult
import com.monit.core.navigation.Navigator

fun Navigator.navigateToHome(
    source: NavBackStackEntry,
    options: NavOptionsBuilder.() -> Unit = {},
): NavigationResult = navigate(source, HomeDestination, options)

/** 실제 화면 내용은 호출자가 제공한다. 기본 가짜 화면을 등록하지 않는다. */
fun NavGraphBuilder.homeScreen(content: @Composable (NavBackStackEntry) -> Unit) {
    composable<HomeDestination> { content(it) }
}
