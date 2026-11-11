package com.monit.feature.accounts

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import com.monit.core.model.AccountNature
import com.monit.core.model.AccountStatus
import com.monit.core.model.BalanceAnchor
import com.monit.core.model.CreateAccount
import com.monit.core.model.EntityRef
import com.monit.core.model.LedgerAccount
import com.monit.core.model.LedgerContext
import com.monit.core.model.WriteReceipt
import com.monit.feature.accounts.model.AccountSubmissionCodec
import com.monit.feature.accounts.model.SubmittedAccountState
import com.monit.feature.accounts.model.matches
import com.monit.feature.accounts.model.toAccountUiState
import com.monit.feature.accounts.model.validateAccountDraft
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AccountRegistrationModelsTest {
    private val context = LedgerContext(id(1), id(2))
    private val date = LocalDate.of(2026, 11, 10)
    private val submission = SubmittedAccountState(context, id(3),
        CreateAccount(id(4), id(5), "합성 계좌", AccountNature.GENERAL, date, "미확인"))

    /** v2는 실제 Bundle Parcel 왕복에서도 context·ID·원문을 보존한다. */
    @Test fun versionTwoSurvivesParcelRoundTrip() {
        val parcel = Parcel.obtain()
        val restoredParcel = Parcel.obtain()
        try {
            parcel.writeBundle(AccountSubmissionCodec.encode(submission))
            val bytes = parcel.marshall()
            restoredParcel.unmarshall(bytes, 0, bytes.size)
            restoredParcel.setDataPosition(0)
            val bundle = requireNotNull(restoredParcel.readBundle(javaClass.classLoader))
            assertEquals(submission, AccountSubmissionCodec.decode(bundle, context))
        } finally {
            parcel.recycle()
            restoredParcel.recycle()
        }
    }

    /** 필수 필드 누락·틀린 타입·다른 세대·잘못된 ID·미지원 버전을 허용하지 않는다. */
    @Test fun invalidVersionTwoIsRejected() {
        val changes: List<(Bundle) -> Unit> = listOf(
            { it.remove("name") }, { it.putInt("operationId", 3) },
            { it.putString("generation", id(9)) }, { it.putString("accountId", "invalid") },
            { it.putInt("version", 3) }, { it.putString("anchorDate", "2026-02-30") },
        )
        for (change in changes) {
            val raw = AccountSubmissionCodec.encode(submission).apply(change)
            assertFailsWith<IllegalArgumentException> { AccountSubmissionCodec.decode(raw, context) }
        }
    }

    /** v1 복원도 필드 수·타입·버전을 검증한다. 손상은 새 요청 생성으로 보정하지 않는다. */
    @Test fun versionOneRejectsCorruptValues() {
        for (raw in listOf(arrayListOf("1"), arrayListOf<Any>("1", 7), arrayListOf("99"))) {
            assertFailsWith<IllegalArgumentException> { AccountSubmissionCodec.decode(raw, context) }
        }
    }

    /** operation이 맞아도 참조 객체·중복 참조·revision이 다르면 영수증을 거부한다. */
    @Test fun receiptRequiresMatchingReferencesAndRevisions() {
        val good = WriteReceipt(id(3), 2, listOf(EntityRef("accounts", id(4), 1), EntityRef("anchors", id(5), 1)))
        assertTrue(good.matches(submission))
        val wrong = listOf(
            good.copy(operationId = id(9)), good.copy(ledgerRevision = 0),
            good.copy(entities = listOf(EntityRef("accounts", id(9), 1), good.entities[1])),
            good.copy(entities = listOf(good.entities[0], good.entities[0])),
            good.copy(entities = listOf(good.entities[0], EntityRef("anchors", id(5), 0))),
        )
        wrong.forEach { assertFalse(it.matches(submission)) }
    }

    /** 미확인 null은 표시하지만 map의 키 누락을 미확인으로 숨기지 않는다. */
    @Test fun mapperDistinguishesUnknownFromMissingBalance() {
        val value = submission.value
        val account = LedgerAccount(value.id, value.name, value.nature, AccountStatus.ACTIVE, 1,
            BalanceAnchor(value.anchorId, date, null, null, value.unknownReason))
        val ui = account.toAccountUiState(mapOf(account.id to null))
        assertNull(ui.balance)
        assertEquals("미확인", ui.unknownReason)
        assertFailsWith<NoSuchElementException> { account.toAccountUiState(emptyMap()) }
    }

    /** 순수 검증은 입력을 변경하지 않고 첫 오류와 엄격한 날짜 경계를 반환한다. */
    @Test fun validatorPreservesOriginalInputAndFieldOrder() {
        val draft = AccountDraftUiState(name = "  ", anchorDate = "2026-02-30", unknownReason = "")
        assertEquals(AccountInputError.NAME, validateAccountDraft(draft, date.plusDays(1)))
        assertEquals("  ", draft.name)
        assertEquals(AccountInputError.ANCHOR_DATE,
            validateAccountDraft(draft.copy(name = "계좌"), date.plusDays(1)))
        assertEquals(AccountInputError.UNKNOWN_REASON,
            validateAccountDraft(draft.copy(name = "계좌", anchorDate = date.toString()), date.plusDays(1)))
        assertNull(validateAccountDraft(draft.copy(name = "계좌", anchorDate = "1900-01-01", unknownReason = "미확인"), date))
    }

    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}
