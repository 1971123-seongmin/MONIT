package com.monit.feature.accounts.importing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monit.core.designsystem.component.MonitCard
import com.monit.core.designsystem.component.MonitDialog
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme

/** 긴 원본/계좌 목록도 한 거대한 Column으로 미리 합성하지 않는다. */
internal fun LazyListScope.importReviewControls(
    ui: ImportWorkflowUiState,
    selectedGroupCount: Int,
    onConfirmTarget: () -> Unit,
    onMapAccount: (String, String, String) -> Unit,
    onKeepAnchor: (String) -> Unit,
    onConfirmAnchor: (String, String, String) -> Unit,
    onAnchorAmount: (String, String) -> Unit,
    onAnchorReason: (String, String) -> Unit,
    onConfirmGroup: () -> Unit,
    onBackup: () -> Unit,
    onApply: () -> Unit,
) {
    item("import-target") {
        MonitCard(Modifier.fillMaxWidth()) {
            Text("계좌 대응과 기준 확인")

            Text("계좌 이름으로 자동 생성하거나 합치지 않아요. 새 계좌가 필요하면 계좌 화면에서 등록한 뒤 파일을 다시 검토해 주세요.")

            MonitButton(
                text = if (ui.targetConfirmed) "현재 장부 대상으로 확인됨" else "현재 장부를 대상으로 명시 확인",
                enabled = !ui.targetConfirmed,
                onClick = onConfirmTarget,
            )
        }
    }

    items(
        items = ui.links,
        key = { "import-link:${it.namespace.length}:${it.namespace}:${it.sourceId}" },
    ) { link ->
        ImportAccountLink(
            link = link,
            accounts = ui.accounts,
            enabled = ui.targetConfirmed,
            onMapAccount = onMapAccount,
        )
    }

    val selectedAccounts = ui.links.mapTo(mutableSetOf()) { it.selectedAccountId }

    items(
        items = ui.accounts.filter { it.id in selectedAccounts },
        key = { "import-anchor:${it.id}" },
    ) { account ->
        ImportAccountBasis(
            account = account,
            enabled = ui.targetConfirmed,
            onKeep = onKeepAnchor,
            onConfirm = onConfirmAnchor,
            onAmount = onAnchorAmount,
            onReason = onAnchorReason,
        )
    }

    item("import-group") {
        Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            Text("행별 확인은 아래 원본 행에서 진행해 주세요. 날짜·금액 일치만으로 이체를 자동 묶지 않아요.")

            MonitButton(
                text = "선택한 두 원본을 한 이체로 명시 확인 ($selectedGroupCount/2)",
                enabled = selectedGroupCount == 2,
                onClick = onConfirmGroup,
            )
        }
    }

    items(
        items = ui.currencyTotals,
        key = { "import-currency:$it" },
    ) { Text(it) }

    item("import-final-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
            MonitButton(
                text = if (ui.backupVerified) "현재 기준 백업 검증됨 · 새 백업 만들기" else "반영 전 현재 장부 백업",
                enabled = ui.targetConfirmed,
                type = MonitButtonType.Secondary,
                onClick = onBackup,
            )

            MonitButton(
                text = if (ui.hasFinancialChanges) "최종 영향 확인 후 반영" else "금융 신규 반영 없이 검토 결과만 보관",
                enabled = ui.canApply && ui.backupVerified,
                onClick = onApply,
            )
        }
    }
}

@Composable
private fun ImportAccountLink(
    link: ImportAccountLinkUiState,
    accounts: List<ImportAccountUiState>,
    enabled: Boolean,
    onMapAccount: (String, String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    MonitCard(Modifier.fillMaxWidth()) {
        Text(link.label)

        MonitButton(
            text = accounts.singleOrNull { it.id == link.selectedAccountId }?.name ?: "대응할 계좌 선택",
            enabled = enabled,
            type = MonitButtonType.Secondary,
            onClick = { expanded = true },
        )
    }

    if (expanded) MonitDialog(
        onDismissRequest = { expanded = false },
        title = { Text("대응할 계좌 선택") },
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            items(
                items = accounts.filter { it.selectable },
                key = { it.id },
            ) { account ->
                MonitButton(
                    text = "${account.name} · 식별자 ${account.id.takeLast(8)}",
                    onClick = {
                        expanded = false
                        onMapAccount(link.namespace, link.sourceId, account.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportAccountBasis(
    account: ImportAccountUiState,
    enabled: Boolean,
    onKeep: (String) -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onAmount: (String, String) -> Unit,
    onReason: (String, String) -> Unit,
) {
    MonitCard(Modifier.fillMaxWidth()) {
        Text("${account.name} · 고정 기준일 ${account.anchorDate}")

        Text("기존 기준액 ${account.anchorAmount}\n${account.choice}")

        MonitButton(
            text = "이 계좌의 기존 기준 유지 확인",
            enabled = enabled,
            type = MonitButtonType.Secondary,
            onClick = { onKeep(account.id) },
        )

        MonitTextField(
            value = account.draft.amount,
            onValueChange = { onAmount(account.id, it) },
            label = "명시 확정할 기준액 (원화 정수)",
            enabled = enabled && account.selectable,
        )

        MonitTextField(
            value = account.draft.reason,
            onValueChange = { onReason(account.id, it) },
            label = "기준액 확정 사유",
            enabled = enabled && account.selectable,
        )

        if (account.draft.dirty) Text("입력 중인 기준액을 명시 확인하거나 기존 기준 유지를 선택해 주세요.")

        MonitButton(
            text = "입력한 기준액을 이번 반영 집합에 포함",
            enabled = enabled && account.selectable && account.draft.amount.isNotBlank(),
            onClick = { onConfirm(account.id, account.draft.amount, account.draft.reason) },
        )
    }
}
