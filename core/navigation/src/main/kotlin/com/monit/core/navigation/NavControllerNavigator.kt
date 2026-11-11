package com.monit.core.navigation

import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder

/** 닫힌 UI의 controller 참조를 해제하며 이전 host의 요청을 다시 실행하지 않는다. */
class NavControllerNavigator(controller: NavController) :
    Navigator,
    AutoCloseable {
    private var controller: NavController? = controller

    override fun <T : Any> navigate(
        source: NavBackStackEntry,
        route: T,
        options: NavOptionsBuilder.() -> Unit,
    ): NavigationResult = execute(source) { current ->
        current.navigate(route, options)
        NavigationResult.EXECUTED
    }

    override fun back(
        source: NavBackStackEntry,
        matchesCaller: ((NavBackStackEntry) -> Boolean)?,
    ): NavigationResult = execute(source) { current ->
        val caller = current.previousBackStackEntry
        if (caller == null || matchesCaller?.invoke(caller) == false) {
            NavigationResult.UNAVAILABLE
        } else if (current.popBackStack()) {
            NavigationResult.EXECUTED
        } else {
            NavigationResult.UNAVAILABLE
        }
    }

    override fun returnWithResult(
        source: NavBackStackEntry,
        matchesCaller: (NavBackStackEntry) -> Boolean,
        resultKey: String,
        result: Bundle,
    ): NavigationResult {
        require(resultKey.isNotBlank())
        return execute(source) { current ->
            val caller = current.previousBackStackEntry
            if (caller == null || !matchesCaller(caller)) {
                return@execute NavigationResult.UNAVAILABLE
            }
            val handle = caller.savedStateHandle
            val hadValue = handle.contains(resultKey)
            val previousValue = handle.get<Any?>(resultKey)
            var popped = false
            try {
                handle[resultKey] = Bundle(result)
                popped = current.popBackStack()
                if (popped) NavigationResult.EXECUTED else NavigationResult.UNAVAILABLE
            } finally {
                // 반환 거부/예외 시 키 부재와 기존 값(null 포함)을 구분해 복구한다.
                if (!popped) {
                    if (hadValue) handle[resultKey] = previousValue
                    else handle.remove<Any?>(resultKey)
                }
            }
        }
    }

    override fun close() {
        requireMainThread()
        controller = null
    }

    private inline fun execute(
        source: NavBackStackEntry,
        action: (NavController) -> NavigationResult,
    ): NavigationResult {
        requireMainThread()
        val current = controller ?: return NavigationResult.IGNORED
        if (current.currentBackStackEntry !== source ||
            source.lifecycle.currentState != Lifecycle.State.RESUMED
        ) return NavigationResult.IGNORED
        return action(current)
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Navigation requires the main thread" }
    }
}
