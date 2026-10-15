package com.qq.closie.life.ui.theme

import android.widget.TextView
import androidx.compose.ui.text.TextStyle
import androidx.core.content.res.ResourcesCompat
import com.qq.closie.R

/** Native receipt adapter uses the same role metrics and cmap fallback as Compose. */
internal fun TextView.applyLifeTypography(style: TextStyle = LifeType.Body) {
    val resolved = style.withBundledFallback(text.toString())
    val resource = when (resolved.fontFamily) {
        LifeFonts.JournalHand -> R.font.satuo_regular
        LifeFonts.JournalMeta -> R.font.sansheng_regular
        LifeFonts.Journal -> R.font.ar_pl_ukai_tw
        else -> R.font.noto_sans_sc_variable
    }
    typeface = ResourcesCompat.getFont(context, resource)
    if (resource == R.font.noto_sans_sc_variable)
        setFontVariationSettings("'wght' ${resolved.fontWeight?.weight ?: 400}")
    textSize = resolved.fontSize.value
    letterSpacing = resolved.letterSpacing.value.takeIf { it.isFinite() }?.div(resolved.fontSize.value) ?: 0f
    fontFeatureSettings = resolved.fontFeatureSettings
    includeFontPadding = false
    val extra = resolved.lineHeight.value.takeIf { it.isFinite() }?.let { it - resolved.fontSize.value } ?: 0f
    setLineSpacing(extra * resources.displayMetrics.scaledDensity, 1f)
}
