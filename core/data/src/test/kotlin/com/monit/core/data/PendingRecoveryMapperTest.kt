package com.monit.core.data

import android.app.Application
import com.monit.core.model.CorruptedSubmission
import com.monit.core.model.LedgerContext
import com.monit.core.model.PendingRecoveryItem
import com.monit.core.model.UnsupportedSubmission
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DAO의 장부 필터와 독립적으로 복구 투영의 귀속 검사·버전 경계를 검증한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PendingRecoveryMapperTest {
    private val context = LedgerContext(id(900), id(901))
    private val operationId = id(1)

    /** 외부 행의 장부가 다르면 알 수 없는 버전도 귀속 불일치다. ID·hash 오류의 우선순위는 유지한다. */
    @Test
    fun foreignOuterLedgerIsRejectedBeforeUnsupportedVersion() {
        val row = row("""{"version":3}""", ledgerId = id(902))

        assertEquals(
            PendingRecoveryItem.Corrupted(operationId, CorruptedSubmission.CONTEXT_MISMATCH),
            row.toRecoveryItem(context),
        )
        assertEquals(
            PendingRecoveryItem.Corrupted(operationId, CorruptedSubmission.HASH_MISMATCH),
            row.copy(requestSha256 = "invalid").toRecoveryItem(context),
        )
        assertEquals(
            PendingRecoveryItem.Corrupted(null, CorruptedSubmission.INVALID_OPERATION_ID),
            row.copy(operationId = "invalid", requestSha256 = "invalid").toRecoveryItem(context),
        )
    }

    /** 같은 장부의 미지원 버전은 내부 필드 누락/변경과 무관하게 VERSION이며 v1 형식으로 해석하지 않는다. */
    @Test
    fun unsupportedVersionDoesNotReadVersionOneFields() {
        val payloads = listOf(
            """{"version":3}""",
            """{"version":3,"ledgerId":"foreign","expectedStorageGeneration":"foreign"}""",
        )

        for (payload in payloads) {
            assertEquals(
                PendingRecoveryItem.Unsupported(operationId, UnsupportedSubmission.VERSION),
                row(payload).toRecoveryItem(context),
            )
        }
    }

    /** 지원하는 v1에서는 외부 귀속을 통과해도 내부 장부/세대가 호출 context와 다르면 거부한다. */
    @Test
    fun supportedVersionStillChecksInnerContext() {
        val payloads = listOf(
            """{"version":1,"ledgerId":"${id(902)}","expectedStorageGeneration":"${context.storageGeneration}"}""",
            """{"version":1,"ledgerId":"${context.ledgerId}","expectedStorageGeneration":"${id(903)}"}""",
        )

        for (payload in payloads) {
            assertEquals(
                PendingRecoveryItem.Corrupted(operationId, CorruptedSubmission.CONTEXT_MISMATCH),
                row(payload).toRecoveryItem(context),
            )
        }
    }

    private fun row(raw: String, ledgerId: String = context.ledgerId) = SubmissionEntity(ledgerId, operationId, raw, requestHash(raw))

    private fun id(value: Long): String = UUID(0, value).toString()
}
