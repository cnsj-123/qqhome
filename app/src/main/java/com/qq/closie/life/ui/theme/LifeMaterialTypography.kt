package com.qq.closie.life.ui.theme

import androidx.compose.material3.Typography

/**
 * Material3 typography bound to the bundled Life OS faces — the font safety net.
 *
 * Life OS screens use [LifeType] explicitly, so in practice almost nothing reads
 * `MaterialTheme.typography`. But Material components (AlertDialog titles, DropdownMenuItem text,
 * ModalBottomSheet defaults, TextButton internals) *do* fall back to the Material theme when a
 * slot is left unstyled — and without a typography here that fallback resolves to the platform
 * default family, which on a vivo device is the user's theme font. That is the exact leak the
 * whole bundled-font design exists to prevent, so the theme must not be the one hole in it.
 *
 * Mapping rule (identical to [LifeType]'s):
 *  - display / headline / title roles → [LifeFonts.Serif] (Noto Serif SC 500)
 *  - body / label roles               → [LifeFonts.Sans] (Noto Sans SC 400/500)
 *
 * Sizes stay in Material's normal range rather than mirroring [LifeType] exactly: this is a
 * fallback, and a Material component that reaches for `titleLarge` should still look like a
 * Material component — just never in an unbundled font. Nothing maps to `FontFamily.Default`.
 */
internal val LifeMaterialTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = LifeFonts.Serif),
        displayMedium = displayMedium.copy(fontFamily = LifeFonts.Serif),
        displaySmall = displaySmall.copy(fontFamily = LifeFonts.Serif),
        headlineLarge = headlineLarge.copy(fontFamily = LifeFonts.Serif),
        headlineMedium = headlineMedium.copy(fontFamily = LifeFonts.Serif),
        headlineSmall = headlineSmall.copy(fontFamily = LifeFonts.Serif),
        titleLarge = titleLarge.copy(fontFamily = LifeFonts.Serif),
        titleMedium = titleMedium.copy(fontFamily = LifeFonts.Sans),
        titleSmall = titleSmall.copy(fontFamily = LifeFonts.Sans),
        bodyLarge = bodyLarge.copy(fontFamily = LifeFonts.Sans),
        bodyMedium = bodyMedium.copy(fontFamily = LifeFonts.Sans),
        bodySmall = bodySmall.copy(fontFamily = LifeFonts.Sans),
        labelLarge = labelLarge.copy(fontFamily = LifeFonts.Sans),
        labelMedium = labelMedium.copy(fontFamily = LifeFonts.Sans),
        labelSmall = labelSmall.copy(fontFamily = LifeFonts.Sans)
    )
}
