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
        assertThat(dims.pageTop.value).isEqualTo(16f)
        assertThat(dims.pageBottom.value).isEqualTo(24f)
        assertThat(dims.blockGap.value).isEqualTo(20f)
        assertThat(dims.sectionGap.value).isEqualTo(20f)
        assertThat(dims.itemGap.value).isEqualTo(10f)
    }

    @Test
    fun regular_usesTheComfortableRhythm() {
        val dims = LifeDimensions.fromScreenWidth(411)
        assertThat(dims.pageHorizontal.value).isEqualTo(24f)
        assertThat(dims.pageTop.value).isEqualTo(20f)
        assertThat(dims.pageBottom.value).isEqualTo(32f)
        assertThat(dims.blockGap.value).isEqualTo(24f)
        assertThat(dims.sectionGap.value).isEqualTo(28f)
        assertThat(dims.itemGap.value).isEqualTo(12f)
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
