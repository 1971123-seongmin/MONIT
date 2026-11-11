package com.monit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import com.monit.core.designsystem.R
import com.monit.core.designsystem.theme.MonitTheme

/** 목록 선택만으로 적용하지 않는다. 확인 콜백은 선택 ID를 다시 검증하는 소유자에게 전달한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitAccountBottomSheet(accounts: List<MonitAccountOption>, selectedId: String?, onSelect: (String) -> Unit,
    onApply: (String) -> Unit, onAddAccount: () -> Unit, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, loading: Boolean = false, errorMessage: String? = null, onRetry: (() -> Unit)? = null) {
    val locked by rememberUpdatedState(loading)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !locked || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!locked) onDismissRequest() }, sheetState = sheetState,
        dragHandle = null, containerColor = MonitTheme.colors.surfaceContainer,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius), modifier = modifier) {
        MonitAccountSheetContent(accounts, selectedId, onSelect, onApply, onAddAccount, onDismissRequest,
            loading = loading, errorMessage = errorMessage, onRetry = onRetry)
    }
}

/** 제목·적용/닫기 고정, 목록만 스크롤한다. 조회 실패/로딩을 미등록으로 바꾸지 않는다. */
@Composable
fun MonitAccountSheetContent(accounts: List<MonitAccountOption>, selectedId: String?, onSelect: (String) -> Unit,
    onApply: (String) -> Unit, onAddAccount: () -> Unit, onDismiss: () -> Unit,
    modifier: Modifier = Modifier, loading: Boolean = false, errorMessage: String? = null, onRetry: (() -> Unit)? = null) {
    val selectable = accounts.singleOrNull { it.id == selectedId && it.disabledReason == null }
    val empty = accounts.isEmpty() && !loading && errorMessage == null
    val windowSize = LocalWindowInfo.current.containerSize
    val maxHeight = with(LocalDensity.current) { windowSize.height.toDp() * .9f }
    Surface(modifier, color = MonitTheme.colors.surfaceContainer, contentColor = MonitTheme.colors.onSurface,
        shape = RoundedCornerShape(MonitTheme.sizes.controlRadius)) {
    Column(Modifier.heightIn(max = maxHeight)
        .padding(MonitTheme.spacing.content), verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline)) {
        Text(stringResource(R.string.monit_account_picker_title), style = MaterialTheme.typography.titleLarge)
        if (!empty && !loading && errorMessage == null && accounts.any { it.id == selectedId }) Text(stringResource(R.string.monit_choose_account),
            style = MaterialTheme.typography.bodyMedium, color = MonitTheme.colors.muted)
        MonitAccountPicker(accounts, selectedId, onSelect, onAddAccount,
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            loading = loading, errorMessage = errorMessage, onRetry = onRetry)
        if (!empty) MonitButton(stringResource(R.string.monit_apply_account),
            { selectable?.let { onApply(it.id) } }, Modifier.fillMaxWidth(),
            enabled = selectable != null && errorMessage == null, loading = loading)
        MonitSecondaryButton(stringResource(R.string.monit_close), onDismiss, Modifier.fillMaxWidth(), enabled = !loading)
    }
    }
}
