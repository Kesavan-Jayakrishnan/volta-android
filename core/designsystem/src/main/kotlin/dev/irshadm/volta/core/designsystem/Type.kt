package dev.irshadm.volta.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.irshadm.volta.core.designsystem.R

@OptIn(ExperimentalTextApi::class)
private fun dmSans(weight: Int) = Font(
    R.font.dm_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** DM Sans: soft, calm, slightly rounded. */
val AppFont = FontFamily(dmSans(400), dmSans(500), dmSans(600), dmSans(700))

/** Tabular figures so live numbers don't jitter while counting/updating. */
const val TabularNums = "tnum"

val WtTypography = Typography(
    displayLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.04).em, fontFeatureSettings = TabularNums),
    headlineLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

/** Big live number style helper. */
fun TextStyle.tabular() = copy(fontFeatureSettings = TabularNums)
