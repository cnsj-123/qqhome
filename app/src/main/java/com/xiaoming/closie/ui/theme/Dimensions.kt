package com.xiaoming.closie.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact-first sizing helper. Primary target is the vivo X200 Pro mini (~393dp wide,
 * narrow and tall); large phones still work because nothing is hardcoded in pixels.
 */
@Immutable
data class ClosieDimensions(
    val screenWidthDp: Int,
    val screenHeightDp: Int
) {
    /** Narrow compact flagship (360-399dp). */
    val isCompact: Boolean get() = screenWidthDp < 400

    val pageHorizontal: Dp get() = 16.dp

    val gridGutter: Dp get() = if (isCompact) 10.dp else 12.dp

    /** 4:5 catalog-like aspect for clothing thumbnails. */
    val gridAspectRatio: Float get() = 0.8f

    val sectionGap: Dp get() = if (isCompact) 20.dp else 24.dp
}

@Composable
fun rememberClosieDimensions(): ClosieDimensions {
    val cfg = LocalConfiguration.current
    return ClosieDimensions(
        screenWidthDp = cfg.screenWidthDp,
        screenHeightDp = cfg.screenHeightDp
    )
}
