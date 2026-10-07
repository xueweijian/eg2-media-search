package com.xueweijian.eg2media.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.FontVariation
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.xueweijian.eg2media.R

/**
 * Nunito 变量字体（单文件全字重，OFL，gallery 同款 HANDOFF §6.2）。
 * 中文回退系统 Noto Sans SC。
 */
val appFontFamily = FontFamily(
    Font(
        R.font.nunito_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.nunito_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.nunito_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.nunito_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val baseline = Typography()

val AppTypography = Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = appFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = appFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = appFontFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = appFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = appFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = appFontFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = appFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = appFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = appFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = appFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = appFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = appFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = appFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = appFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = appFontFamily),
)
