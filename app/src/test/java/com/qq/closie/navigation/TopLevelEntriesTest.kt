package com.qq.closie.navigation

import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.ui.shell.LifeDestination
import org.junit.Test

/**
 * Regression test for the v0.1 real-device crash 生活 → 衣橱 ("CI green but the phone dies").
 *
 * The crash was a JVM circular class-initialization bug, not a Compose or data bug:
 *
 *  1. The first static touch of the `TopLevel` family in a cold process is
 *     `TopLevel.Closet.INSTANCE` — LifeShell passes `TopLevel.Closet.route` as the Closie
 *     NavHost's startDestination, and that expression is evaluated the moment the user taps
 *     衣橱 in 生活.
 *  2. `Closet.<clinit>` runs, which must first run its superclass `TopLevel.<clinit>`, which
 *     initializes `Companion`, which ran `entries = listOf(Home, Closet, Ootd, Outfits)`.
 *  3. Evaluating that list re-reads `Closet.INSTANCE` while `Closet.<clinit>` is *still on the
 *     stack*. The JVM permits a same-thread recursive class-init to proceed, so the read
 *     returns the not-yet-assigned static: null.
 *  4. `entries` was henceforth `[Home, null, Ootd, Outfits]`, and the next
 *     `TopLevel.entries.any { it.route == … }` (ClosieNavHost's bottom-bar visibility) threw
 *     NPE → the app died exactly one tap into the closet. No static audit could find it; no
 *     CI test touched it.
 *
 * The fix defers list construction with `by lazy` (all child <clinit>s have unwound by the
 * time it first runs). THIS TEST reproduces the original trigger order on purpose — the very
 * first family access here is `TopLevel.Closet`, before anything touches `Companion` — so that
 * the null-capture cannot silently come back.
 */
class TopLevelEntriesTest {

    @Test
    fun entries_areComplete_evenWhenClosetIsTheFirstTouch() {
        // Simulates the real device: the family's FIRST static access is the Closet instance
        // (LifeShell → ClosieNavHost startDestination), before any other family member or the
        // companion is touched. The JVM class-init order triggered here is the crash's exact
        // trigger. Do not "clean up" this line order.
        val closet: TopLevel = TopLevel.Closet

        assertThat(TopLevel.entries).doesNotContain(null)
        assertThat(TopLevel.entries)
            .containsExactly(TopLevel.Home, TopLevel.Closet, TopLevel.Ootd, TopLevel.Outfits)
            .inOrder()
        assertThat(closet.route).isEqualTo("closet")
        assertThat(closet.label).isEqualTo("衣橱")
    }

    @Test
    fun entries_areComplete_evenWhenHomeIsTheFirstTouch() {
        // The other cold-start order: Closie standalone used to land on Home first.
        val home: TopLevel = TopLevel.Home

        assertThat(TopLevel.entries).doesNotContain(null)
        assertThat(TopLevel.entries)
            .containsExactly(TopLevel.Home, TopLevel.Closet, TopLevel.Ootd, TopLevel.Outfits)
            .inOrder()
        assertThat(home.route).isEqualTo("home")
    }

    @Test
    fun tabRoutes_areComplete_evenWhenClosetIsTheFirstTouch() {
        // LifeDestination's companion list has the identical pattern — pinned for the same
        // reason. Its cold path starts at Home, but Closet being first must also be safe.
        val closet: LifeDestination = LifeDestination.Closet

        assertThat(LifeDestination.tabRoutes).doesNotContain(null)
        assertThat(LifeDestination.tabs).containsExactly(
            LifeDestination.Home,
            LifeDestination.Timeline,
            LifeDestination.Modules,
            LifeDestination.Me
        ).inOrder()
        assertThat(closet.route).isEqualTo("life_closet")
    }
}
