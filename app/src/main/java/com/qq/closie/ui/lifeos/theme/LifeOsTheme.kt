package com.qq.closie.ui.lifeos.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.data.appearance.ThemePalette
import com.qq.closie.data.appearance.ThemeTokens

data class LifeOsColors(
    val paper: Color, val paperSecondary: Color, val paperTertiary: Color,
    val card: Color, val ink: Color, val inkSecondary: Color, val muted: Color,
    val accent: Color, val accentDeep: Color, val secondary: Color,
    val line: Color, val onAccent: Color, val shade: Color, val isDark: Boolean
)

private fun String.color() = Color(0xFF000000L or substring(1).toLong(16))
private fun ThemeTokens.colors() = LifeOsColors(
    paper.color(), paperSecondary.color(), paperTertiary.color(), card.color(), ink.color(),
    inkSecondary.color(), muted.color(), accent.color(), accentDeep.color(), secondary.color(),
    line.color(), onAccent.color(), shade.color(), isDark
)

val LocalLifeOsColors = staticCompositionLocalOf { ThemePalette.resolve(AppearanceSettings()).colors() }

@Composable
fun LifeOsTheme(settings: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val colors = ThemePalette.resolve(settings).colors()
    val base = if (colors.isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent, onPrimary = colors.onAccent,
        primaryContainer = colors.paperTertiary, onPrimaryContainer = colors.accentDeep,
        secondary = colors.accentDeep, onSecondary = colors.paper,
        background = colors.paper, onBackground = colors.ink,
        surface = colors.card, onSurface = colors.ink,
        surfaceVariant = colors.paperSecondary, onSurfaceVariant = colors.inkSecondary,
        outline = colors.line, outlineVariant = colors.line, scrim = colors.shade
    )
    CompositionLocalProvider(LocalLifeOsColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = LifeOsTypography, content = content)
    }
}
