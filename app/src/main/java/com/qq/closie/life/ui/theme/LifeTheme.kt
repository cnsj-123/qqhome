package com.qq.closie.life.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material colour scheme derived from [LifeColors].
 *
 * Life OS screens still render Material components (ModalBottomSheet, NavigationBar, Surface), so
 * the Material scheme has to agree with the Life tokens — otherwise a sheet would silently pick up
 * the host theme's colours. Every value here maps back to a [LifeColors] token; nothing is
 * introduced locally.
 */
private val LifeColorScheme = lightColorScheme(
    primary = LifeColors.Accent,
    onPrimary = LifeColors.OnAccent,
    primaryContainer = LifeColors.AccentMuted,
    onPrimaryContainer = LifeColors.Ink,
    secondary = LifeColors.Mist,
    onSecondary = LifeColors.ColdWhite,
    background = LifeColors.Paper,
    onBackground = LifeColors.Ink,
    surface = LifeColors.ColdWhite,
    onSurface = LifeColors.Ink,
    surfaceVariant = LifeColors.Fog,
    onSurfaceVariant = LifeColors.Mist,
    surfaceTint = LifeColors.ColdWhite,
    outline = LifeColors.Line,
    outlineVariant = LifeColors.Line,
    error = LifeColors.Alert,
    onError = LifeColors.OnAccent,
    scrim = LifeColors.Ink
)

/** Material shapes bound to the 10/12/16dp scale in [LifeShape]. */
private val LifeShapes = Shapes(
    extraSmall = RoundedCornerShape(LifeShape.small),
    small = RoundedCornerShape(LifeShape.small),
    medium = RoundedCornerShape(LifeShape.medium),
    large = RoundedCornerShape(LifeShape.large),
    extraLarge = RoundedCornerShape(LifeShape.large)
)

/**
 * The Life OS design system.
 *
 * Applied per Life OS page — **not** around [com.qq.closie.navigation.ClosieNavHost]. The closet
 * keeps rendering inside the app's original theme, so building the Life OS visual language cannot
 * regress the already-shipped Closie screens.
 *
 * Text inside Life OS uses [LifeType] and [LifeColors] explicitly rather than
 * `MaterialTheme.typography`, so a future Material version bump cannot silently restyle the app.
 * [LifeMaterialTypography] is the safety net underneath that convention: any Material component
 * whose text slot we did not style still resolves to a bundled face rather than the platform
 * default — i.e. rather than the device's theme font.
 */
@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LifeColorScheme,
        // Not optional. Omitting typography here would let an unstyled Material component fall
        // through to FontFamily.Default, which is the system/theme font — the one leak the
        // bundled-font design exists to close.
        typography = LifeMaterialTypography,
        shapes = LifeShapes,
        content = content
    )
}
