package com.monit.app.notifications

import com.monit.core.common.coroutines.CoroutineScopeProvider
import com.monit.core.model.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderCoordinatorTest {
    /** 복원 유지보수는 조회 오류와 다르며 기존 OS 예약/실패 상태를 바꾸지 않는다. */
    @Test fun maintenanceDefersWithoutCancellingExistingPlatformSchedule() = runTest {
        val repository = ReminderTestRepository().apply { maintenance = true }
        val platform = TestPlatform()
        val before = repository.config
        runtime(repository, platform, backgroundScope).reconcile()
        assertEquals(0, platform.cancelAlls)
        assertEquals(0, platform.posts)
        assertEquals(0, repository.failedCalls)
        assertEquals(before, repository.config)
    }
    private fun runtime(repository: ReminderRepository, platform: TestPlatform, scope: CoroutineScope) = ReminderCoordinator(
        repository,
        platform,
        object : CoroutineScopeProvider {
            override val ioScope = scope
            override val defaultScope = scope
        },
    )

    /** OS 예약 실패는 금융 writer와 분리된 failed/dirty 경로로만 전달하고 성공 처리하지 않는다. */
    @Test fun scheduleFailureKeepsPendingIntentAndNeverAcknowledgesSuccess() = runTest {
        val repository = ReminderTestRepository().apply { config = config.copy(enabled = true) }
        val platform = TestPlatform().apply { failSchedule = true }
        runtime(repository, platform, backgroundScope).reconcile()
        assertEquals(1, repository.failedCalls)
        assertEquals(0, repository.applyCalls)
        assertTrue(repository.config.failed && repository.config.dirty)
        assertEquals(0, platform.posts)
    }

    /** post 실패 뒤 명시 재조정도 원래 일별 token과 같은 notification 경로로만 재시도한다. */
    @Test fun failedPostCanRetryOriginalDailyDelivery() = runTest {
        val repository = ReminderTestRepository().apply { config = config.copy(enabled = true) }
        val platform = TestPlatform().apply { failPost = true }
        val coordinator = runtime(repository, platform, backgroundScope)
        coordinator.reconcile()
        assertFalse(repository.posted)
        platform.failPost = false
        coordinator.reconcile()
        assertEquals(listOf(repository.delivery.token, repository.delivery.token), platform.tokens)
        assertTrue(repository.posted)
        assertFalse(repository.config.failed)
    }

    /** 조회 오류나 장부 부재를 금융 초기화/알림 발송으로 바꾸지 않는다. */
    @Test fun unavailableLedgerOrReadFailureCancelsPlatformWithoutPosting() = runTest {
        val repository = ReminderTestRepository().apply { failRead = true }
        val platform = TestPlatform()
        val coordinator = runtime(repository, platform, backgroundScope)
        coordinator.reconcile()
        repository.failRead = false
        repository.emptyLedger = true
        coordinator.reconcile()
        assertEquals(2, platform.cancelAlls)
        assertEquals(0, platform.posts)
        assertEquals(0, repository.applyCalls)
    }

    /**
     * 목적: DB 확인 뒤에도 OS 권한이 철회되면 통지를 보내지 않는다.
     * 조건: prepare에 쓰인 첫 조회만 허용이고 발송 직전 조회는 거부다.
     * 절차: reconcile을 끝까지 진행하고 거부 상태로 재준비한다.
     * 기대: post0·취소1·금융 명령 없음, 거부를 발송 성공으로 표시하지 않는다.
     */
    @Test fun accessIsRecheckedImmediatelyBeforePost() = runTest {
        val repository = ReminderTestRepository().apply { config = config.copy(enabled = true) }
        val platform = TestPlatform().apply { revokeAfterFirstRead = true }
        runtime(repository, platform, backgroundScope).reconcile()
        assertEquals(0, platform.posts)
        assertEquals(1, platform.cancels)
        assertFalse(repository.posted)
    }

    /** 같은 발송 뒤 금융 변경 경합으로 clean CAS가 실패해도 새 요약을 추가하지 않는다. */
    @Test fun concurrentChangeAfterPostDoesNotCreateAnotherDelivery() = runTest {
        val repository = ReminderTestRepository().apply {
            config = config.copy(enabled = true)
            changeDuringFirstApply = true
        }
        val platform = TestPlatform()
        runtime(repository, platform, backgroundScope).reconcile()
        assertEquals(1, platform.posts)
        assertEquals(2, repository.applyCalls)
        assertEquals(2, repository.prepareCalls)
    }

    /** 취소는 성공/실패로 삼키지 않고 dirty 상태를 그대로 둔다. */
    @Test fun cancellationPropagatesWithoutAcknowledgement() = runTest {
        val repository = ReminderTestRepository().apply { config = config.copy(enabled = true) }
        val platform = TestPlatform().apply { cancelSchedule = true }
        assertFailsWith<CancellationException> { runtime(repository, platform, backgroundScope).reconcile() }
        assertEquals(0, repository.applyCalls)
        assertEquals(0, repository.failedCalls)
        assertTrue(repository.config.dirty)
    }

    /** 앱 프로세스 observer는 중복 시작하지 않고 clean 신호로 재조정 루프를 만들지 않는다. */
    @Test fun observerStartsOnceAndDoesNotReconcileCleanStateRepeatedly() = runTest {
        val repository = ReminderTestRepository()
        val platform = TestPlatform()
        val coordinator = runtime(repository, platform, backgroundScope)
        coordinator.start()
        coordinator.start()
        runCurrent()
        assertEquals(1, repository.prepareCalls)
        assertEquals(1, repository.signals.subscriptionCount.value)
    }

    /**
     * 목적: 완료된 prepare/applied의 지연 dirty 신호가 자기 재조정 루프를 만들지 않는다.
     * 조건: 실제 Room의 true→false 변경을 공유 Flow로 지연 전달하고 불필요한 세 번째 준비는 gate에서 중단한다.
     * 절차: observer 시작→명시 OS 재조회→과거 신호 소비→진짜 외부 변경→clean 상태의 명시 재조회.
     * 기대: 자기 신호 추가 준비0, 외부/명시 요청은 각각 실행하며 일별 post1이다. 실패 시 observer부터 취소해 루프를 남기지 않는다.
     */
    @Test fun delayedOwnDirtySignalIsIgnoredButExternalAndExplicitRequestsStillReconcile() = runTest {
        val base = ReminderTestRepository()
        val signals = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        val unexpected = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var externalChangesAllowed = false
        val repository = object : ReminderRepository by base {
            override fun changes() = signals
            override suspend fun prepare(access: ReminderAccess): ReminderPlan? {
                if (base.prepareCalls >= 2 && !externalChangesAllowed) {
                    unexpected.complete(Unit)
                    release.await()
                }
                base.config = base.config.copy(dirty = true)
                val plan = base.prepare(access)
                signals.emit(true)
                return plan
            }
            override suspend fun applied(plan: ReminderPlan, posted: Boolean): Boolean {
                val result = base.applied(plan, posted)
                signals.emit(false)
                return result
            }
        }
        val platform = TestPlatform()
        val coordinator = runtime(repository, platform, backgroundScope)
        try {
            coordinator.start()
            runCurrent()
            assertEquals(1, base.prepareCalls)
            coordinator.reconcile()
            runCurrent()
            assertFalse(unexpected.isCompleted)
            assertEquals(2, base.prepareCalls)
            assertFalse(base.config.dirty)
            externalChangesAllowed = true
            base.config = base.config.copy(enabled = true, revision = base.config.revision + 1, dirty = true)
            signals.emit(true)
            runCurrent()
            assertEquals(3, base.prepareCalls)
            assertFalse(base.config.dirty)
            assertTrue(base.config.enabled)
            coordinator.reconcile()
            runCurrent()
            assertEquals(4, base.prepareCalls)
            assertEquals(1, platform.posts)
            assertEquals(0, base.failedCalls)
        } finally {
            backgroundScope.cancel()
            release.complete(Unit)
        }
    }

    /**
     * 목적: 관찰 중 유지보수는 OS 예약을 지우지 않고 해제 뒤 진짜 dirty를 처리한다.
     * 조건: 같은 사용자 선호에 maintenance 신호와 같은 값의 해제 신호를 전달한다.
     * 절차: 시작/확정→maintenance 중 dirty 관찰→해제 재관찰→무장부 명시 재조회→과거 신호.
     * 기대: 유지보수 중 변경/실패0, 해제 뒤 재조정/선호 보존, 무장부 명시 취소1·추가 자기 실행0이다.
     */
    @Test fun observedMaintenanceResumesPendingWorkAndEmptyLedgerStillCancelsExplicitly() = runTest {
        val base = ReminderTestRepository().apply { config = config.copy(enabled = true) }
        val signals = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
        val repository = object : ReminderRepository by base {
            override fun changes() = signals
        }
        val platform = TestPlatform()
        val coordinator = runtime(repository, platform, backgroundScope)
        coordinator.start()
        runCurrent()
        assertEquals(1, base.prepareCalls)
        base.maintenance = true
        base.config = base.config.copy(dirty = true)
        val pending = base.config
        signals.emit(true)
        runCurrent()
        assertEquals(1, base.prepareCalls)
        assertEquals(pending, base.config)
        assertEquals(0, base.failedCalls)
        assertEquals(0, platform.cancelAlls)
        base.maintenance = false
        signals.emit(true)
        runCurrent()
        assertEquals(2, base.prepareCalls)
        assertFalse(base.config.dirty)
        assertTrue(base.config.enabled)
        assertEquals(1, platform.posts)
        base.emptyLedger = true
        coordinator.reconcile()
        signals.emit(true)
        runCurrent()
        assertEquals(1, platform.cancelAlls)
        assertEquals(3, base.prepareCalls)
    }
}

private class TestPlatform : ReminderPlatform {
    var failSchedule = false
    var failPost = false
    var cancelSchedule = false
    var revokeAfterFirstRead = false
    var reads = 0
    var posts = 0
    var cancels = 0
    var cancelAlls = 0
    val tokens = mutableListOf<String>()
    override fun createChannel() {}
    override fun read(): ReminderAccess {
        reads++
        return ReminderAccess(!revokeAfterFirstRead || reads == 1, true, true)
    }
    override fun schedule(plan: ReminderPlan) {
        if (cancelSchedule) throw CancellationException()
        if (failSchedule) throw IOException()
    }
    override fun cancelAll() {
        cancelAlls++
    }
    override fun cancelNotification() {
        cancels++
    }
    override fun post(delivery: ReminderDelivery) {
        tokens += delivery.token
        if (failPost) throw IOException()
        posts++
    }
}
