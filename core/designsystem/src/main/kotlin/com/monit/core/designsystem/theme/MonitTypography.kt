package com.monit.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.monit.core.designsystem.R

@OptIn(ExperimentalTextApi::class)
private val NotoSansKr = FontFamily(
    Font(R.font.noto_sans_kr, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.noto_sans_kr, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = NotoSansKr, fontWeight = weight,
    fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = 0.sp,
)

/** Figma 40:87의 5개 글자 스타일. 모든 M3 fallback 역할에도 같은 오프라인 글꼴을 사용한다. */
internal val MonitTypography = Typography(
    displayLarge = textStyle(24, 36, FontWeight.Medium),
    displayMedium = textStyle(24, 36, FontWeight.Medium),
    displaySmall = textStyle(24, 36, FontWeight.Medium),
    headlineLarge = textStyle(24, 36, FontWeight.Medium),
    headlineMedium = textStyle(24, 36, FontWeight.Medium),
    headlineSmall = textStyle(24, 36, FontWeight.Medium),
    titleLarge = textStyle(24, 36, FontWeight.Medium),
    titleMedium = textStyle(14, 22, FontWeight.Medium),
    titleSmall = textStyle(14, 22, FontWeight.Medium),
    bodyLarge = textStyle(16, 24), bodyMedium = textStyle(14, 24), bodySmall = textStyle(12, 16),
    labelLarge = textStyle(14, 22, FontWeight.Medium),
    labelMedium = textStyle(14, 22, FontWeight.Medium), labelSmall = textStyle(12, 16),
)
