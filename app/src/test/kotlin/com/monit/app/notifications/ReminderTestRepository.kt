package com.monit.app.notifications

import com.monit.core.model.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun reminderTestId(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')

/** UI/OS 연결 프로토콜 대역. 금융·일별 유일성·Migration의 근거는 실제 data 시험이다. */
internal class ReminderTestRepository : ReminderRepository {
    val context = LedgerContext(reminderTestId(900), reminderTestId(901))
    val now: Instant = Instant.parse("2026-11-13T00:01:00Z")
    val signals = MutableStateFlow(true)
    var config = ReminderConfiguration(context, false, 0, true, false, null, listOf(RuleReminderPreference(reminderTestId(40), "합성 보험", true, 0, true)))
    var os = ReminderAccess(true, true, true)
    val source = object : ReminderAccessSource {
        override fun read() = os
    }
    var failRead = false
    var maintenance = false
    var failWrite = false
    var gate: CompletableDeferred<Unit>? = null
    var configurationGate: CompletableDeferred<Unit>? = null
    var configurationEntered: CompletableDeferred<Unit>? = null
    var current = true
    var posted = false
    var changeDuringFirstApply = false
    var emptyLedger = false
    var prepareCalls = 0
    var applyCalls = 0
    var failedCalls = 0
    var writeCalls = 0
    var resolveCalls = 0
    var link: ReminderLink? = ReminderLink(context, reminderTestId(70), "2026-11", reminderTestId(50))
    val delivery = ReminderDelivery(reminderTestId(70), LocalDate.of(2026, 11, 13), listOf(PaymentReminder(reminderTestId(50), "2026-11", ReminderStage.DUE, now.minusSeconds(60))))
    override fun changes(): Flow<Boolean> = signals
    override suspend fun configuration(context: LedgerContext): ReminderConfiguration {
        require(context == this.context)
        configurationEntered?.complete(Unit)
        configurationGate?.await()
        if (failRead) throw IOException()
        return config
    }
    override suspend fun setEnabled(context: LedgerContext, expectedRevision: Long, enabled: Boolean): ReminderConfiguration {
        writeCalls++
        gate?.await()
        if (failWrite) throw IOException()
        require(context == this.context && config.revision == expectedRevision)
        config = config.copy(enabled = enabled, revision = config.revision + 1, dirty = true)
        signals.value = !signals.value
        return config
    }
    override suspend fun setRuleEnabled(context: LedgerContext, ruleId: String, expectedRevision: Long, enabled: Boolean): ReminderConfiguration {
        writeCalls++
        gate?.await()
        if (failWrite) throw IOException()
        require(context == this.context)
        config = config.copy(
            revision = config.revision + 1,
            dirty = true,
            rules = config.rules.map {
                if (it.ruleId == ruleId) {
                    require(it.revision == expectedRevision)
                    it.copy(enabled = enabled, revision = it.revision + 1, isDefault = false)
                } else it
            },
        )
        signals.value = !signals.value
        return config
    }
    override suspend fun prepare(access: ReminderAccess): ReminderPlan? {
        prepareCalls++
        if (maintenance) throw BackupException(BackupProblem.MAINTENANCE)
        if (failRead) throw IOException()
        if (emptyLedger) return null
        val enabled = config.enabled && access.allowed
        return ReminderPlan(config, 1, reminderTestId(80), if (enabled) now.plusSeconds(86_400) else null, if (enabled && !posted) delivery else null, !enabled)
    }
    override suspend fun current(plan: ReminderPlan): Boolean = current
    override suspend fun acceptsAlarm(context: LedgerContext, token: String): Boolean = context == this.context && token == reminderTestId(80)
    override suspend fun applied(plan: ReminderPlan, posted: Boolean): Boolean {
        applyCalls++
        if (posted) this.posted = true
        if (changeDuringFirstApply && applyCalls == 1) return false
        config = config.copy(dirty = false, failed = false)
        signals.value = false
        return true
    }
    override suspend fun failed(plan: ReminderPlan?) {
        failedCalls++
        config = config.copy(dirty = true, failed = true)
    }
    override suspend fun resolve(token: String): ReminderLink? {
        resolveCalls++
        if (failRead) throw IOException()
        return link?.takeIf { it.token == token }
    }
    override suspend fun afterRestore(context: LedgerContext): Unit = error("UI never restores a ledger")
}
