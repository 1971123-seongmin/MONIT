package com.monit.core.navigation

/** UI 명령의 실행 결과다. 금융 성공이나 영속 결과 소비를 의미하지 않는다. */
enum class NavigationResult {
    EXECUTED,
    IGNORED,
    UNAVAILABLE,
}
