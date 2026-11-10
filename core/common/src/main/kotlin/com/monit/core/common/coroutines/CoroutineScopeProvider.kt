package com.monit.core.common.coroutines

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** 앱 프로세스 동안 공유하는 Scope. 사용하는 작업이 실패·취소 정책을 소유한다. */
interface CoroutineScopeProvider {
    val ioScope: CoroutineScope
    val defaultScope: CoroutineScope
}

internal class DefaultCoroutineScopeProvider @Inject constructor(
    contexts: CoroutineContextProvider,
) : CoroutineScopeProvider {
    override val ioScope = CoroutineScope(SupervisorJob() + contexts.io)
    override val defaultScope = CoroutineScope(SupervisorJob() + contexts.default)
}
