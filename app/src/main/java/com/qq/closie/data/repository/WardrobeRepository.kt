package com.qq.closie.data.repository

import com.qq.closie.data.model.*
import kotlinx.coroutines.flow.StateFlow

/** Future All-in-One tool boundary: UI and later assistants never manipulate storage directly. */
interface WardrobeRepository {
    /**
     * The whole wardrobe in one emission — **the** flow a screen that shows more than one surface
     * must collect.
     *
     * ### Why the five flows below are not enough, and this one is
     *
     * `items`, `wearEvents`, `washEvents`, `ootds` and `outfits` are five `StateFlow`s. They are
     * updated from one canonical value, so each individual emission is internally consistent — but
     * they are still five stores, and therefore five resume points. A screen that collects three of
     * them (衣橱 reads items + wear + wash; 首页 reads items + ootds + wear; 搭配 reads outfits +
     * items) has three coroutines that a `MutableStateFlow` assignment wakes **independently**. A
     * republish that assigns all five can be observed by that screen as `(new items, old wear, old
     * wash)`: the collector on `wearEvents` has not been rescheduled yet. `synchronized` and
     * `MutableStateFlow` both exclude writers, not readers resuming on another dispatcher, so no
     * amount of care inside the repository closes this.
     *
     * The only structure that does close it is one store the screen collects once, which is what this
     * property is. A composable does
     *
     * ```
     *   val wardrobe by repo.snapshot.collectAsState()
     *   wardrobe.items        // all three read from the SAME emission
     *   wardrobe.wearEvents
     *   wardrobe.washEvents
     * ```
     *
     * and a torn combination becomes unrepresentable rather than merely unlikely — there is no second
     * flow to have been left behind, because there is no second store.
     *
     * ### What this does not guarantee
     *
     * This prevents **cross-Flow tearing** (a screen rendering items from one generation and wear
     * events from another). It does **not** collapse a multi-step logical mutation — such as
     * `deleteItem`, which calls `putItems → publish`, `putWear → publish`, `putWash → publish` —
     * into a single emission. A collector will see intermediate snapshots; each one is internally
     * consistent, but the logical operation is not complete until the last publish. Batching a
     * logical mutation into one publish is a separate concern and is not addressed here.
     *
     * ### Relationship to the five per-surface flows
     *
     * They remain, because a screen that genuinely shows one surface (the editor's item list, the
     * detail screen's single item) is simpler with one flow and needs no cross-surface guarantee.
     * They are **projections** of this one, kept for that compatibility, and they carry exactly the
     * guarantee a single `StateFlow` can carry: one surface, one generation, no tears *within* that
     * surface. They are not, and are not documented as, cross-surface atomic. Only [snapshot] is.
     */
    val snapshot: StateFlow<WardrobeSnapshot>

    val items: StateFlow<List<ClothingItem>>
    val wearEvents: StateFlow<List<WearEvent>>
    val washEvents: StateFlow<List<WashEvent>>
    val ootds: StateFlow<List<Ootd>>
    val outfits: StateFlow<List<Outfit>>
    fun listItems(): List<ClothingItem>; fun getItem(id: String): ClothingItem?
    fun createItem(item: ClothingItem): ClothingItem; fun updateItem(item: ClothingItem): ClothingItem; fun deleteItem(id: String)
    fun listWearEvents(): List<WearEvent>; fun addWear(itemId: String, date: String, source: WearSource = WearSource.MANUAL, ootdId: String? = null, note: String = ""); fun addWash(itemId: String, date: String, note: String = "")
    fun deleteWearEvent(id: String); fun deleteWashEvent(id: String)
    fun wearCount(itemId: String): Int; fun washCount(itemId: String): Int
    fun listOotds(): List<Ootd>; fun saveOotd(ootd: Ootd): Ootd; fun deleteOotd(id: String)
    fun listOutfits(): List<Outfit>; fun saveOutfit(outfit: Outfit): Outfit; fun deleteOutfit(id: String)
    fun reloadFromDisk()
}

/**
 * The recovery-owned seam, deliberately **not** a member of [WardrobeRepository].
 *
 * ### Why it is a separate capability and not a public method
 *
 * The restore has to republish the in-memory wardrobe from disk at a point where the business gate is
 * still closed — `durable state coherent -> republish -> gate READY`. `reloadFromDisk` cannot serve that
 * (it is gate-checked, correctly, because it is a business operation), so the restore needs an ungated
 * entry point. Putting that entry point on [WardrobeRepository] would hand every UI caller, every
 * ViewModel, and every future assistant a gate bypass on the same interface they already hold — the
 * "narrow internal seam" would exist in name only.
 *
 * Kotlin cannot mark an interface member `internal`, so the previous placement could only be defended by
 * a comment. A separate `internal` interface makes the compiler enforce it instead: a caller must hold a
 * [WardrobeRecoveryPublisher].
 *
 * Note what `internal` actually enforces: **module** visibility. Any code in this Gradle module — `:app`,
 * and `src/test` as a friend source set — may reference [WardrobeRecoveryPublisher], and code in another
 * module may not. It is not the stronger claim the earlier wording made ("nothing outside the backup
 * package can obtain one"), because Kotlin has no package-private modifier; within `:app`, the separation
 * is by convention and by the type being awkward to obtain, not by the compiler. The compiler-enforced
 * part is the module boundary, which is the part that matters for keeping this off the app's published
 * surface.
 *
 * ### What implementors must guarantee
 *
 *  1. **Republish, never write.** It reads the durable files and replaces the in-memory snapshots. It must
 *     not perform any durable mutation of its own.
 *  2. **Checked.** It reads *all* durable surfaces into locals first and publishes only if every one
 *     succeeded; a partial read must throw rather than publish an empty or half-updated snapshot. See
 *     [LocalWardrobeRepository.reloadFromDiskForRecoveryChecked].
 *  3. **No gate check of its own.** Calling it while the gate is closed is the expected usage — it is
 *     called by the restore that closed it. The *ordering* guarantee (`before endRestoreReady`) belongs to
 *     the caller.
 */
internal interface WardrobeRecoveryPublisher {
    /**
     * Republish the in-memory wardrobe from disk, for the restore that owns the closed gate.
     *
     * @throws Exception when any durable surface could not be read or parsed. The caller must treat that
     *   as a failed recovery reload — the gate must not be reopened on a memory/disk disagreement.
     */
    fun reloadFromDiskForRecoveryChecked()
}
