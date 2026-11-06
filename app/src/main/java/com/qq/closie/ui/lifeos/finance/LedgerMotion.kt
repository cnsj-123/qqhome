package com.qq.closie.ui.lifeos.finance

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.qq.closie.life.ui.theme.LifeFonts

/** Parameters from LifeOS-账目动效方案-v2, not generic destination transitions. */
internal object LedgerMotion {
    val SpringSpatial = spring<Float>(stiffness = 380f, dampingRatio = .75f)
    val SpringBouncy = spring<Float>(stiffness = 320f, dampingRatio = .58f)
    val SpringSnappy = spring<Float>(stiffness = 700f, dampingRatio = .85f)
    val EaseOutStrong = CubicBezierEasing(.23f, 1f, .32f, 1f)
    val EasePaper = CubicBezierEasing(.32f, .72f, 0f, 1f)
    const val MICRO = 120
    const val FAST = 200
    const val NORMAL = 280
    const val HERO = 520
    const val STAGGER = 30
}

/** Measured finance roles in page 1226:39231. Numeric editing still uses the bundled Sans. */
internal object LedgerText {
    val body = TextStyle(fontFamily = LifeFonts.Journal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = .8.sp)
    val caption = body.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .7.sp)
    val meta = TextStyle(fontFamily = LifeFonts.JournalMeta, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.4.sp)
    val title = body.copy(fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 2.sp)
    val amount = body.copy(fontSize = 28.sp, lineHeight = 38.sp, letterSpacing = .5.sp, fontFeatureSettings = "tnum")
    val rowAmount = body.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = .5.sp, fontFeatureSettings = "tnum")
    val input = TextStyle(fontFamily = LifeFonts.Sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
}
