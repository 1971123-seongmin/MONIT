package com.monit.core.data

import androidx.room.withTransaction
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.model.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class RoomReminderRepository @Inject constructor(
    private val database: LedgerDatabase,
    private val clock: Clock,
    private val contexts: CoroutineContextProvider,
) : ReminderRepository {
    private val dao get() = database.reminderDao()
    private val ledgerDao get() = database.ledgerDao()
    override fun changes() = kotlinx.coroutines.flow.merge(
        dao.signals().distinctUntilChanged().map { rows -> rows.any { it.dirty } },
        database.maintenanceFinished.map { true },
    )

    override suspend fun configuration(context: LedgerContext): ReminderConfiguration = database.withTransaction {
        requireContext(context)
        settings(context).configuration(context)
    }

    override suspend fun setEnabled(context: LedgerContext, expectedRevision: Long, enabled: Boolean): ReminderConfiguration = database.withTransaction {
        database.requireDurableWriter()
        requireContext(context)
        val before = settings(context)
        require(expectedRevision >= 0)
        if (before.enabled == enabled) return@withTransaction before.configuration(context)
        if (before.revision != expectedRevision) throw LedgerConflictException(LedgerConflict.STALE_REVISION)
        val next = before.copy(enabled = enabled, revision = Math.addExact(before.revision, 1), dirty = true, failed = false, syncToken = null)
        saveSettings(next)
        currentCoroutineContext().ensureActive()
        next.configuration(context)
    }

    override suspend fun setRuleEnabled(context: LedgerContext, ruleId: String, expectedRevision: Long, enabled: Boolean): ReminderConfiguration = database.withTransaction {
        database.requireDurableWriter()
        requireContext(context)
        requireLedgerId(ruleId)
        require(expectedRevision >= 0)
        checkNotNull(database.paymentDao().rule(context.ledgerId, ruleId)) { "Rule no longer exists" }
        val preferences = rulePreferences(context)
        val before = preferences.find { it.ruleId == ruleId }
        val settings = settings(context)
        if ((before?.enabled ?: true) == enabled) return@withTransaction settings.configuration(context)
        if ((before?.revision ?: 0) != expectedRevision) throw LedgerConflictException(LedgerConflict.STALE_REVISION)
        val next = ReminderRuleEntity(context.ledgerId, context.storageGeneration, ruleId, enabled, Math.addExact(expectedRevision, 1))
        if (before == null) dao.insertRule(next) else check(dao.updateRule(next) == 1)
        saveSettings(settings.copy(revision = Math.addExact(settings.revision, 1), dirty = true, failed = false, syncToken = null))
        currentCoroutineContext().ensureActive()
        settings(context).configuration(context)
    }

    override suspend fun prepare(access: ReminderAccess): ReminderPlan? = withContext(contexts.default) {
        database.withTransaction {
            database.requireDurableWriter()
            val ledger = currentLedger() ?: return@withTransaction null
            val context = LedgerContext(ledger.id, ledger.storageGeneration)
            val config = settings(context)
            val now = clock.instant()
            val day = now.atZone(reminderZone).toLocalDate()
            val notices = if (config.enabled && access.allowed) occurrences(context, now, ledger.revision) else emptyList()
            val days = dao.days(context.ledgerId, context.storageGeneration)
            days.forEach(::verifyDay)
            var today = days.find { it.day == day.toString() }
            if (today == null && config.enabled && access.allowed) {
                val consumed = days.flatMap { members(it.membersJson) }.map { it.key }.toSet()
                val eligible = duePaymentReminders(notices, now, consumed)
                if (eligible.isNotEmpty()) {
                    today = ReminderDayEntity(context.ledgerId, context.storageGeneration, day.toString(), id(), "PENDING", captureMembers(eligible), now.toString(), null)
                    dao.insertDay(today)
                }
            }
            val valid = today?.let { row ->
                val original = members(row.membersJson).associateBy { it.key }
                duePaymentReminders(notices, now, emptySet()).filter { original[it.key] == it }
            } ?: emptyList()
            if (today != null && today.status == "PENDING" && config.enabled && access.allowed && valid.isEmpty()) {
                today = today.copy(status = "SUPPRESSED")
                check(dao.updateDay(today) == 1)
            }
            val nextAt = notices.asSequence().filter { it.scheduledAt > now }
                .filter { today == null || it.scheduledAt.atZone(reminderZone).toLocalDate() > day }
                .minOfOrNull { it.scheduledAt }
            val token = id()
            val next = config.copy(syncToken = token, nextAt = nextAt?.toString(), dirty = true)
            saveSettings(next)
            currentCoroutineContext().ensureActive()
            ReminderPlan(
                next.configuration(context),
                ledger.revision,
                token,
                nextAt,
                today?.takeIf { it.status == "PENDING" && valid.isNotEmpty() }?.let { ReminderDelivery(it.token, day, valid) },
                !config.enabled || !access.allowed || valid.isEmpty(),
            )
        }
    }

    override suspend fun current(plan: ReminderPlan): Boolean = database.withTransaction {
        val ledger = requireContext(plan.configuration.context)
        val row = settings(plan.configuration.context)
        row.syncToken == plan.token && row.revision == plan.configuration.revision && row.enabled == plan.configuration.enabled && ledger.revision == plan.ledgerRevision
    }

    override suspend fun acceptsAlarm(context: LedgerContext, token: String): Boolean = database.withTransaction {
        requireLedgerId(token)
        val current = currentLedger() ?: return@withTransaction false
        if (current.id != context.ledgerId || current.storageGeneration != context.storageGeneration) return@withTransaction false
        val row = settings(context)
        row.enabled && row.syncToken == token && row.nextAt?.let { Instant.parse(it) <= clock.instant() } == true
    }

    override suspend fun applied(plan: ReminderPlan, posted: Boolean): Boolean = database.withTransaction {
        database.requireDurableWriter()
        val context = plan.configuration.context
        val ledger = requireContext(context)
        if (posted) {
            val delivery = checkNotNull(plan.delivery)
            val row = checkNotNull(dao.day(context.ledgerId, delivery.token))
            verifyDay(row)
            check(row.storageGeneration == context.storageGeneration)
            if (row.status == "PENDING") check(dao.updateDay(row.copy(status = "POSTED", postedAt = clock.instant().toString())) == 1)
        }
        val row = settings(context)
        if (row.syncToken != plan.token || row.revision != plan.configuration.revision || ledger.revision != plan.ledgerRevision) return@withTransaction false
        saveSettings(row.copy(dirty = false, failed = false, appliedLedgerRevision = ledger.revision))
        currentCoroutineContext().ensureActive()
        true
    }

    override suspend fun failed(plan: ReminderPlan?) = database.withTransaction {
        database.requireDurableWriter()
        val ledger = currentLedger() ?: return@withTransaction
        val context = plan?.configuration?.context ?: LedgerContext(ledger.id, ledger.storageGeneration)
        requireContext(context)
        val row = settings(context)
        if (plan == null || row.syncToken == plan.token) saveSettings(row.copy(dirty = true, failed = true))
        currentCoroutineContext().ensureActive()
    }

    override suspend fun resolve(token: String): ReminderLink? = withContext(contexts.default) {
        requireLedgerId(token)
        database.withTransaction {
            val ledger = currentLedger() ?: return@withTransaction null
            val context = LedgerContext(ledger.id, ledger.storageGeneration)
            val row = dao.day(ledger.id, token) ?: return@withTransaction null
            verifyDay(row)
            if (row.storageGeneration != context.storageGeneration) return@withTransaction null
            val values = members(row.membersJson)
            val month = values.firstOrNull()?.month ?: return@withTransaction null
            // 클릭은 원래 월/항목 조회만 열고 상태 변경을 요청하지 않는다.
            ReminderLink(context, token, month, values.singleOrNull()?.itemId)
        }
    }

    override suspend fun afterRestore(context: LedgerContext) = database.withTransaction {
        database.requireDurableWriter()
        requireContext(context)
        val old = dao.settings(context.ledgerId)
        val next = ReminderSettingsEntity(context.ledgerId, context.storageGeneration, old?.enabled ?: false, Math.addExact(old?.revision ?: 0, 1), true, false, null, null, 0)
        saveSettings(next)
        dao.rules(context.ledgerId).forEach { row -> check(dao.updateRule(row.copy(storageGeneration = context.storageGeneration)) == 1) }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun occurrences(context: LedgerContext, now: Instant, revision: Long): List<PaymentReminder> {
        val month = YearMonth.from(now.atZone(reminderZone))
        val rules = database.paymentDao().rules(context.ledgerId)
        val months = ((-1L..1L).map { month.plusMonths(it) } + rules.map { paymentMonth(it.startMonth) }.filter { it > month.plusMonths(1) })
            .filter { it.year in 1900..9999 }.distinct().sorted()
        val store = PaymentStore(database, clock)
        val excluded = rulePreferences(context).filter { !it.enabled }.map { it.ruleId }.toSet()
        return months.flatMap {
            val snapshot = store.month(context, it.toString(), revision)
            paymentReminders(snapshot.copy(items = snapshot.items.filter { item -> item.reference.ruleId !in excluded }))
        }
    }

    private suspend fun currentLedger(): LedgerEntity? {
        database.requireAvailable()
        val values = ledgerDao.entryLedgers()
        check(!ledgerDao.hasRecordsWithoutLedger())
        if (values.isEmpty()) {
            check(!ledgerDao.hasBusinessRecords())
            return null
        }
        check(values.size == 1)
        return values.single().also {
            LedgerContext(it.id, it.storageGeneration)
            check(it.revision > 0)
        }
    }

    private suspend fun requireContext(context: LedgerContext): LedgerEntity {
        val ledger = currentLedger() ?: throw LedgerConflictException(LedgerConflict.MISSING_REFERENCE)
        if (ledger.id != context.ledgerId || ledger.storageGeneration != context.storageGeneration) throw LedgerConflictException(LedgerConflict.STALE_GENERATION)
        return ledger
    }

    private suspend fun settings(context: LedgerContext): ReminderSettingsEntity {
        val row = dao.settings(context.ledgerId) ?: ReminderSettingsEntity(context.ledgerId, context.storageGeneration, false, 0, true, false, null, null, 0)
        check(row.storageGeneration == context.storageGeneration && row.revision >= 0 && row.appliedLedgerRevision >= 0)
        row.syncToken?.let(::requireLedgerId)
        row.nextAt?.let(Instant::parse)
        return row
    }

    private suspend fun saveSettings(value: ReminderSettingsEntity) {
        if (dao.settings(value.ledgerId) == null) dao.insertSettings(value) else check(dao.updateSettings(value) == 1)
    }
    private fun id() = UUID.randomUUID().toString()
    private suspend fun rulePreferences(context: LedgerContext): List<ReminderRuleEntity> = dao.rules(context.ledgerId).also { rows ->
        rows.forEach { check(it.storageGeneration == context.storageGeneration && it.revision > 0) }
    }
    private suspend fun ReminderSettingsEntity.configuration(context: LedgerContext): ReminderConfiguration {
        val preferences = rulePreferences(context).associateBy { it.ruleId }
        val rules = database.paymentDao().rules(context.ledgerId).map { rule ->
            val row = preferences[rule.id]
            val version = checkNotNull(database.paymentDao().versions(context.ledgerId, rule.id).maxByOrNull { it.revision })
            RuleReminderPreference(rule.id, decodePaymentTerms(version.termsJson).name, row?.enabled ?: true, row?.revision ?: 0, row == null)
        }
        return ReminderConfiguration(context, enabled, revision, dirty, failed, nextAt?.let(Instant::parse), rules)
    }
}

private fun captureMembers(values: List<PaymentReminder>): String = JSONArray().apply {
    values.forEach { value -> put(JSONObject().put("item", value.itemId).put("month", value.month).put("stage", value.stage.name).put("at", value.scheduledAt.toString())) }
}.toString()

private fun members(raw: String): List<PaymentReminder> {
    val array = JSONArray(raw)
    return (0 until array.length()).map { index ->
        val json = array.getJSONObject(index)
        PaymentReminder(json.getString("item").also(::requireLedgerId), json.getString("month").also(::paymentMonth), ReminderStage.valueOf(json.getString("stage")), Instant.parse(json.getString("at")))
    }.also { values -> check(values.map { it.itemId }.distinct().size == values.size && captureMembers(values) == raw) }
}

private fun verifyDay(row: ReminderDayEntity) {
    requireLedgerId(row.token)
    LedgerContext(row.ledgerId, row.storageGeneration)
    LocalDate.parse(row.day)
    check(row.status in setOf("PENDING", "POSTED", "SUPPRESSED"))
    check(row.status != "POSTED" || row.postedAt != null)
    Instant.parse(row.createdAt)
    row.postedAt?.let(Instant::parse)
    check(members(row.membersJson).isNotEmpty())
}
