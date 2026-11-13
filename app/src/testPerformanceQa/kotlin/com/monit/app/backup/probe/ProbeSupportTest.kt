package com.monit.app.backup.probe

import com.monit.app.backup.RecoveryCopyCheckpoint
import com.monit.app.backup.RecoveryCopyProbe
import java.io.IOException
import java.security.UnrecoverableKeyException
import kotlin.test.*
import org.junit.Test

class ProbeSupportTest {
    /** 20개 nearest-rank p95는 정렬된 19번째 값이며 평균이나 최대값으로 대체하지 않는다. */
    @Test fun nearestRankRequiresExactlyTwentyCompleteNonnegativeSamples() {
        assertEquals(19L, ProbeStatistics.p95((1L..20L).toList().reversed()))
        assertEquals(7L, ProbeStatistics.p95(List(20) { 7L }))
        assertFailsWith<IllegalArgumentException> { ProbeStatistics.p95(List(19) { 1L }) }
        assertFailsWith<IllegalArgumentException> { ProbeStatistics.p95(List(20) { if (it == 0) -1L else 1L }) }
    }

    /** key 접근 직전 오류는 실제 key를 생성하거나 삭제하지 않고 명시 checkpoint에서 발생한다. */
    @Test fun keyAccessFailureIsPerInstanceAndOnlyAtSelectedCheckpoint() {
        val probe = RecoveryCopyProbe()
        probe.failurePoint = RecoveryCopyCheckpoint.BEFORE_KEY_ACCESS
        probe.checkpoint(RecoveryCopyCheckpoint.BEFORE_SPACE_CHECK)
        assertFalse(probe.injected)
        assertFailsWith<UnrecoverableKeyException> { probe.checkpoint(RecoveryCopyCheckpoint.BEFORE_KEY_ACCESS) }
        assertTrue(probe.injected)
        val independent = RecoveryCopyProbe()
        independent.checkpoint(RecoveryCopyCheckpoint.BEFORE_KEY_ACCESS)
        assertFalse(independent.injected)
    }

    /** 사본 동기화 직후 오류가 활성화되지 않은 인스턴스에 전파되지 않는다. */
    @Test fun recoveryIoFaultIsExplicitAndLocal() {
        val probe = RecoveryCopyProbe()
        probe.failurePoint = RecoveryCopyCheckpoint.AFTER_CANDIDATE_SYNC
        probe.checkpoint(RecoveryCopyCheckpoint.BEFORE_KEY_ACCESS)
        assertFailsWith<IOException> { probe.checkpoint(RecoveryCopyCheckpoint.AFTER_CANDIDATE_SYNC) }
        assertTrue(probe.injected)
    }
}
