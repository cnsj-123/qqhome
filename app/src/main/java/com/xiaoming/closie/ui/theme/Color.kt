package com.xiaoming.closie.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Closie v2 palette — "The wardrobe supplies the color; Closie supplies the frame."
 *
 * The UI stays ~90% neutral (Porcelain / Paper / Mist / Ink / Graphite) so the clothes
 * themselves are the richest color on screen. Fig is the brand accent, used sparingly.
 */
object ClosieColor {
    // Neutral base
    val Porcelain = Color(0xFFF7F6F2)
    val Paper = Color(0xFFFFFFFF)
    val Mist = Color(0xFFEFEEE9)
    val Fog = Color(0xFFE7E5DF)

    val Ink = Color(0xFF171717)
    val Graphite = Color(0xFF55524E)
    val Stone = Color(0xFF96918A)
    val Hairline = Color(0xFFDDDAD3)

    // Brand accent
    val Fig = Color(0xFF713D4B)
    val FigPressed = Color(0xFF5E303D)
    val FigSoft = Color(0xFFEFE3E6)

    // Secondary, used very sparingly
    val Moss = Color(0xFF747B61)
    val MossSoft = Color(0xFFEBEDE5)

    val Error = Color(0xFFB3261E)

    // Backward-compatible aliases so pre-existing screens keep compiling.
    val Canvas = Porcelain
    val Surface = Paper
    val SurfaceSoft = Mist
    val SurfaceMuted = Fog
    val InkSecondary = Graphite
    val InkTertiary = Stone
    val HairlineStrong = Fog
    val Rose = Fig
    val RosePressed = FigPressed
    val RoseSoft = FigSoft
}
