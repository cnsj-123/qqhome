package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/** App-wide hierarchy derived from Figma Home A. Dense tools share the same roles at the
 * compact scale in LifeText. Money/input retain Noto for unambiguous numeric editing. */
object LifeType {
    // Measured through local Figwright, frames 3:6 / 3:37. AUTO line height stays unspecified.
    // Functional controls use a compact derived scale; the journal roles preserve exact metrics.
    val Wordmark = TextStyle(fontFamily = LifeFonts.Wordmark, fontSize = 32.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 2.24.sp)
    val DateHero = TextStyle(fontFamily = LifeFonts.Journal, fontSize = 23.sp, lineHeight = TextUnit.Unspecified)
    val JournalHand = TextStyle(fontFamily = LifeFonts.JournalHand, fontSize = 20.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 4.sp)
    val JournalMeta = TextStyle(fontFamily = LifeFonts.JournalMeta, fontSize = 13.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 2.6.sp)
    val JournalTagline = DateHero.copy(fontSize = 11.sp)
    // Owner override: preserve the original P1 patch for “好像没有随手一拍”.
    val JournalEmpty = TextStyle(fontFamily = LifeFonts.JournalEmpty, fontSize = 30.sp, lineHeight = 40.sp, letterSpacing = 2.1.sp)
    val JournalAhead = TextStyle(fontFamily = LifeFonts.JournalAhead, fontSize = 18.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 2.34.sp)
    val JournalSection = TextStyle(fontFamily = LifeFonts.JournalSection, fontSize = 13.sp, lineHeight = TextUnit.Unspecified, letterSpacing = 2.47.sp)
    val JournalStamp = TextStyle(fontFamily = LifeFonts.JournalStamp, fontSize = 13.sp, lineHeight = TextUnit.Unspecified, letterSpacing = .78.sp)
    val JournalEmptyYear = JournalMeta.copy(fontSize = 12.sp, letterSpacing = 2.4.sp)
    val JournalFactHeading = DateHero.copy(fontSize = 15.sp)
    val JournalFactMeta = JournalMeta.copy(fontSize = 16.sp, letterSpacing = 3.2.sp)
    val Brand = Wordmark
    val Display = JournalEmpty
    val PageTitle = DateHero
    val EditorialTitle = PageTitle.copy(fontSize = 21.sp, lineHeight = TextUnit.Unspecified)
    val ModuleTitle = JournalHand.copy(fontSize = 18.sp, letterSpacing = 1.sp)
    val SectionTitle = JournalHand.copy(fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = 1.sp)
    val Body = JournalHand.copy(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = .8.sp)
    val BodySecondary = Body.copy(fontSize = 14.sp, lineHeight = 22.sp)
    val Caption = JournalMeta.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = .65.sp)
    val Navigation = Body.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = .5.sp)
    val Action = Body.copy(fontSize = 15.sp, lineHeight = 23.sp)
    val Money = TextStyle(fontFamily = LifeFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 23.sp,
        lineHeight = 31.sp, fontFeatureSettings = "tnum")
    val MoneyCompact = Money.copy(fontSize = 17.sp, lineHeight = 25.sp)
    val MoneyCalendar = Money.copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.sp)
    val MonoNumber = Money.copy(fontSize = 15.sp, lineHeight = 22.sp)
    val Timestamp = JournalStamp
    val HandNote = JournalSection.copy(fontSize = 16.sp)
    val UserNote = Body
    val EmptyTitle = JournalEmpty.copy(fontSize = 23.sp, lineHeight = 32.sp)

    fun inputFor(value: String) = Body.withBundledFallback(value)
    fun inputFor(value: androidx.compose.ui.text.input.TextFieldValue) = inputFor(value.text)

    fun journalFor(text: String) = JournalHand.withBundledFallback(text)
    fun journalMetaFor(text: String) = JournalMeta.withBundledFallback(text)
    fun journalEmptyFor(text: String) = JournalEmpty.withBundledFallback(text)
    fun journalAheadFor(text: String) = JournalAhead.withBundledFallback(text)
}

/** Preserve the role's metrics when an arbitrary user string needs the bundled fallback. */
internal fun TextStyle.withBundledFallback(text: String): TextStyle {
    val supports = when (fontFamily) {
        LifeFonts.JournalHand -> SatuoCoverage.supports(text)
        LifeFonts.JournalMeta -> SanshengCoverage.supports(text)
        LifeFonts.JournalEmpty -> SlideyouranCoverage.supports(text)
        LifeFonts.JournalAhead -> HuiwenCoverage.supports(text)
        LifeFonts.Journal -> JournalCoverage.supports(text)
        LifeFonts.Wordmark, LifeFonts.JournalSection, LifeFonts.JournalStamp -> text.all { it.code in 32..126 || it == '\n' }
        else -> true
    }
    return if (supports) this else copy(fontFamily = if (JournalCoverage.supports(text)) LifeFonts.Journal else LifeFonts.Sans)
}
