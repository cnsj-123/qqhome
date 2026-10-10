package com.qq.closie.ui.lifeos.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.qq.closie.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Bundled Source Han Sans CN Normal (OFL 1.1); no network or OEM font dependency. */
object LifeText {
    val family = FontFamily(Font(R.font.life_sans))
    val display = TextStyle(fontFamily = family, fontSize = 26.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 29.sp)
    val body = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 24.sp)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 19.sp)
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
