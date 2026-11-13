package com.monit.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

val reminderZone: ZoneId = ZoneId.of("Asia/Seoul")
const val REMINDER_DELAY_NOTICE = "오전 9시 이후 알림을 요청합니다. 절전·기기 상태에 따라 늦거나 표시되지 않을 수 있으며, 정확한 시각은 보장하지 않습니다."

enum class ReminderStage(val dayOffset: Long) { BEFORE(-1), DUE(0), AFTER(1) }

/** 금융 표시 값은 알림 payload에 넣지 않는다. */
data class PaymentReminder(
    val itemId: String,
    val month: String,
    val stage: ReminderStage,
    val scheduledAt: Instant,
) {
    val key: String get() = "$itemId:${stage.name}"
}

data class ReminderAccess(val permissionGranted: Boolean, val appEnabled: Boolean, val channelEnabled: Boolean) {
    val allowed: Boolean get() = permissionGranted && appEnabled && channelEnabled
}

data class ReminderConfiguration(
    val context: LedgerContext,
    val enabled: Boolean,
    val revision: Long,
    val dirty: Boolean,
    val failed: Boolean,
    val nextAt: Instant?,
    val rules: List<RuleReminderPreference> = emptyList(),
)
data class RuleReminderPreference(val ruleId: String, val name: String, val enabled: Boolean, val revision: Long, val isDefault: Boolean)

data class ReminderDelivery(val token: String, val day: LocalDate, val members: List<PaymentReminder>)
data class ReminderPlan(
    val configuration: ReminderConfiguration,
    val ledgerRevision: Long,
    val token: String,
    val nextAt: Instant?,
    val delivery: ReminderDelivery?,
    val cancelNotification: Boolean,
)
data class ReminderLink(val context: LedgerContext, val token: String, val month: String, val itemId: String?)

interface ReminderAccessSource {
    fun read(): ReminderAccess
}

interface ReminderRepository {
    /** 금융/설정 변경과 영속 dirty 상태를 관찰한다. OS 작업은 소비자가 실행한다. */
    fun changes(): Flow<Boolean>
    suspend fun configuration(context: LedgerContext): ReminderConfiguration
    suspend fun setEnabled(context: LedgerContext, expectedRevision: Long, enabled: Boolean): ReminderConfiguration
    suspend fun setRuleEnabled(context: LedgerContext, ruleId: String, expectedRevision: Long, enabled: Boolean): ReminderConfiguration
    suspend fun prepare(access: ReminderAccess): ReminderPlan?
    suspend fun current(plan: ReminderPlan): Boolean
    suspend fun acceptsAlarm(context: LedgerContext, token: String): Boolean
    suspend fun applied(plan: ReminderPlan, posted: Boolean): Boolean
    suspend fun failed(plan: ReminderPlan?)
    suspend fun resolve(token: String): ReminderLink?

    /** 향후 복원 commit 후 호출할 경계. OS 권한/예약은 복원하지 않는다. */
    suspend fun afterRestore(context: LedgerContext)
}

/** D-1/D/D+1만 만든다. 조회가 회차나 거래를 저장하지 않는다. */
fun paymentReminders(snapshot: PaymentMonthSnapshot): List<PaymentReminder> {
    val rules = snapshot.rules.associateBy { it.id }
    return snapshot.items.filter { item ->
        val rule = rules[item.reference.ruleId]
        item.active && item.status in setOf(PaymentStatus.PENDING, PaymentStatus.RECONFIRM) &&
            rule != null && snapshot.month >= rule.startMonth && (rule.endMonth == null || snapshot.month <= rule.endMonth)
    }.flatMap { item ->
        ReminderStage.entries.map { stage ->
            PaymentReminder(item.reference.id, item.reference.month, stage, item.dueDate.plusDays(stage.dayOffset).atTime(9, 0).atZone(reminderZone).toInstant())
        }
    }
}

/** 최근 24시간의 항목별 최신 단계부터 선택한 뒤 이미 처리한 논리 통지를 제외한다. */
fun duePaymentReminders(values: List<PaymentReminder>, now: Instant, consumed: Set<String>): List<PaymentReminder> = values
    .filter { it.scheduledAt > now.minusSeconds(86_400) && it.scheduledAt <= now }
    .groupBy { it.itemId }
    .values.map { it.maxBy(PaymentReminder::scheduledAt) }
    .filter { it.key !in consumed }
    .sortedWith(compareByDescending<PaymentReminder> { it.scheduledAt }.thenBy { it.itemId })
