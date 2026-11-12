package com.monit.feature.accounts.importing

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.monit.core.designsystem.theme.MonitTheme

@Preview(name = "이관 미리보기", widthDp = 360, heightDp = 708)
@Preview(name = "이관 미리보기 어두운 테마", widthDp = 360, heightDp = 708, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ImportPreviewReadyPreview() {
    PreviewSurface {
        ImportPreviewScreen(
            phase = ImportPreviewPhase.READY,
            ui = ImportPreviewUiState(
                sourceSummary = "합성 검토 예시",
                sourceRowCount = "2건",
                candidateCount = "1건",
                heldCount = "1건",
                excludedCount = "0건",
                transactionCount = "1건",
                completeness = "일부 관계 확보 미확인 · 전체 범위 검증 아님",
                reconciliation = "지원 후보만 집계 · 전체 금액 대사 미확인",
                rows = listOf(
                    ImportPreviewRowUiState("synthetic-1", "합성 지출", "반영예정 후보", "2026-11-02 · 5,000원", "합성 원문 근거", "합성 원문"),
                    ImportPreviewRowUiState("synthetic-2", "합성 미확보 관계", "보류", "계좌 대응 확인 필요", "합성 원문 근거", "합성 원문"),
                ),
            ),
            onSelectFile = {},
            onCheckRevision = {},
            onReviewAgain = {},
            onBack = {},
        )
    }
}

@Preview(name = "이관 파일 오류 큰 글꼴", widthDp = 320, heightDp = 708, fontScale = 2f)
@Composable
private fun ImportPreviewErrorPreview() {
    PreviewSurface {
        ImportPreviewScreen(
            phase = ImportPreviewPhase.FAILED,
            ui = ImportPreviewUiState(),
            onSelectFile = {},
            onCheckRevision = {},
            onReviewAgain = {},
            onBack = {},
            problem = "파일을 해석하지 못했어요. 원본과 기존 기록은 보존됩니다.",
        )
    }
}

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    MonitTheme {
        Surface(
            color = MonitTheme.colors.surfaceContainer,
            contentColor = MonitTheme.colors.onSurface,
            content = content,
        )
    }
}
