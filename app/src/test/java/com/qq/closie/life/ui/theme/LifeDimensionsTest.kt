package com.qq.closie.life.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM tests for the compact breakpoint. The values are the v0.2.0 spec's page rhythm —
 * these tests exist so the breakpoint itself (and only it) is pinned: anyone who nudges a dp
 * value sees a diff; anyone who breaks the 400dp boundary logic sees a red test.
 */
class LifeDimensionsTest {

    @Test
    fun below400_isCompact() {
        assertThat(LifeDimensions.fromScreenWidth(360).isCompact).isTrue()
        assertThat(LifeDimensions.fromScreenWidth(393).isCompact).isTrue()
        assertThat(LifeDimensions.fromScreenWidth(399).isCompact).isTrue()
    }

    @Test
    fun at400_isRegular() {
        // The boundary is `< 400`: 400dp itself (tablets, landscape) gets the regular rhythm.
        assertThat(LifeDimensions.fromScreenWidth(400).isCompact).isFalse()
        assertThat(LifeDimensions.fromScreenWidth(411).isCompact).isFalse()
    }

    @Test
    fun compact_usesTheTighterRhythm() {
        val dims = LifeDimensions.fromScreenWidth(393)
        assertThat(dims.pageHorizontal.value).isEqualTo(20f)
        // v0.3.0: 16 → 22. A real phone review found the top gap "略微收得过头"; this is the nudge.
        assertThat(dims.pageTop.value).isEqualTo(22f)
        assertThat(dims.pageBottom.value).isEqualTo(24f)
        assertThat(dims.blockGap.value).isEqualTo(20f)
        assertThat(dims.sectionGap.value).isEqualTo(20f)
        assertThat(dims.itemGap.value).isEqualTo(10f)
    }

    @Test
    fun regular_usesTheComfortableRhythm() {
        val dims = LifeDimensions.fromScreenWidth(411)
        assertThat(dims.pageHorizontal.value).isEqualTo(24f)
        assertThat(dims.pageTop.value).isEqualTo(24f)
        assertThat(dims.pageBottom.value).isEqualTo(32f)
        assertThat(dims.blockGap.value).isEqualTo(24f)
        assertThat(dims.sectionGap.value).isEqualTo(28f)
        assertThat(dims.itemGap.value).isEqualTo(12f)
    }

    /**
     * §3 fixes the target band for the top gap at 20–24dp on a compact phone. Pinning the exact
     * number elsewhere is already done; this guards the *band*, which is the thing the spec actually
     * stated, so a future tweak inside the band is allowed to pass but drifting back to 16 is not.
     */
    @Test
    fun pageTop_staysInsideTheTargetBandOnBothRhythms() {
        val compact = LifeDimensions.fromScreenWidth(393)
        val regular = LifeDimensions.fromScreenWidth(411)

        assertThat(compact.pageTop.value).isAtLeast(20f)
        assertThat(compact.pageTop.value).isAtMost(24f)
        assertThat(regular.pageTop.value).isAtLeast(20f)
        assertThat(regular.pageTop.value).isAtMost(24f)
    }

    /** Compact must never be *roomier* than regular in any dimension — the invariant of the break. */
    @Test
    fun compact_neverExceedsRegular() {
        val compact = LifeDimensions.fromScreenWidth(360)
        val regular = LifeDimensions.fromScreenWidth(480)

        assertThat(compact.pageHorizontal).isAtMost(regular.pageHorizontal)
        assertThat(compact.pageTop).isAtMost(regular.pageTop)
        assertThat(compact.pageBottom).isAtMost(regular.pageBottom)
        assertThat(compact.blockGap).isAtMost(regular.blockGap)
        assertThat(compact.sectionGap).isAtMost(regular.sectionGap)
        assertThat(compact.itemGap).isAtMost(regular.itemGap)
    }
}
