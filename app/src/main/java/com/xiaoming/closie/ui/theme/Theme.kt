package com.xiaoming.closie.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = ClosieColor.Rose,
    onPrimary = ClosieColor.Surface,
    primaryContainer = ClosieColor.RoseSoft,
    onPrimaryContainer = ClosieColor.RosePressed,
    secondary = ClosieColor.SurfaceSoft,
    onSecondary = ClosieColor.Ink,
    secondaryContainer = ClosieColor.SurfaceSoft,
    onSecondaryContainer = ClosieColor.Ink,
    tertiary = ClosieColor.RoseSoft,
    onTertiary = ClosieColor.Ink,
    background = ClosieColor.Canvas,
    onBackground = ClosieColor.Ink,
    surface = ClosieColor.Surface,
    onSurface = ClosieColor.Ink,
    surfaceVariant = ClosieColor.SurfaceSoft,
    onSurfaceVariant = ClosieColor.InkSecondary,
    surfaceTint = ClosieColor.Rose,
    inverseSurface = ClosieColor.Ink,
    inverseOnSurface = ClosieColor.Surface,
    error = ClosieColor.Error,
    onError = ClosieColor.Surface,
    outline = ClosieColor.Hairline,
    outlineVariant = ClosieColor.HairlineStrong,
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
