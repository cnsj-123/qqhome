package com.qq.closie.ui.lifeos.theme

import androidx.compose.material3.Typography
import com.qq.closie.life.ui.theme.LifeType
import androidx.compose.ui.unit.sp

/** Compact application scale of the single Home A typography owner. */
object LifeText {
    val family = LifeType.Body.fontFamily
    val number = LifeType.Money
    val journal = LifeType.JournalHand
    val display = LifeType.Display
    val title = LifeType.SectionTitle
    val body = LifeType.Body
    val caption = LifeType.Caption
}

val LifeOsTypography = Typography(
    displayLarge = LifeType.Display, displayMedium = LifeType.Display, displaySmall = LifeType.PageTitle,
    headlineLarge = LifeType.PageTitle, headlineMedium = LifeType.EditorialTitle, headlineSmall = LifeType.SectionTitle,
    titleLarge = LifeType.SectionTitle, titleMedium = LifeType.Body, titleSmall = LifeType.BodySecondary,
    bodyLarge = LifeType.Body, bodyMedium = LifeType.BodySecondary, bodySmall = LifeType.Caption,
    labelLarge = LifeType.Action, labelMedium = LifeType.Navigation, labelSmall = LifeType.Caption
)
