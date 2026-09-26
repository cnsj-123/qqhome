package com.qq.closie.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = ClosieColor.Fig,
    onPrimary = ClosieColor.Paper,
    primaryContainer = ClosieColor.FigSoft,
    onPrimaryContainer = ClosieColor.FigPressed,
    secondary = ClosieColor.Moss,
    onSecondary = ClosieColor.Paper,
    secondaryContainer = ClosieColor.MossSoft,
    onSecondaryContainer = ClosieColor.Moss,
    tertiary = ClosieColor.FigSoft,
    onTertiary = ClosieColor.Ink,
    background = ClosieColor.Porcelain,
    onBackground = ClosieColor.Ink,
    surface = ClosieColor.Paper,
    onSurface = ClosieColor.Ink,
    surfaceVariant = ClosieColor.Mist,
    onSurfaceVariant = ClosieColor.Graphite,
    surfaceTint = ClosieColor.Fig,
    inverseSurface = ClosieColor.Ink,
    inverseOnSurface = ClosieColor.Paper,
    error = ClosieColor.Error,
    onError = ClosieColor.Paper,
    outline = ClosieColor.Hairline,
    outlineVariant = ClosieColor.Fog,
    scrim = ClosieColor.Ink.copy(alpha = 0.32f)
)

@Composable
fun ClosieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = ClosieTypography,
        shapes = ClosieShapes,
        content = content
    )
}
