@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.qq.closie.R

/**
 * Life OS typefaces — bundled, never resolved from the device.
 *
 * The four families (and only these four) ship inside the APK under `res/font/`:
 *
 *  - [Serif]     Noto Serif SC   — brand / page / editorial titles, dates, quotes, amounts.
 *                  Two official static instances (Medium 500, SemiBold 600), subset to
 *                  ASCII + GB2312 + the app's UI charset: Serif renders only fixed UI
 *                  strings, never user-entered text, so a subset cannot cause a visible
 *                  fallback today. Design weights 400/700 have no mapped role in v0.2
 *                  and are not bundled; add the instance when a role appears.
 *  - [Sans]      Noto Sans SC    — every functional line of copy: body, lists, buttons,
 *                  navigation, settings, capture sheet, empty states. Full-charset
 *                  variable [wght 100–900] resource with 300 / 400 / 500 pinned. This is
 *                  the main reading face and user-entered text renders in it, which is
 *                  why the full variable font ships rather than a subset.
 *  - [Hand]      Caveat          — English-only decorative annotations ("quiet days,
 *                  soft archive"). Never used for Chinese text: Caveat has no CJK glyphs,
 *                  and a handwritten Chinese UI label is exactly the v0.1 bug this fixes.
 *  - [Typewriter] Special Elite  — amounts, timestamps, date stamps, version/build info.
 *                  Latin-only by design: any string drawn with it is fixed UI content (never
 *                  user text), so its Latin charset is sufficient. Chinese copy always goes
 *                  through a Sans role.
 *
 * Fonts are regular Android font resources, so they are fully offline, identical on
 * vivo / Samsung / Pixel / Xiaomi / OPPO, and unaffected by the system theme font
 * (OriginOS font replacement cannot reach a bundled res/font family).
 *
 * Variable resources pin the `wght` axis explicitly via [FontVariation] (minSdk 26 is
 * the first API level that honours variation settings). Without the pin the variable
 * font's own default instance would render — for Noto Sans SC that is wght=100, far
 * lighter than any Life OS style.
 */
object LifeFonts {

    /** Noto Serif SC. Roles in v0.2: Medium 500 (EditorialTitle), SemiBold 600 (Brand/Display/PageTitle). */
    val Serif: FontFamily = FontFamily(
        Font(R.font.noto_serif_sc_medium, FontWeight.Medium),
        Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold)
    )

    /** Noto Sans SC. Design weights: Light 300 / Regular 400 / Medium 500. */
    val Sans: FontFamily = FontFamily(
        sans(wght = 300, weight = FontWeight.Light),
        sans(wght = 400, weight = FontWeight.Normal),
        sans(wght = 500, weight = FontWeight.Medium)
    )

    /** Caveat. English decorative annotations only. Design weights: 400 / 500 / 600. */
    val Hand: FontFamily = FontFamily(
        hand(wght = 400, weight = FontWeight.Normal),
        hand(wght = 500, weight = FontWeight.Medium),
        hand(wght = 600, weight = FontWeight.SemiBold)
    )

    /** Special Elite. Single Regular weight. */
    val Typewriter: FontFamily = FontFamily(
        Font(R.font.special_elite_regular, FontWeight.Normal)
    )

    private fun sans(wght: Int, weight: FontWeight) = Font(
        resId = R.font.noto_sans_sc_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(wght))
    )

    private fun hand(wght: Int, weight: FontWeight) = Font(
        resId = R.font.caveat_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(wght))
    )
}
