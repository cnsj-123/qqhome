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
 *   compact : pageHorizontal 20 · pageTop 22 · pageBottom 24 · blockGap 20 · sectionGap 20 · itemGap 10
 *   regular : pageHorizontal 24 · pageTop 24 · pageBottom 32 · blockGap 24 · sectionGap 28 · itemGap 12
 *
 * **v0.3.0 top-spacing calibration.** On the real device the user reported v0.1 as "顶部太空" and
 * v0.2 as "略微收得过头" — so this is a nudge, not a redesign. `pageTop` on compact moves 16 → 22dp,
 * which lands the status-bar → page-title distance in the 20–24dp band the spec asks for. Regular
 * moves 20 → 24dp so the two breakpoints keep a consistent 2dp relationship instead of crossing over.
 *
 * The important part is what did NOT change: the shell's Scaffold remains the *only* system-bar
 * inset owner, and `consumeWindowInsets(padding)` in [com.qq.closie.life.ui.shell.LifeShellNavHost]
 * still zeroes the inset for nested content. So `pageTop` is purely the visual gap below the status
 * bar — never a second safety margin. The v0.1 bug was three layers stacking
 * (`Scaffold inset + statusBarsPadding + pageTop` ≈ 60dp); re-adding a `statusBarsPadding()` here to
 * "fix" the spacing would reintroduce it, which is why the tests below pin the fact that this value
 * stays inside the 20–24dp band rather than allowing a runaway.
 *
 * These are *page* dimensions only. [pageBottom] is a small visual tail, not a second reserve for the
 * bottom bar (the Scaffold already measures the bar).
 */
data class LifeDimensions private constructor(
    val screenWidthDp: Int
) {
    /** Narrow compact flagship (360–399dp). */
    val isCompact: Boolean = screenWidthDp < 400

    /** Page content inset from the screen edge. */
    val pageHorizontal: Dp = if (isCompact) 20.dp else 24.dp

    /**
     * Visual gap between the status bar and the first line of the page.
     *
     * v0.3.0: 16dp → 22dp (compact), 20dp → 24dp (regular). See the class docs — this is the whole
     * of the top-spacing change, and no screen may add its own inset on top of it.
     */
    val pageTop: Dp = if (isCompact) 22.dp else 24.dp

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
