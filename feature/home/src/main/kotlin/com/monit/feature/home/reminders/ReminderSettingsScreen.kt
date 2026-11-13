package com.monit.feature.home.reminders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.MonitButton
import com.monit.core.designsystem.component.button.MonitButtonType
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.core.model.LedgerContext
import com.monit.core.model.REMINDER_DELAY_NOTICE
import com.monit.feature.home.payments.PaymentTopBar

@Composable
fun ReminderSettingsRoute(
    context: LedgerContext,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: (Boolean) -> Unit,
    onReschedule: () -> Unit,
    viewModel: ReminderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SideEffect { viewModel.bind(context) }

    LifecycleResumeEffect(context) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    BackHandler(onBack = onBack)

    ReminderSettingsScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onEnabled = viewModel::setEnabled,
        onRuleEnabled = viewModel::setRuleEnabled,
        onRequestPermission = onRequestPermission,
        onOpenSettings = { onOpenSettings(state.ui.channelBlocked && !state.ui.permissionDenied && !state.ui.appBlocked) },
        onReschedule = onReschedule,
    )
}

@Composable
fun ReminderSettingsScreen(
    state: ReminderSettingsState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRuleEnabled: (String, Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onReschedule: () -> Unit,
) {
    val ui = state.ui
    val ready = state.phase == ReminderSettingsPhase.READY

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("reminder-settings-list")
            .imePadding()
            .padding(horizontal = MonitTheme.spacing.content),
        verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
    ) {
        item(
            "title",
            "header",
        ) {
            PaymentTopBar(
                title = "알림 설정",
                onBack = onBack,
            )
        }

        if (state.phase == ReminderSettingsPhase.FAILED) item(
            "failure",
            "status",
        ) {
            MonitStatusPanel(
                title = "알림 설정을 확인하지 못했어요",
                description = "금융 기록과 납입 상태는 보존됩니다. 알림 허용 여부를 성공으로 추정하지 않아요.",
                error = true,
                action = {
                    MonitButton(
                        text = "다시 확인",
                        onClick = onRetry,
                    )
                },
            )
        }

        if (state.phase == ReminderSettingsPhase.LOADING) item(
            "loading",
            "status",
        ) {
            Text("실제 권한과 저장한 알림 선호를 확인하고 있어요.")
        }

        if (state.phase in setOf(ReminderSettingsPhase.READY, ReminderSettingsPhase.SAVING)) {
            item(
                "preference",
                "settings",
            ) {
                MonitCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = MonitTheme.sizes.outline,
                            color = MonitTheme.colors.outline,
                            shape = RoundedCornerShape(MonitTheme.sizes.controlRadius),
                        ),
                    verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
                ) {
                    MonitBadge(
                        text = ui.status,
                        tone = if (ui.enabled && ui.allowed) MonitBadgeTone.Selected else MonitBadgeTone.Neutral,
                    )

                    Text(
                        text = ui.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    MonitButton(
                        text = if (ui.enabled) "전체 알림 끄기" else "전체 알림 켜기",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ready,
                        loading = state.phase == ReminderSettingsPhase.SAVING,
                        onClick = { onEnabled(!ui.enabled) },
                    )

                    Text(
                        text = "전체 알림을 켜면 아래에서 켜진 일정이 대상이에요. 끄더라도 일정별 선택·거래·납입 상태는 바뀌지 않아요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MonitTheme.colors.muted,
                    )
                }
            }

            item(
                "os-state",
                "access",
            ) {
                Text("실제 OS 상태 · 권한 ${ui.permission} / 앱 ${ui.app} / 채널 ${ui.channel}")

                if (ui.permissionDenied) {
                    MonitButton(
                        text = "알림 권한 허용",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ready,
                        onClick = onRequestPermission,
                    )
                }

                MonitButton(
                    text = "알림 설정 열기",
                    modifier = Modifier.fillMaxWidth(),
                    type = MonitButtonType.Secondary,
                    onClick = onOpenSettings,
                )

                Text("설정에서 돌아오면 실제 권한·앱·채널 상태를 다시 확인해요. 허용 상태와 사용자가 저장한 선호는 구분합니다.")
            }

            item(
                "schedule",
                "schedule",
            ) {
                Text("기본 시점 · 서울 기준 예정일 전날·당일·다음 날 오전 9시 이후 · 하루 요약 1회")

                Text(REMINDER_DELAY_NOTICE)

                Text(if (ui.dirty) "알림 재조정 대기 · ${ui.next}" else ui.next)

                if (ui.dirty || ui.failed) {
                    MonitButton(
                        text = if (ui.failed) "알림 재조정 다시 시도" else "알림 일정 다시 확인",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ready,
                        onClick = onReschedule,
                    )
                }

                if (ui.failed) Text("OS 알림 적용을 확인하지 못했어요. 금융 기록은 보존되며 알림 도착을 보장하지 않아요.")
            }

            item(
                "rules-title",
                "header",
            ) {
                Text("일정별 알림 선호")

                Text("미설정 일정의 기본값은 켜짐이며, 전체 알림을 직접 켜기 전에는 요청하지 않아요.")

                Text("켜진 일정의 활성 미완료·재확인 항목만 대상이에요. 완료·건너뜀·종료 이후 항목은 제외합니다.")
            }

            items(
                ui.rules,
                key = { it.id },
                contentType = { "rule-preference" },
            ) { rule ->
                MonitCard(Modifier.fillMaxWidth()) {
                    Text(rule.name)

                    Text(rule.preference)

                    MonitButton(
                        text = "${rule.name} 알림 ${if (rule.enabled) "끄기" else "켜기"}",
                        modifier = Modifier.fillMaxWidth(),
                        type = MonitButtonType.Secondary,
                        enabled = ready,
                        onClick = { onRuleEnabled(rule.id, !rule.enabled) },
                    )
                }
            }

            if (ui.rules.isEmpty()) item(
                "empty-rules",
                "status",
            ) {
                Text("등록한 반복 일정이 없어요. 월별 납입에서 먼저 등록해 주세요.")
            }
        }

        ui.message?.let { message ->
            item(
                "message",
                "message",
            ) { Text(message) }
        }
    }
}
