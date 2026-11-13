package com.monit.core.data.importing

import android.app.Application
import com.monit.core.model.*
import java.math.BigInteger
import java.time.LocalDate
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ImportPreviewEvaluatorTest {
    private suspend fun report(bytes: ByteArray = ImportPreviewFixture.bytes(), snapshot: LedgerSnapshot = ImportPreviewFixture.snapshot()) = ImportPreviewEvaluator.evaluate(ImportPreviewParser.parse(bytes), ImportPreviewFixture.context, snapshot, ImportPreviewFixture.today)

    /** 대상 미확정 추출본도 원문 보류는 표시하되 현재 계좌를 승인 매핑으로 추정하지 않는다. */
    @Test fun unknownTargetAllowsHeldPreviewWithoutAdoptingCurrentAccounts() = runTest {
        for (omit in listOf(false, true)) {
            val bytes = ImportPreviewFixture.edited(changeMapping = {
                if (omit) it.remove("target") else it.put("target", JSONObject.NULL)
            })
            val result = report(bytes)
            assertNull(result.input.target)
            assertEquals(6, result.rows.size)
            assertEquals(0, result.expectedTransactionCount)
            assertTrue(result.rows.take(4).all { ImportHoldReason.ACCOUNT_MAPPING_REQUIRED in it.reasons })
            assertEquals(ImportDecision.EXCLUDED, result.rows[4].decision)
            assertEquals(ImportPreviewFixture.context, result.context)
            assertTrue(result.totals.isEmpty())
            assertEquals(0, result.actualCreatedCount)
        }
    }

    /** 6원본행의 상호배타 판정과 예상3거래/실제0, 후보 종류별 합계·이체 효과를 분리한다. */
    @Test fun syntheticRowsHaveExclusiveDecisionsAndNoActualWrites() = runTest {
        val result = report()
        assertEquals(6, result.rows.size)
        assertEquals(3, result.rows.count { it.decision == ImportDecision.CANDIDATE })
        assertEquals(2, result.rows.count { it.decision == ImportDecision.HELD })
        assertEquals(1, result.rows.count { it.decision == ImportDecision.EXCLUDED })
        assertEquals(0, result.existingCount)
        assertEquals(0, result.actualCreatedCount)
        assertEquals(3, result.expectedTransactionCount)
        assertEquals(BigInteger("5000"), result.totals.single { it.kind == TransactionKind.EXPENSE }.amount)
        assertEquals(BigInteger("100000"), result.totals.single { it.kind == TransactionKind.INCOME }.amount)
        assertEquals(BigInteger("2000"), result.totals.single { it.kind == TransactionKind.TRANSFER }.amount)
        assertEquals(BigInteger("93000"), result.accountEffects[ImportPreviewFixture.id(10)])
        assertEquals(BigInteger("2000"), result.accountEffects[ImportPreviewFixture.id(11)])
        assertEquals(result.fingerprint, report().fingerprint)
        assertTrue(result.partial)
    }

    /** 식별 가능한 rawJson 중복 키/해석 오류·digest 불일치는 그 행을 보류하고 다른 행을 유지한다. */
    @Test fun rawErrorsHoldOneRowWithoutLosingKnownRowCount() = runTest {
        for (raw in listOf("{\"a\":1,\"a\":2}", "{broken", "[]")) {
            val result = report(ImportPreviewFixture.edited(changeSnapshot = { ImportPreviewFixture.row(it, 0, raw) }))
            assertEquals(6, result.rows.size)
            assertEquals(setOf(ImportHoldReason.RAW_FORMAT), result.rows.first().reasons)
            assertEquals(2, result.expectedTransactionCount)
        }
        val result = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                it.getJSONArray("rows").getJSONObject(0).put("rawSha256", "bad-digest")
            }),
        )
        assertEquals(setOf(ImportHoldReason.RAW_DIGEST), result.rows.first().reasons)
        assertEquals(6, result.rows.size)
    }

    /** 관계 확보 미상·다른 namespace·같은 ID 반복을 빈 관계/하나의 계좌로 축약하지 않는다. */
    @Test fun unknownForeignAndDuplicateRelationsAreHeld() = runTest {
        val wrongNamespace = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                it.getJSONArray("rows").getJSONObject(0).put("relationNamespace", "other:wallets")
            }),
        )
        assertTrue(ImportHoldReason.RELATION_UNAVAILABLE in wrongNamespace.rows.first().reasons)
        val duplicate = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                val raw = JSONObject(it.getJSONArray("rows").getJSONObject(0).getString("rawJson"))
                raw.put("결제수단", JSONArray(listOf("wallet-a", "wallet-a")))
                ImportPreviewFixture.row(it, 0, raw.toString())
            }),
        )
        assertTrue(ImportHoldReason.AMBIGUOUS_RELATION in duplicate.rows.first().reasons)
        assertNull(duplicate.rows.first().candidate)
    }

    /** 같은 sourceId라도 namespace/entityKind 전체가 키이고, 동일 키의 모순은 양쪽을 보류한다. */
    @Test fun sourceKeyConflictsNeverUseLastRowWins() = runTest {
        val result = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                it.getJSONArray("rows").getJSONObject(1).put("sourceId", "S1")
            }),
        )
        assertTrue(result.rows.take(2).all { ImportHoldReason.CONFLICTING_SOURCE_KEY in it.reasons })
        assertTrue(result.rows.take(2).all { it.decision == ImportDecision.HELD })
        val different = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                it.getJSONArray("rows").getJSONObject(1).put("sourceId", "S1").put("sourceNamespace", "other:transactions")
            }),
        )
        assertFalse(different.rows.any { ImportHoldReason.CONFLICTING_SOURCE_KEY in it.reasons || ImportHoldReason.REPEATED_SOURCE_KEY in it.reasons })
    }

    /** source ID 없는 유사한 두 행도 각각 유지하고 같은 금융 거래로 자동 합치지 않는다. */
    @Test fun idlessSimilarRowsRemainTwoHeldItems() = runTest {
        val result = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                val rows = it.getJSONArray("rows")
                val first = rows.getJSONObject(0)
                first.put("sourceId", JSONObject.NULL)
                val second = rows.getJSONObject(1)
                second.put("sourceId", JSONObject.NULL).put("rawJson", first.getString("rawJson")).put("rawSha256", first.getString("rawSha256"))
            }),
        )
        assertEquals(6, result.rows.size)
        assertTrue(result.rows.take(2).all { it.source.sourceKey == null && ImportHoldReason.POSSIBLE_DUPLICATE in it.reasons })
        assertEquals(0, result.existingCount)
    }

    /** 로컬 날짜/금액/계좌가 같아도 source mapping 없이 기반영으로 확정하지 않는다. */
    @Test fun similarLocalTransactionIsHeldAndNeverExists() = runTest {
        val local = LedgerTransaction(
            ImportPreviewFixture.id(50), TransactionKind.EXPENSE, LocalDate.of(2026, 11, 2), 5000,
            ImportPreviewFixture.id(10), null, AccountNature.GENERAL, null, "다른 합성 메모", 1, 1,
        )
        val result = report(snapshot = ImportPreviewFixture.snapshot(transactions = listOf(local)))
        assertEquals(ImportDecision.HELD, result.rows.first().decision)
        assertTrue(ImportHoldReason.POSSIBLE_DUPLICATE in result.rows.first().reasons)
        assertEquals(0, result.existingCount)
    }

    /** pagination 미완료와 속성 확보 미상은 부분 추출이며 알려진 행만 세고 후보를 확정하지 않는다. */
    @Test fun incompleteSourceCannotBecomeCompleteReconciliation() = runTest {
        val result = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                it.getJSONArray("sources").getJSONObject(0).put("paginationComplete", false).remove("missingProperties")
            }),
        )
        assertTrue(result.partial)
        assertTrue(result.rows.filter { it.source.entityKind == "transaction" }.all { ImportHoldReason.SOURCE_INCOMPLETE in it.reasons })
        assertEquals(0, result.expectedTransactionCount)
        assertTrue(result.totals.isEmpty())
    }

    /** won 표시와 미확인 통화/다른 통화는 KRW로 추정해 합산하지 않는다. */
    @Test fun unconfirmedOrForeignCurrencyIsNotAddedToKrwTotals() = runTest {
        for (currency in listOf("KRW" to false, "USD" to true)) {
            val result = report(
                ImportPreviewFixture.edited(changeMapping = {
                    it.getJSONArray("fields").getJSONObject(0).put("currency", currency.first).put("currencyConfirmed", currency.second)
                }),
            )
            assertTrue(result.rows.filter { it.source.entityKind == "transaction" }.all { ImportHoldReason.CURRENCY_UNCONFIRMED in it.reasons })
            assertTrue(result.totals.isEmpty())
            assertTrue(result.accountEffects.isEmpty())
        }
    }

    /** 소수/지수/0/범위초과 금액을 반올림/절댓값/Long coercion으로 보정하지 않는다. */
    @Test fun unsupportedAmountsRemainRawAndHeld() = runTest {
        for (amount in listOf<Any>("0", "-5000", "1000000000000", "1e3", "99999999999999999999999999", 5000.0)) {
            val result = report(
                ImportPreviewFixture.edited(changeSnapshot = {
                    val raw = JSONObject(it.getJSONArray("rows").getJSONObject(0).getString("rawJson")).put("금액", amount)
                    val text = if (amount is Double) raw.toString().replace("\"금액\":5000", "\"금액\":5000.0") else raw.toString()
                    ImportPreviewFixture.row(it, 0, text)
                }),
            )
            assertTrue(ImportHoldReason.INVALID_AMOUNT in result.rows.first().reasons)
            assertNull(result.rows.first().candidate)
        }
    }

    /** 계좌 대응 중복과 보관 계좌는 자동 연결하지 않으며 이체 양쪽 누락도 보류한다. */
    @Test fun unavailableAmbiguousAndIncompleteAccountMappingsAreHeld() = runTest {
        val duplicate = report(
            ImportPreviewFixture.edited(changeMapping = {
                val first = it.getJSONArray("accounts").getJSONObject(0)
                it.getJSONArray("accounts").put(JSONObject(first.toString()))
            }),
        )
        assertTrue(ImportHoldReason.AMBIGUOUS_RELATION in duplicate.rows.first().reasons)
        val archived = report(snapshot = ImportPreviewFixture.snapshot(archived = true))
        assertEquals(0, archived.expectedTransactionCount)
        assertTrue(archived.rows.first().reasons.contains(ImportHoldReason.ACCOUNT_UNAVAILABLE))
        val transfer = report(
            ImportPreviewFixture.edited(changeSnapshot = {
                val raw = JSONObject(it.getJSONArray("rows").getJSONObject(2).getString("rawJson")).put("입금계좌", JSONArray())
                ImportPreviewFixture.row(it, 2, raw.toString())
            }),
        )
        assertTrue(ImportHoldReason.ACCOUNT_MAPPING_REQUIRED in transfer.rows[2].reasons)
        assertNull(transfer.rows[2].candidate)
    }

    /** 다른 대상 장부 매핑을 현재 장부로 덮어쓰지 않고 revision 변경은 검토 지문을 바꾼다. */
    @Test fun targetContextAndLedgerRevisionAreBoundToPreview() = runTest {
        val input = ImportPreviewParser.parse(ImportPreviewFixture.bytes())
        assertEquals(
            ImportFileProblem.WRONG_LEDGER,
            assertFailsWith<ImportFileException> {
                ImportPreviewEvaluator.evaluate(input, ImportPreviewFixture.context.copy(storageGeneration = ImportPreviewFixture.id(99)), ImportPreviewFixture.snapshot(), ImportPreviewFixture.today)
            }.problem,
        )
        assertNotEquals(report().fingerprint, report(snapshot = ImportPreviewFixture.snapshot(revision = 5)).fingerprint)
    }

    /** 제외 확인이 없으면 통계/미지원 행도 자동 제외하지 않으며 원문·관계는 결과에 남긴다. */
    @Test fun exclusionNeedsConfirmationAndPreservesSourceEvidence() = runTest {
        val result = report(ImportPreviewFixture.edited(changeMapping = { it.getJSONArray("choices").getJSONObject(0).put("confirmed", false) }))
        assertEquals(ImportDecision.HELD, result.rows[4].decision)
        assertTrue(ImportHoldReason.EXPLICIT_HOLD in result.rows[4].reasons)
        assertEquals(result.input.rows[4], result.rows[4].source)
    }
}
