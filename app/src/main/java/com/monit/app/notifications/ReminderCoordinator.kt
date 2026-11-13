package com.monit.app.notifications

import com.monit.core.common.coroutines.CoroutineScopeProvider
import com.monit.core.model.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface ReminderPlatform : ReminderAccessSource {
    fun createChannel()
    fun schedule(plan: ReminderPlan)
    fun cancelAll()
    fun cancelNotification()
    fun post(delivery: ReminderDelivery)
}

/** 프로세스 범위 OS 연결. 금융 writer가 이 객체나 OS 호출에 의존하지 않는다. */
@Singleton
internal class ReminderCoordinator @Inject constructor(
    private val repository: ReminderRepository,
    private val platform: ReminderPlatform,
    private val scopes: CoroutineScopeProvider,
) {
    private val mutex = Mutex()
    private var observer: Job? = null

    fun start() {
        if (observer != null) return
        observer = scopes.ioScope.launch {
            reconcile()
            while (true) {
                try {
                    repository.changes().collect { dirty -> if (dirty) reconcile() }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // DB 관찰 오류는 성공으로 바꾸지 않는다. 재구독은 지연해 busy loop를 막는다.
                    delay(5_000)
                }
            }
        }
    }

    suspend fun reconcile() = mutex.withLock {
        repeat(3) {
            var plan: ReminderPlan? = null
            try {
                platform.createChannel()
                plan = repository.prepare(platform.read())
                if (plan == null) {
                    platform.cancelAll()
                    return@withLock
                }
                if (!repository.current(plan)) return@repeat
                platform.schedule(plan)
                if (plan.cancelNotification) platform.cancelNotification()
                var posted = false
                if (plan.delivery != null) {
                    if (!platform.read().allowed || !repository.current(plan)) return@repeat
                    platform.post(checkNotNull(plan.delivery))
                    posted = true
                }
                if (repository.applied(plan, posted)) return@withLock
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 임시 배타 경계를 DB 오류로 오인해 이전 예약을 지우지 않는다. 해제 신호가 다시 조정한다.
                if (error is BackupException && error.problem == BackupProblem.MAINTENANCE) return@withLock
                try {
                    repository.failed(plan)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* 기존 원본을 보존하며 OS 성공으로 표시하지 않는다. */ }
                if (plan == null) {
                    try {
                        platform.cancelAll()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { /* 다음 앱/시스템 재조정에서 다시 확인한다. */ }
                }
                return@withLock
            }
        }
        // 경합이 계속되면 dirty를 유지하고 다음 관찰/명시 재시도로 넘긴다.
    }
}
