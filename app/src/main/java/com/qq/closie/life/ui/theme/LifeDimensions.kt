package com.qq.closie.life.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact-aware semantic page dimensions.
 *
 * Life OS targets narrow, tall Android phones first (the primary device is ~393dp wide).
 * One breakpoint, `screenWidthDp < 400` ([isCompact]), switches the page rhythm:
 *
 *   compact : pageHorizontal 20 · pageTop 16 · pageBottom 24 · blockGap 20 · sectionGap 20 · itemGap 10
 *   regular : pageHorizontal 24 · pageTop 20 · pageBottom 32 · blockGap 24 · sectionGap 28 · itemGap 12
 *
 * These are *page* dimensions only. The status-bar inset is owned once by the shell's
 * Scaffold (see LifeShellNavHost) — [pageTop] is the visual gap between the status bar
 * and the first line, not a second safety margin, and [pageBottom] is a small visual
 * tail, not a second reserve for the bottom bar (the Scaffold already measures the bar).
 */
data class LifeDimensions private constructor(
    val screenWidthDp: Int
) {
    /** Narrow compact flagship (360–399dp). */
    val isCompact: Boolean = screenWidthDp < 400

    /** Page content inset from the screen edge. */
    val pageHorizontal: Dp = if (isCompact) 20.dp else 24.dp

    /** Visual gap between the status bar and the first line of the page. */
    val pageTop: Dp = if (isCompact) 16.dp else 20.dp

    /** Small visual tail at the very bottom of a page's content. */
    val pageBottom: Dp = if (isCompact) 24.dp else 32.dp

    /** Rhythm between the home page's header → 最近 → 接下来 → 记点什么 blocks. */
    val blockGap: Dp = if (isCompact) 20.dp else 24.dp

    /** Vertical rhythm between sections on directory-style pages. */
    val sectionGap: Dp = if (isCompact) 20.dp else 28.dp

    /** Between sibling rows / items inside one section. */
    val itemGap: Dp = if (isCompact) 10.dp else 12.dp

    companion object {
        /** Pure factory so the breakpoint behaviour is unit-testable without Compose. */
        fun fromScreenWidth(widthDp: Int): LifeDimensions = LifeDimensions(widthDp)
    }
}

@Composable
fun rememberLifeDimensions(): LifeDimensions {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        LifeDimensions.fromScreenWidth(configuration.screenWidthDp)
    }
}
