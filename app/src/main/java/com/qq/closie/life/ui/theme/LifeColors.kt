package com.qq.closie.life.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** Compatibility names for existing module UI, resolved by the single Life OS theme. */
object LifeColors {
    val Ink: Color @Composable get() = LocalLifeOsColors.current.ink
    val Mist: Color @Composable get() = LocalLifeOsColors.current.inkSecondary
    val MistLight: Color @Composable get() = LocalLifeOsColors.current.muted
    val Line: Color @Composable get() = LocalLifeOsColors.current.line
    val Fog: Color @Composable get() = LocalLifeOsColors.current.paperTertiary
    val Paper: Color @Composable get() = LocalLifeOsColors.current.paper
    val ColdWhite: Color @Composable get() = LocalLifeOsColors.current.card
    val Sage: Color @Composable get() = LocalLifeOsColors.current.accent
    val Clay: Color @Composable get() = MaterialTheme.colorScheme.error
    val Gold: Color @Composable get() = LocalLifeOsColors.current.secondary
    val TextPrimary: Color @Composable get() = LocalLifeOsColors.current.ink
    val TextSecondary: Color @Composable get() = LocalLifeOsColors.current.inkSecondary
    val TextTertiary: Color @Composable get() = LocalLifeOsColors.current.muted
    val TextDisabled: Color @Composable get() = LocalLifeOsColors.current.muted
    val Surface: Color @Composable get() = LocalLifeOsColors.current.paper
    val SurfaceRaised: Color @Composable get() = LocalLifeOsColors.current.card
    val SurfaceInset: Color @Composable get() = LocalLifeOsColors.current.paperTertiary
    val Hairline: Color @Composable get() = LocalLifeOsColors.current.line
    val Accent: Color @Composable get() = LocalLifeOsColors.current.accent
    val AccentMuted: Color @Composable get() = LocalLifeOsColors.current.paperSecondary
    val OnAccent: Color @Composable get() = LocalLifeOsColors.current.onAccent
    val Alert: Color @Composable get() = MaterialTheme.colorScheme.error
}
