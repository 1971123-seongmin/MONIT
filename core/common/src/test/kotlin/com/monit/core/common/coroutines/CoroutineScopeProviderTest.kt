package com.monit.core.common.coroutines

import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineScopeProviderTest {
    /** 같은 가상 시간에서 IO·Default가 주입된 Dispatcher를 사용하고 독립 Job을 소유한다. */
    @Test fun scopesUseInjectedContextsAndIndependentJobs() = runTest {
        val contexts = DefaultCoroutineContextProvider(
            StandardTestDispatcher(testScheduler, "main"),
            StandardTestDispatcher(testScheduler, "io"),
            StandardTestDispatcher(testScheduler, "default"),
        )
        val provider = DefaultCoroutineScopeProvider(contexts)
        try {
            assertSame(
                contexts.io,
                provider.ioScope.async {
                    currentCoroutineContext()[ContinuationInterceptor]
                }.await(),
            )
            assertSame(
                contexts.default,
                provider.defaultScope.async {
                    currentCoroutineContext()[ContinuationInterceptor]
                }.await(),
            )
            assertNotSame(provider.ioScope.coroutineContext[Job], provider.defaultScope.coroutineContext[Job])
        } finally {
            provider.ioScope.cancel()
            provider.defaultScope.cancel()
        }
    }

    /**
     * 목적: 자식 실패의 Supervisor 격리와 IO·Default Scope의 독립 취소를 확인한다.
     * 조건: 같은 TestScheduler에서 IO 형제 작업은 gate 대기, Default 작업은 취소까지 대기한다.
     * 절차: IO 자식 실패를 await로 관찰하고 형제 gate를 해제한 뒤 Default Scope만 취소한다.
     * 기대: 형제는 정상 완료하고 Default의 finally가 실행되며 IO Scope의 새 작업은 계속 진행한다.
     */
    @Test fun childFailureAndScopeCancellationRemainIsolated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = DefaultCoroutineScopeProvider(DefaultCoroutineContextProvider(dispatcher, dispatcher, dispatcher))
        val gate = CompletableDeferred<Unit>()
        val defaultCleanedUp = CompletableDeferred<Unit>()
        try {
            val sibling = provider.ioScope.async {
                gate.await()
                "completed"
            }
            provider.defaultScope.async {
                try {
                    awaitCancellation()
                } finally {
                    defaultCleanedUp.complete(Unit)
                }
            }
            val failed = provider.ioScope.async { throw IOException("synthetic failure") }
            runCurrent()
            assertFailsWith<IOException> { failed.await() }
            assertTrue(provider.ioScope.isActive)
            gate.complete(Unit)
            assertEquals("completed", sibling.await())
            provider.defaultScope.cancel()
            runCurrent()
            assertTrue(defaultCleanedUp.isCompleted)
            assertEquals("still active", provider.ioScope.async { "still active" }.await())
        } finally {
            provider.ioScope.cancel()
            provider.defaultScope.cancel()
            runCurrent()
        }
    }
}
