package com.monit.core.data.backup

import java.io.File

enum class ProbeProfile { SMALL, RECORD_LIMIT, MIXED_LIMIT }

data class ProbeFixtureInfo(
    val profile: String,
    val sourceBytes: Int,
    val recordTotal: Int,
    val recordCounts: Map<String, Int>,
    val sourceSha256: String,
    val expectedIncome: Long,
    val expectedExpense: Long,
    val expectedActive: Int,
    val expectedDeleted: Int,
    val semanticSha256: String,
)

data class ProbeFixturePack(val directory: File, val current: File, val source: File, val info: ProbeFixtureInfo)

data class ProbeState(
    val semanticSha256: String,
    val recordTotal: Int,
    val active: Int,
    val deleted: Int,
    val income: Long,
    val expense: Long,
    val generation: String,
)
