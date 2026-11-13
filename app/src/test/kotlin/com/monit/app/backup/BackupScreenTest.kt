package com.monit.app.backup

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.monit.core.designsystem.theme.MonitTheme
import com.monit.feature.home.backup.*
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupScreenTest {
    @get:Rule val compose = createComposeRule()

    /** 미리보기 첫 버튼은 확인 dialog만 열고 마지막 명시 확인만 restore를 호출한다. */
    @Test fun replacementRequiresFinalExplicitConfirmation() {
        var restores = 0
        compose.setContent { MonitTheme { TestBackupScreen(BackupPhase.PREVIEW, restore = { restores++ }) } }
        compose.onNodeWithText("현재 자료 전체를 교체").performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("현재 자료 전체를 교체할까요?").assertIsDisplayed()
        compose.onAllNodesWithText("취소 · 현재 자료 유지")[1].performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("현재 자료 전체를 교체").performClick()
        compose.onNodeWithText("확인 · 전체 교체").performClick()
        assertEquals(1, restores)
    }

    /** 재설정은 별도 행동이며 복원 재실행 버튼을 제공하지 않는다. */
    @Test fun restoredResultOffersNotificationOnlyRetry() {
        var retries = 0
        compose.setContent { MonitTheme { TestBackupScreen(BackupPhase.RESULT, notifications = { retries++ }) } }
        compose.onNodeWithText("알림 재설정 다시 시도").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("현재 자료 전체를 교체").assertDoesNotExist()
    }

    /** 결과 불명은 성공/이전 유지의 추측과 재복원 행동 대신 결과 확인만 제공한다. */
    @Test fun unknownResultOnlyAllowsStatusResolution() {
        var resolutions = 0
        compose.setContent { MonitTheme { TestBackupScreen(BackupPhase.UNKNOWN, resolve = { resolutions++ }) } }
        compose.onAllNodesWithText("복원 결과 확인").assertCountEquals(2)
        compose.onAllNodesWithText("복원 결과 확인")[1].performClick()
        assertEquals(1, resolutions)
        compose.onNodeWithText("백업 자료를 복원했어요").assertDoesNotExist()
        compose.onNodeWithText("현재 자료 전체를 교체").assertDoesNotExist()
    }

    /** 공통 textfield의 두 비밀번호 입력은 Password semantics/변환을 사용한다. */
    @Test fun bothSecretFieldsUsePasswordSemantics() {
        compose.setContent { MonitTheme { TestBackupScreen(BackupPhase.EXPORT_PASSWORD) } }
        val passwords = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        passwords.assertCountEquals(2)
        passwords[0].assertIsDisplayed()
        passwords[1].performScrollTo().assertIsDisplayed()
    }
}

@androidx.compose.runtime.Composable
internal fun TestBackupScreen(
    phase: BackupPhase,
    restore: () -> Unit = {},
    notifications: () -> Unit = {},
    resolve: () -> Unit = {},
) {
    BackupScreen(
        state = BackupState(
            phase,
            BackupUiState(
                sourceDate = "2026.11.12 20:00",
                summary = if (phase == BackupPhase.RESULT) "전체 30개 · 현재 장부에 반영 완료" else "현재 34개 → 백업 30개로 전체 교체",
                counts = listOf(BackupCountUiState("계좌", "3", "3"), BackupCountUiState("거래", "14", "10")),
                originals = "포함 원문 0개",
                anchorSummary = "복원 기준일 · 계좌 3개 모두 2026.10.31",
            ),
        ),
        password = "synthetic-password-2026",
        confirmation = "synthetic-password-2026",
        onPassword = {},
        onConfirmation = {},
        onExport = {},
        onOpen = {},
        onSubmit = {},
        onRestore = restore,
        onCancel = {},
        onBack = {},
        onResolve = resolve,
        onNotifications = notifications,
        onDone = {},
    )
}
