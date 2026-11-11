package com.monit.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.app.LedgerEntryState
import com.monit.app.LedgerEntryViewModel
import com.monit.core.designsystem.component.MonitStatusPanel
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.LedgerEntryProblem

@Composable
fun MonitLauncher(viewModel: LedgerEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MonitTheme {
        Surface(
            Modifier.fillMaxSize(), color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface
        ) {
            when (val entry = state) {
                is LedgerEntryState.Ready -> MonitApp(entry.context)
                else -> Box(
                    Modifier.fillMaxSize().safeDrawingPadding().padding(MonitTheme.spacing.content),
                    contentAlignment = Alignment.Center,
                ) {
                    when (entry) {
                        LedgerEntryState.Loading -> MonitStatusPanel(
                            "장부 확인 중",
                            "기존 기록을 안전하게 확인하고 있어요."
                        )

                        LedgerEntryState.Failed -> MonitStatusPanel(
                            "장부를 열지 못했어요", "기존 자료는 그대로 보존됩니다. 다시 확인해 주세요.", error = true,
                            action = { MonitButton("다시 확인", onClick = viewModel::retry) },
                        )

                        is LedgerEntryState.RecoveryRequired -> MonitStatusPanel(
                            "장부 확인이 필요해요",
                            when (entry.reason) {
                                LedgerEntryProblem.MISSING_METADATA -> "기록의 장부 정보를 찾지 못했습니다. 기존 자료를 보존했어요."
                                LedgerEntryProblem.INVALID_METADATA -> "기록의 장부 정보가 올바르지 않습니다. 기존 자료를 보존했어요."
                                LedgerEntryProblem.MULTIPLE_LEDGERS -> "여러 장부 정보가 있어 안전하게 열 수 없습니다. 기존 자료를 보존했어요."
                            },
                            error = true,
                            action = { MonitButton("장부 다시 확인", onClick = viewModel::retry) },
                        )

                        is LedgerEntryState.Ready -> Unit
                    }
                }
            }
        }
    }
}
