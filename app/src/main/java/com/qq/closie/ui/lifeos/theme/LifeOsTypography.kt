package com.qq.closie.ui.lifeos.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** System serif titles and sans text: no downloaded or unlicensed fonts in the APK. */
object LifeText {
    val display = TextStyle(fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 29.sp)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 24.sp)
    val caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 19.sp)
}

val LifeOsTypography = Typography(
    displaySmall = LifeText.display,
    headlineSmall = LifeText.title,
    titleLarge = LifeText.title,
    titleMedium = LifeText.body.copy(fontWeight = FontWeight.Medium),
    bodyLarge = LifeText.body,
    bodyMedium = LifeText.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = LifeText.caption,
    labelLarge = LifeText.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = LifeText.caption,
    labelSmall = LifeText.caption
)
