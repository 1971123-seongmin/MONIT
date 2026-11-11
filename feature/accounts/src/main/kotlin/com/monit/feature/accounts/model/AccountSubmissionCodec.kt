package com.monit.feature.accounts.model

import android.os.Bundle
import com.monit.core.model.AccountNature
import com.monit.core.model.CreateAccount
import com.monit.core.model.LedgerContext
import java.time.DateTimeException
import java.time.LocalDate

/** 화면 saved-state 전용 형식. Room 요청/영속 초안 형식과 무관하다. */
internal object AccountSubmissionCodec {
    fun encode(submission: SubmittedAccountState): Bundle = Bundle().apply {
        putInt("version", 2)
        putString("ledgerId", submission.context.ledgerId)
        putString("generation", submission.context.storageGeneration)
        putString("operationId", submission.operationId)
        putString("accountId", submission.value.id)
        putString("anchorId", submission.value.anchorId)
        putString("name", submission.value.name)
        putString("nature", submission.value.nature.name)
        putString("anchorDate", submission.value.anchorDate.toString())
        putString("unknownReason", submission.value.unknownReason)
    }

    /** 기존 v1 task도 같은 제출로 읽고, 알 수 없는 형식은 새 제출로 대체하지 않는다. */
    @Suppress("DEPRECATION")
    fun decode(raw: Any, context: LedgerContext): SubmittedAccountState {
        val fields = when (raw) {
            is Bundle -> {
                require(raw.get("version") == 2)
                listOf("ledgerId", "generation", "operationId", "accountId", "anchorId", "name",
                    "nature", "anchorDate", "unknownReason").map { key ->
                    requireNotNull(raw.get(key) as? String)
                }
            }
            is ArrayList<*> -> {
                require(raw.size == 10 && raw[0] == "1" && raw.all { it is String })
                raw.drop(1).map { it as String }
            }
            else -> throw IllegalArgumentException("Unsupported submission state")
        }
        require(LedgerContext(fields[0], fields[1]) == context)
        val date = try {
            LocalDate.parse(fields[7])
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Invalid submission date", error)
        }
        require(date.toString() == fields[7] && date >= LocalDate.of(1900, 1, 1))
        return SubmittedAccountState(context, fields[2], CreateAccount(
            fields[3], fields[4], fields[5], AccountNature.valueOf(fields[6]), date, fields[8],
        ))
    }
}
