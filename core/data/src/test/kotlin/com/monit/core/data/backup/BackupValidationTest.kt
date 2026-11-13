package com.monit.core.data.backup

import android.app.Application
import com.monit.core.common.coroutines.CoroutineContextProvider
import com.monit.core.data.*
import com.monit.core.model.*
import java.time.*
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class BackupValidationTest {
    private fun id(n: Int) = "00000000-0000-0000-0000-" + n.toString().padStart(12, '0')
    private val context = LedgerContext(id(900), id(901))
    private val clock = Clock.fixed(Instant.parse("2026-11-13T03:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 11, 13)
    private val contexts = object : CoroutineContextProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
    }
    private lateinit var app: Application
    private lateinit var db: LedgerDatabase
    private lateinit var ledger: RoomLedgerRepository
    private lateinit var payments: RoomPaymentRepository
    private val adapter get() = BackupSnapshotAdapter(db, clock)
    private val validator = BackupValidator(clock)

    @Before fun open() {
        app = RuntimeEnvironment.getApplication()
        db = createLedgerDatabase(app, "backup-validation.db")
        ledger = RoomLedgerRepository(db, clock, contexts)
        payments = RoomPaymentRepository(db, ledger, clock, contexts)
    }

    @After fun close() {
        db.close()
        app.deleteDatabase("backup-validation.db")
    }
    private suspend fun ready() {
        ledger.initialize(context)
        for ((n, nature) in listOf(10 to AccountNature.GENERAL, 20 to AccountNature.SAVINGS)) ledger.createAccount(context, id(n + 100), CreateAccount(id(n), id(n + 1), "합성 계좌 $n", nature, day.minusDays(3), "미확인"))
        ledger.confirmAnchor(context, id(200), ConfirmAnchor(id(10), id(11), 1, day.minusDays(3), 1000, ""))
        ledger.createTransaction(context, id(130), CreateTransaction(id(30), TransactionKind.INCOME, day.minusDays(1), 300, creditAccountId = id(10), memo = "합성 수입"))
    }
    private fun bytes(value: JSONObject) = BackupJson.utf8(value.toString())
    private suspend fun capture() = adapter.capture(context)

    /** 현재 실제 writer의 기준액·금융·원문/영수증을 일관 스냅샷으로 검증하고 UI초안 payload를 제외한다. */
    @Test fun currentWriterSnapshotValidatesAndExcludesUnsubmittedDraftPayload() = runTest {
        ready()
        RoomDraftRepository(db, ledger, contexts).saveDraft(context, id(400), 0, DraftScope(DraftKind.ACCOUNT, DraftOrigin.ACCOUNTS), DraftContent.Account("UI_ONLY_SENTINEL", AccountNature.GENERAL, "날짜 입력중", "미확인"))
        val root = capture()
        val restored = validator.validate(bytes(root))
        assertEquals(context, restored.context)
        assertEquals(1, restored.rows("transactions").size)
        assertEquals(4, restored.root.list("submissionsEvidence").size)
        assertFalse(root.toString().contains("UI_ONLY_SENTINEL"))
        assertEquals("RECORDED", restored.root.getJSONObject("reminderPreferences").text("coverage"))
        assertFalse(restored.root.getJSONObject("reminderPreferences").getJSONObject("global").getBoolean("present"))
    }

    /** 목적: 실제 성공/확정거절/READY/RELEASED·납입이력과 선호가 서로 오분류되지 않는다.
     * 조건: 실제 Room writer의 완료·미래규칙·명백충돌과 금융실패 intent가 공존한다.
     * 절차: 대표 상태를 생성→capture→strict v2 검증→원래 테이블·상태 대조.
     * 기대: 원문/상태 그대로 검증, 활성 금융/기준액·초안 자료를 검증 중 변경하지 않음.
     */
    @Test fun representativeOutcomesIntentsHistoriesAndPreferencesValidateTogether() = runTest {
        ready()
        val terms = PaymentTerms("합성 저축", PaymentPurpose.SAVINGS, 200, 13, id(10), id(20))
        payments.submit(context, id(140), PaymentCommand.RegisterRule(id(40), terms, "2026-11"))
        val item = payments.month(context, "2026-11").items.single().reference
        val transaction = CreateTransaction(id(31), TransactionKind.TRANSFER, day, 200, id(10), id(20))
        payments.submit(context, id(150), PaymentCommand.Confirm(item, PaymentTransaction(transaction), true, ""))
        ledger.updateTransaction(context, id(151), UpdateTransaction(transaction.copy(amount = 250), 1))
        assertFailsWith<TransactionRejectedException> { ledger.deleteTransaction(context, id(152), DeleteTransaction(id(31), 1)) }
        assertFailsWith<AnchorRejectedException> { ledger.confirmAnchor(context, id(201), ConfirmAnchor(id(10), id(11), 1, day.minusDays(3), 2000, "")) }
        assertFailsWith<PaymentRejectedException> { payments.submit(context, id(153), PaymentCommand.ChangeItem(item, PaymentAction.SKIP)) }
        val missing = PaymentCommand.RegisterRule(id(50), terms.copy(name = "해제된 합성 규칙"), "2026-11")
        payments.release(context, id(160), missing)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_backup_rule BEFORE INSERT ON payment_rules BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        assertFails { payments.submit(context, id(161), missing.copy(id = id(51))) }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_backup_rule")
        val reminders = RoomReminderRepository(db, clock, contexts)
        reminders.setEnabled(context, 0, true)
        reminders.setRuleEnabled(context, id(40), 0, false)
        val before = ledger.snapshot(context, day)
        val root = capture()
        val result = validator.validate(bytes(root))
        val witnesses = result.root.list("submissionsEvidence")
        assertEquals(setOf("READY", "RELEASED", "CONSUMED"), witnesses.filter { !it.isNull("payment_intents") }.map { it.getJSONObject("payment_intents").text("status") }.toSet())
        assertEquals(before, ledger.snapshot(context, day))
        assertEquals(1, result.root.getJSONObject("reminderPreferences").list("rules").size)
        assertEquals(2, result.rows("payment_events").size)
    }

    /** 금액만 바꿔 현재 행·생성 원문 의미가 달라진 파일은 outer 암호와 별개로 거부한다. */
    @Test fun transactionMutationCannotReuseUnchangedOriginalReceipt() = runTest {
        ready()
        val root = capture()
        root.getJSONObject("collections").getJSONArray("transactions").getJSONObject(0).put("amount", "999")
        assertFailsWith<BackupException> { validator.validate(bytes(root)) }
        assertEquals(300, ledger.snapshot(context, day).income)
    }

    /** 미래 실제일·잘못된 FK·manifest 수치·알 수 없는 필드·원래 세대 변경은 모두 무쓰기 거부다. */
    @Test fun invalidDateReferenceCountsUnknownFieldsAndSourceGenerationAreRejected() = runTest {
        ready()
        val original = capture()
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("collections").getJSONArray("transactions").getJSONObject(0).put("date", "2027-01-01") },
            { it.getJSONObject("collections").getJSONArray("transactions").getJSONObject(0).put("creditAccountId", id(999)) },
            { it.getJSONObject("manifest").put("recordTotal", 0) },
            { it.put("unapproved", true) },
            { it.getJSONArray("submissionsEvidence").getJSONObject(0).getJSONObject("sourceContext").put("storageGeneration", id(999)) },
        )
        for (change in changes) {
            val copy = JSONObject(original.toString())
            change(copy)
            assertFailsWith<BackupException> { validator.validate(bytes(copy)) }
        }
        assertEquals(original.toString(), capture().toString())
    }

    /** strict v1은 빈 baseline을 보존하고 없는 새 근거/선호를 NOT_RECORDED_BY_V1로 구분한다. */
    @Test fun strictVersionOneEmptyBaselineRetainsOriginalAndMarksMissingEvidence() = runTest {
        val original = javaClass.getResourceAsStream("/backup/backup-v1-minimal.json")!!.use { it.readBytes() }
        val value = validator.validate(original)
        assertEquals("NOT_RECORDED_BY_V1", value.root.getJSONObject("manifest").text("submissionCoverage"))
        assertEquals("NOT_RECORDED_BY_V1", value.root.getJSONObject("reminderPreferences").text("coverage"))
        val archived = value.root.list("originals").single()
        assertContentEquals(original, java.util.Base64.getDecoder().decode(archived.text("bytesBase64")))
        assertTrue(value.rows("transactions").isEmpty())
    }

    /** 현재 저장 모델에 대응하지 않는 v1의 분류/이관/납입 근거는 버리거나 빈 배열로 치환하지 않고 전체 거부한다. */
    @Test fun versionOneUnsupportedFinanceIsNotPartiallyRestored() = runTest {
        val original = javaClass.getResourceAsStream("/backup/backup-v1-ledger-example.json")!!.use { it.readBytes() }
        assertEquals(BackupProblem.UNSUPPORTED, assertFailsWith<BackupException> { validator.validate(original) }.problem)
    }
}
