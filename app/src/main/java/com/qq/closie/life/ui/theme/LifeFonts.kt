@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.qq.closie.R

/** Bundled font families. Home A roles preserve the inspected Figma faces;
 * functional copy shares Satuo/Sansheng, with Noto Sans for numbers and final fallback.
 * See third_party/fonts/home-a for provenance.
 * Variable font weights are pinned to avoid rendering their default thin instance. */
object LifeFonts {

    /** Home A exact Latin faces and full Chinese date/journal face; see third_party/fonts/home-a. */
    val Wordmark = FontFamily(Font(R.font.shalimar_regular))
    val JournalAhead = FontFamily(Font(R.font.huiwen_regular))
    val JournalHand = FontFamily(Font(R.font.satuo_regular))
    val JournalMeta = FontFamily(Font(R.font.sansheng_regular))
    val JournalEmpty = FontFamily(Font(R.font.slideyouran_regular))
    val Journal = FontFamily(Font(R.font.ar_pl_ukai_tw))
    val JournalSection = FontFamily(Font(R.font.pacifico_regular))
    val JournalStamp = FontFamily(Font(R.font.almendra_regular))

    /** Noto Sans SC. Design weights: Light 300 / Regular 400 / Medium 500. */
    val Sans: FontFamily = FontFamily(
        sans(wght = 300, weight = FontWeight.Light),
        sans(wght = 400, weight = FontWeight.Normal),
        sans(wght = 500, weight = FontWeight.Medium)
    )

    private fun sans(wght: Int, weight: FontWeight) = Font(
        resId = R.font.noto_sans_sc_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(wght))
    )
}
