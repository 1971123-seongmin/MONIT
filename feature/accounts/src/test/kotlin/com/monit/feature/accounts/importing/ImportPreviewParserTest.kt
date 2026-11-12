package com.monit.feature.accounts.importing

import android.app.Application
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
class ImportPreviewParserTest {
    /** envelope/매핑을 재직렬화해 digest를 검증하지 않고, 제공 문자열의 공백·Unicode를 그대로 보존한다. */
    @Test fun exactStringsAndDigestsSurviveWithoutNormalization() = runTest {
        val snapshot = ImportPreviewFixture.envelope().getString("snapshotJson")
        val mapping = ImportPreviewFixture.envelope().getString("mappingJson")
        val bytes = ImportPreviewFixture.seal(" \n$snapshot\n ", "\t$mapping\n")
        val parsed = ImportPreviewParser.parse(bytes)
        assertEquals(6, parsed.rows.size)
        assertEquals(ImportPreviewParser.digest(" \n$snapshot\n "), parsed.snapshotSha256)
        assertEquals(ImportPreviewParser.digest("\t$mapping\n"), parsed.mappingSha256)
        assertEquals(ImportPreviewParser.digest(bytes), parsed.inputSha256)
        val raw = ImportPreviewFixture.envelope().let { JSONObject(it.getString("snapshotJson")) }.getJSONArray("rows").getJSONObject(0)
        assertEquals(raw.getString("rawJson"), parsed.rows.first().rawJson)
    }

    /** envelope·snapshot·mapping의 중복 키는 JSONObject 생성 전에 파일 오류로 거부한다. */
    @Test fun duplicateKeysInEveryStructuralLayerAreRejected() = runTest {
        val envelope = ImportPreviewFixture.envelope()
        val snapshot = envelope.getString("snapshotJson")
        val mapping = envelope.getString("mappingJson")
        val inputs = listOf(
            ("{\"version\":1," + envelope.toString().drop(1)).toByteArray(),
            ImportPreviewFixture.seal("{\"scope\":\"duplicate\"," + snapshot.drop(1), mapping),
            ImportPreviewFixture.seal(snapshot, "{\"version\":\"duplicate\"," + mapping.drop(1)),
        )
        for (input in inputs) assertEquals(ImportFileProblem.INVALID_JSON, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(input) }.problem)
    }

    /** 잘못된 UTF-8이나 digest를 성공한 0건으로 바꾸지 않는다. */
    @Test fun invalidUtf8AndPayloadDigestAreFileErrors() = runTest {
        assertEquals(ImportFileProblem.INVALID_UTF8, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(byteArrayOf(0xC3.toByte(), 0x28)) }.problem)
        val envelope = ImportPreviewFixture.envelope().put("snapshotSha256", "0".repeat(64))
        assertEquals(ImportFileProblem.INVALID_DIGEST, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(envelope.toString().toByteArray()) }.problem)
    }

    /** 알 수 없는 버전·필드와 잘린 파일은 일부 성공으로 처리하지 않는다. */
    @Test fun unknownVersionFieldsAndTruncatedJsonAreRejected() = runTest {
        val version = ImportPreviewFixture.envelope().put("version", 2).toString().toByteArray()
        assertEquals(ImportFileProblem.UNSUPPORTED_VERSION, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(version) }.problem)
        assertFailsWith<ImportFileException> { ImportPreviewParser.parse(ImportPreviewFixture.envelope().put("unknown", true).toString().toByteArray()) }
        assertFailsWith<ImportFileException> { ImportPreviewParser.parse(ImportPreviewFixture.bytes().copyOf(30)) }
    }

    /** 입력 bytes·행 수·중첩 한도를 넘으면 절단하지 않고 파일 오류로 끝낸다. */
    @Test fun capacityAndDepthLimitsRejectInsteadOfTruncating() = runTest {
        assertEquals(ImportFileProblem.TOO_LARGE, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(ByteArray(ImportPreviewParser.MAX_INPUT_BYTES + 1)) }.problem)
        val rows = JSONArray().apply { repeat(ImportPreviewParser.MAX_ROWS + 1) { put(JSONObject()) } }
        val bytes = ImportPreviewFixture.edited(changeSnapshot = { it.put("rows", rows) })
        assertEquals(ImportFileProblem.TOO_MANY_ROWS, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(bytes) }.problem)
        val deep = "{\"nested\":".repeat(18) + "0" + "}".repeat(18)
        assertFailsWith<ImportFileException> { ImportPreviewParser.strictObject(deep) }
    }

    /** pagination과 관계 확보 누락을 true나 빈 관계로 채우지 않는다. */
    @Test fun missingCoverageAndRelationsRemainUnknown() = runTest {
        val bytes = ImportPreviewFixture.edited(changeSnapshot = {
            it.getJSONArray("sources").getJSONObject(0).remove("paginationComplete")
            it.getJSONArray("sources").getJSONObject(0).remove("missingProperties")
            it.getJSONArray("rows").getJSONObject(0).remove("relationsComplete")
        })
        val parsed = ImportPreviewParser.parse(bytes)
        assertNull(parsed.coverage.first().paginationComplete)
        assertFalse(parsed.coverage.first().missingPropertiesKnown)
        assertNull(parsed.rows.first().relationsComplete)
    }

    /** 같은 locator를 두 결과에 사용하거나 상충하는 명시 결정을 마지막 값 우선으로 고르지 않는다. */
    @Test fun ambiguousLocatorsAndChoicesAreRejected() = runTest {
        val duplicate = ImportPreviewFixture.edited(changeSnapshot = {
            it.getJSONArray("rows").getJSONObject(1).put("locator", "rows/0")
        })
        assertFailsWith<ImportFileException> { ImportPreviewParser.parse(duplicate) }
        val choices = ImportPreviewFixture.edited(changeMapping = {
            val original = it.getJSONArray("choices").getJSONObject(0)
            it.getJSONArray("choices").put(JSONObject(original.toString()))
        })
        assertEquals(ImportFileProblem.INVALID_MAPPING, assertFailsWith<ImportFileException> { ImportPreviewParser.parse(choices) }.problem)
    }

    /** null/누락과 달리 잘못된 target 타입·불완전 UUID는 파일 오류다. */
    @Test fun malformedTargetIsNotTreatedAsUnknown() = runTest {
        for (target in listOf<Any>("unknown", 3, JSONObject().put("ledgerId", "bad"))) {
            val bytes = ImportPreviewFixture.edited(changeMapping = { it.put("target", target) })
            assertFailsWith<ImportFileException> { ImportPreviewParser.parse(bytes) }
        }
    }

    /** 업무 입력 16KiB 한도를 직렬화 원문에 재사용하지 않는다. */
    @Test fun rawPayloadMayExceedBusinessTextLimit() = runTest {
        val raw = JSONObject().put("unrelated", "가".repeat(20_000)).toString()
        val parsed = ImportPreviewParser.parse(ImportPreviewFixture.edited(changeSnapshot = { ImportPreviewFixture.row(it, 0, raw) }))
        assertEquals(raw, parsed.rows.first().rawJson)
        assertEquals(ImportPreviewParser.digest(raw), parsed.rows.first().rawSha256)
    }
}
