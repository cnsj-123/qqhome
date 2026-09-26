package com.qq.closie.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.qq.closie.data.repository.WardrobeSnapshot

/**
 * The one wardrobe generation currently on screen, shared down the composition.
 *
 * ### Why a `CompositionLocal` and not a parameter on every screen
 *
 * The requirement is that **every** Closet screen renders from a single `WardrobeSnapshot` emission, so
 * that 衣橱 (items + wear + wash), 详情 (item + wear + wash), 首页 (items + ootds + wear), OOTD
 * (items + ootds) and 搭配 (outfits + items) can never be composed from two generations. The obvious
 * implementation — each screen calling `repo.snapshot.collectAsState()` — does **not** satisfy it: those
 * are N separate collectors with N resume points, which is the same defect as N separate flows. One
 * emission reaching the screen proves nothing if the screen's *siblings* are a generation behind.
 *
 * So the collection happens exactly once, inside [ClosieNavHost][com.qq.closie.navigation.ClosieNavHost],
 * and the resulting value is passed down. The alternatives were considered and are worse:
 *
 *  - **A parameter on each screen** would work and is the most explicit option, but it changes the
 *    signature of six public composables that tests and the navigation graph already call, and it makes
 *    every future Closet screen remember to thread it. The local carries the invariant structurally.
 *  - **A `ViewModel` holding the flow** would add a lifecycle owner per host and a second source of
 *    truth for "which snapshot is current", for a value that is a pure projection of a `StateFlow` the
 *    repository already owns.
 *
 * ### Why it lives in the UI layer, not in `data.repository`
 *
 * `WardrobeSnapshot` itself is a pure Kotlin data class — it has no Compose dependency and lives in
 * `data.repository`, which is correct. This `CompositionLocal`, however, is a Compose runtime concept
 * (`staticCompositionLocalOf`), and the repository layer must not import `androidx.compose.runtime`.
 * Keeping the local here preserves the layering contract: UI → Repository → storage, never the reverse.
 *
 * ### Why it is a static (non-defaulted) local
 *
 * `staticCompositionLocalOf` with no default: a screen that reads it outside the provider is a *programming
 * error*, not a state to fall back from. A default of [WardrobeSnapshot.EMPTY] would silently render an
 * empty wardrobe in that case — the exact "false empty" failure this whole change exists to remove.
 * Crashing with the standard Compose message ("CompositionLocal … not present") at development time is
 * the correct behaviour, and every production entry point renders under the provider.
 *
 * ### Why reading it does not recompose everything on every publish
 *
 * This is a `staticCompositionLocalOf`: a wardrobe publish is a user action (a save, a delete, a
 * restore), not a frame-by-frame event, so the coarser invalidation is free in practice and the value
 * is skippable — Compose can skip recomposing a reader whose behaviour did not read it. The alternative
 * — five separate provided values — would restore exactly the N-store problem this design removes.
 */
val LocalWardrobeSnapshot = staticCompositionLocalOf<WardrobeSnapshot> {
    error(
        "LocalWardrobeSnapshot was read outside its provider. Closet screens must render inside " +
            "ClosieNavHost, which collects WardrobeRepository.snapshot once and provides it — see " +
            "WardrobeRepository.snapshot for why one canonical collection is required."
    )
}
