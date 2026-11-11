package com.monit.core.navigation

import android.os.Bundle
import androidx.annotation.MainThread
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptionsBuilder

/** UI 수명에 연결된 탐색 계약. 화면 이름·금융 모델·명령 큐를 소유하지 않는다. */
interface Navigator {
    @MainThread
    fun <T : Any> navigate(
        source: NavBackStackEntry,
        route: T,
        options: NavOptionsBuilder.() -> Unit = {},
    ): NavigationResult

    @MainThread
    fun back(
        source: NavBackStackEntry,
        matchesCaller: ((NavBackStackEntry) -> Boolean)? = null,
    ): NavigationResult

    /** 실제 바로 아래 caller에 작은 UI 결과를 기록하고 source 한 건만 제거한다. */
    @MainThread
    fun returnWithResult(
        source: NavBackStackEntry,
        matchesCaller: (NavBackStackEntry) -> Boolean,
        resultKey: String,
        result: Bundle,
    ): NavigationResult
}
