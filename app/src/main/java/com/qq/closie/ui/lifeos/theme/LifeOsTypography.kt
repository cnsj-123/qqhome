package com.qq.closie.ui.lifeos.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.qq.closie.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** UI uses platform sans with real weights; journal headings may opt into bundled serif. */
object LifeText {
    val family = FontFamily.SansSerif
    val number = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val journal = TextStyle(fontFamily = FontFamily(Font(R.font.noto_serif_sc_medium)), fontSize = 22.sp, lineHeight = 30.sp)
    val display = TextStyle(fontFamily = family, fontSize = 26.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 18.sp)
}

val LifeOsTypography = Typography(
    displayLarge = LifeText.display.copy(fontSize = 36.sp, lineHeight = 44.sp),
    displayMedium = LifeText.display.copy(fontSize = 30.sp, lineHeight = 38.sp),
    displaySmall = LifeText.display,
    headlineLarge = LifeText.display,
    headlineMedium = LifeText.title.copy(fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = LifeText.title,
    titleLarge = LifeText.title,
    titleMedium = LifeText.body.copy(fontWeight = FontWeight.Medium),
    titleSmall = LifeText.body,
    bodyLarge = LifeText.body,
    bodyMedium = LifeText.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = LifeText.caption,
    labelLarge = LifeText.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = LifeText.caption,
    labelSmall = LifeText.caption
)
