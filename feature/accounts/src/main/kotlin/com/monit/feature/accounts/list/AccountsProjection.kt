package com.monit.feature.accounts.list

import com.monit.core.model.AccountNature
import com.monit.core.model.AccountStatus
import com.monit.core.model.LedgerSnapshot
import com.monit.core.model.assetFlows
import com.monit.core.model.sumSignedAmounts
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

internal fun LedgerSnapshot.toAccountsUiState(asOf: LocalDate, month: YearMonth): AccountsUiState {
    val format = NumberFormat.getIntegerInstance(Locale.KOREA)
    fun money(amount: Long) = format.format(amount) + "원"
    val active = accounts.filter { it.status == AccountStatus.ACTIVE }
    val confirmed = active.mapNotNull { balances[it.id] }
    val unknown = active.count { balances[it.id] == null }
    val assets = active.filter { it.nature == AccountNature.SAVINGS || it.nature == AccountNature.INVESTMENT }
    val knownAssets = assets.mapNotNull { balances[it.id] }
    val flows = assetFlows(transactions, month)
    val summary = when {
        accounts.isEmpty() -> "등록된 계좌가 없어요"
        confirmed.isEmpty() -> "확인된 잔액이 아직 없어요"
        else -> "확인된 잔액 합계 " + money(sumSignedAmounts(confirmed))
    }
    return AccountsUiState(
        summary + if (unknown > 0) " · 잔액 미확인 ${unknown}개 제외" else "",
        accounts.map { account ->
            val balance = balances[account.id]
            val nature = when (account.nature) {
                AccountNature.GENERAL -> "일반"
                AccountNature.SAVINGS -> "저축"
                AccountNature.INVESTMENT -> "투자"
            }
            AccountRowUiState(
                account.id,
                account.name + " · " + nature,
                balance?.let(::money) ?: "잔액 미확인",
                when {
                    account.status == AccountStatus.ARCHIVED -> "보관된 계좌 · 현재 잔액 합계에서 제외"
                    balance == null -> "현재 잔액 합계에서 제외"
                    else -> "장부 기준 잔액 · 은행 확인 금액이 아니에요"
                },
            )
        },
        asOf.toString(),
        month.toString(),
        if (knownAssets.isEmpty()) "저축·투자 장부 잔액: 확인된 금액 없음" else "확인된 저축·투자 장부 소계 " + money(sumSignedAmounts(knownAssets)),
        "납입 ${money(flows.deposits)} · 인출 ${money(flows.withdrawals)} · 순납입 ${money(flows.net)}",
        "현재 잔액 합계: 활성 KRW 계좌 ${active.size - unknown}개 포함 · 미확인 ${unknown}개/보관 ${accounts.size - active.size}개 제외\n저축·투자 소계: 확인 ${knownAssets.size}개 포함 · 미확인 ${assets.size - knownAssets.size}개 제외 · 일반 계좌 제외",
    )
}
