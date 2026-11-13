package com.monit.feature.home.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.monit.core.designsystem.component.*
import com.monit.core.designsystem.component.button.*
import com.monit.core.designsystem.component.textfield.MonitTextField
import com.monit.core.designsystem.theme.MonitTheme

/** 비밀번호 값은 호출 Route의 비저장 메모리 입력이다. 화면 표시 모델에 암호·URI를 넣지 않는다. */
@Composable
fun BackupScreen(
    state: BackupState,
    password: String,
    confirmation: String,
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
    onExport: () -> Unit,
    onOpen: () -> Unit,
    onSubmit: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onResolve: () -> Unit,
    onNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val phase = state.phase
    val ui = state.ui
    var confirm by remember { mutableStateOf(false) }
    val title = when (phase) {
        BackupPhase.EXPORT_PASSWORD -> "백업 비밀번호"
        BackupPhase.RESTORE_PASSWORD -> "복원 파일 확인"
        BackupPhase.PREVIEW -> "복원 영향 확인"
        BackupPhase.RESULT -> "복원 결과"
        BackupPhase.UNKNOWN -> "복원 결과 확인"
        BackupPhase.EXPORTED_UNVERIFIED -> "백업 검증 미완료"
        else -> "백업과 복원"
    }

    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(MonitTheme.spacing.content)
                .testTag("backup-screen"),
            verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.inline),
        ) {
            MonitTopBar(
                title = title,
                navigationIcon = if (phase !in setOf(BackupPhase.WORKING, BackupPhase.UNKNOWN)) (
                    {
                        IconButton(
                            onClick = when (phase) {
                                BackupPhase.ENTRY -> onBack
                                BackupPhase.RESULT -> onDone
                                else -> onCancel
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "돌아가기",
                            )
                        }
                    }
                    ) else null,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MonitTheme.spacing.content),
            ) {
                ui.message?.let {
                    Text(
                        it,
                        color = MonitTheme.colors.error,
                    )
                }

                when (phase) {
                    BackupPhase.ENTRY -> {
                        MonitCard(Modifier.fillMaxWidth()) {
                            Text("파일 백업")

                            Text("비밀번호로 보호한 파일\n보관 위치: 사용자가 선택")
                        }

                        Text(ui.lastBackup)

                        Text("백업에서 복원\n파일 선택 → 비밀번호 → 검증")

                        Text("현재 자료 전체 대체 영향을 확인한 뒤 마지막 화면에서 직접 확정해요.")
                    }

                    BackupPhase.EXPORT_PASSWORD, BackupPhase.RESTORE_PASSWORD -> {
                        Text("선택한 문서")

                        Text(if (phase == BackupPhase.EXPORT_PASSWORD) "비밀번호를 잊으면 이 파일을 복원할 수 없어요. 앱은 비밀번호를 저장하지 않아요." else "백업할 때 사용한 비밀번호를 입력해주세요. 분실한 비밀번호는 복구할 수 없어요.")

                        MonitTextField(
                            value = password,
                            onValueChange = onPassword,
                            label = if (phase == BackupPhase.EXPORT_PASSWORD) "비밀번호" else "백업 비밀번호",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            visualTransformation = PasswordVisualTransformation(),
                        )

                        if (phase == BackupPhase.EXPORT_PASSWORD) {
                            MonitTextField(
                                value = confirmation,
                                onValueChange = onConfirmation,
                                label = "비밀번호 다시 입력",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                visualTransformation = PasswordVisualTransformation(),
                            )

                            Text("15~128자 · 긴 고유 암호구를 사용해주세요.\n비밀번호로 암호화한 파일을 저장한 뒤, 다시 열어 검증해야 백업이 완료돼요.")
                        } else {
                            Text("아직 현재 장부를 바꾸지 않아요.\n파일을 검증한 뒤 포함 내용과 전체 교체 영향을 먼저 보여드려요.")

                            Text("백업 파일 전체 복원입니다. 기존 자료에 일부만 합치는 가져오기가 아니에요.")
                        }
                    }

                    BackupPhase.WORKING -> {
                        MonitStatusPanel(
                            title = ui.progress,
                            description = "앱을 닫지 말고 잠시 기다려주세요. 결과를 확인한 뒤 다음 행동을 안내해요.",
                        )
                    }

                    BackupPhase.PREVIEW -> {
                        Text("검증한 백업 · ${ui.sourceDate}\n${ui.summary}")

                        ui.counts.forEach { count -> Text("${count.name} · 현재 ${count.current} → 백업 ${count.incoming}") }

                        Text(ui.anchorSummary)

                        Text("현재 자료에 합치지 않아요. 백업에 없는 이후 기록과 변경은 사라집니다.")

                        Text(ui.originals)

                        Text("작성 중 입력·기기 알림 권한·예약은 제외돼요. 복원 전 입력 화면은 다시 확인해야 해요.")

                        ui.legacyNotice?.let { Text(it) }

                        Text("현재 자료의 복구용 사본을 확보한 뒤 교체해요. 위 영향을 확인했다면 아래 버튼을 눌러주세요.")
                    }

                    BackupPhase.EXPORTED -> {
                        Text(
                            "백업 파일 검증을 마쳤어요",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        Text("저장한 파일을 다시 열어 암호 인증과 내용을 확인했어요. 비밀번호와 파일을 안전하게 보관해주세요.")

                        Text(ui.lastBackup)
                    }

                    BackupPhase.EXPORTED_UNVERIFIED -> {
                        Text(
                            "파일을 내보내기 시작했지만 검증을 마치지 못했어요",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        Text("저장한 파일을 다시 열어 확인하지 못했어요. 이 파일을 검증된 백업으로 사용하지 마세요.")

                        Text("현재 장부는 바뀌지 않았어요. 이전 성공 백업 파일은 그대로 보관해주세요.")

                        Text(ui.lastBackup)
                    }

                    BackupPhase.RESULT -> RestoreOutcomeBody(ui)

                    BackupPhase.UNKNOWN -> {
                        Text("복원 결과 확인이 필요해요")

                        Text("같은 복원을 다시 실행하지 마세요. 완료 표식으로 현재 장부 상태를 확인해야 해요. 확정 여부를 확인하기 전에는 기존 자료 유지나 복원 성공을 추정하지 않아요.")
                    }
                }
            }

            when (phase) {
                BackupPhase.ENTRY -> {
                    BackupAction(
                        text = "백업 파일 만들기",
                        onClick = onExport,
                    )

                    BackupAction(
                        text = "복원할 파일 선택",
                        onClick = onOpen,
                    )

                    BackupAction(
                        text = "돌아가기",
                        onClick = onBack,
                        secondary = true,
                    )
                }

                BackupPhase.EXPORT_PASSWORD, BackupPhase.RESTORE_PASSWORD -> {
                    BackupAction(
                        text = if (phase == BackupPhase.EXPORT_PASSWORD) "백업 파일 저장" else "파일 검증",
                        onClick = onSubmit,
                    )

                    BackupAction(
                        text = "취소 · 현재 자료 유지",
                        onClick = onCancel,
                        secondary = true,
                    )
                }

                BackupPhase.PREVIEW -> {
                    BackupAction(
                        text = "현재 자료 전체를 교체",
                        onClick = { confirm = true },
                    )

                    BackupAction(
                        text = "취소 · 현재 자료 유지",
                        onClick = onCancel,
                        secondary = true,
                    )
                }

                BackupPhase.EXPORTED, BackupPhase.EXPORTED_UNVERIFIED -> BackupAction(
                    text = "백업과 복원으로",
                    onClick = onCancel,
                )

                BackupPhase.RESULT -> {
                    if (ui.notificationResetNeeded) BackupAction(
                        text = "알림 재설정 다시 시도",
                        onClick = onNotifications,
                    )

                    BackupAction(
                        text = "장부 보기",
                        onClick = onDone,
                    )
                }

                BackupPhase.UNKNOWN -> BackupAction(
                    text = "복원 결과 확인",
                    onClick = onResolve,
                )

                BackupPhase.WORKING -> Unit
            }
        }

        if (confirm && phase == BackupPhase.PREVIEW) MonitDialog(
            onDismissRequest = { confirm = false },
            title = { Text("현재 자료 전체를 교체할까요?") },
            actions = {
                BackupAction(
                    text = "취소 · 현재 자료 유지",
                    onClick = { confirm = false },
                    secondary = true,
                )

                BackupAction(
                    text = "확인 · 전체 교체",
                    onClick = {
                        confirm = false
                        onRestore()
                    },
                )
            },
        ) {
            Text("백업에 없는 이후 기록과 변경은 사라집니다. ${ui.summary}. 검증한 복구용 사본을 확보하지 못하면 교체하지 않아요.")
        }
    }
}

@Composable
fun RestoreOutcomeBody(ui: BackupUiState) {
    Text(
        "백업 자료를 복원했어요",
        style = MaterialTheme.typography.titleLarge,
    )

    Text("${ui.sourceDate} 백업\n${ui.summary}")

    if (ui.notificationResetNeeded) {
        Text("알림 재설정이 필요해요")

        Text("자료 복원은 완료됐지만 알림 준비를 마치지 못했어요. 알림 재설정만 다시 시도할 수 있어요.")
    } else Text("알림 재설정을 요청했어요. 표시 여부와 시각은 기기 상태에 따라 달라요.")

    Text("기기 알림 권한은 복원되지 않아요. 이전 입력 화면은 다시 확인해주세요. 같은 파일을 다시 복원할 필요는 없어요.")

    Text("기록과 수동 납입 확인은 계속 이용할 수 있어요.")
}

@Composable
private fun BackupAction(
    text: String,
    onClick: () -> Unit,
    secondary: Boolean = false,
) {
    MonitButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        type = if (secondary) MonitButtonType.Secondary else MonitButtonType.Primary,
    )
}
