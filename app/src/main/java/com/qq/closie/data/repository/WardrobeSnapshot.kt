package com.qq.closie.data.repository

import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.Ootd
import com.qq.closie.data.model.Outfit
import com.qq.closie.data.model.WashEvent
import com.qq.closie.data.model.WearEvent

/**
 * The whole in-memory wardrobe as one immutable value: the canonical shape of a wardrobe generation.
 *
 * ### Why the five lists are one type and not five fields on the repository
 *
 * A save in this app is almost never one list. Deleting an item rewrites `items.json` **and**
 * `wear.json` **and** `wash.json`; saving an OOTD rewrites `ootds.json` and reads `items.json`; the
 * post-restore republish replaces all five. Every one of those is a *logical* change that a screen can
 * observe halfway through if the surfaces are separate stores — and it does, because a screen such as
 * 衣橱 collects `items` + `wearEvents` + `washEvents` and renders `wearCount` next to an item name.
 * Half-observed, that screen shows the deleted item's wear count against the new list, or vice versa.
 *
 * Modelling the five lists as one value is what makes **each emission** atomic as a property of the
 * *data shape* rather than a property of the publishing code's care: there is no half-updated value,
 * because a half-updated value is not a value this type can hold. See [WardrobeRepository.snapshot]
 * for why consumers must collect *this* rather than the per-surface flows when they need more than one list.
 *
 * ### What this does NOT guarantee
 *
 * Atomicity here is per-emission, not per-logical-mutation. A single user action such as `deleteItem`
 * still calls `putItems() → publish`, `putWear() → publish`, `putWash() → publish` in sequence, so
 * a collector will see two or three intermediate snapshots before the logical operation is complete.
 * What this type prevents is **cross-Flow tearing** — a screen that needs items + wear + wash will
 * never render the new items against the old wear, because it reads them from one emission, not from
 * three independent flows with three resume points. It does **not** collapse a multi-step business
 * transaction into a single publish; that would require a JSON-level write transaction or a batching
 * mechanism, which is a separate architecture concern and is not part of this change.
 *
 * ### Immutability is the mechanism, not a style choice
 *
 * Every field is an immutable `List` and [copy] is the only way to produce a variant, so a publish is
 * always "take the current value, produce a new whole", never "mutate a part in place". The window in
 * which a single emission is half-updated does not exist, so it cannot be observed.
 *
 * ### Visibility: `public`
 *
 * This type appears in [WardrobeRepository]'s signature, and Kotlin forbids a public interface from
 * exposing an `internal` type — a caller outside the module could reach a type it cannot name. It was
 * `internal` while it lived entirely behind the implementation; publishing it is the price of making
 * one coherent collect possible, and it is a small one: the type is a pure immutable data holder with
 * no behaviour, no storage access and no way to mutate the repository.
 *
 * @property items clothing items, sorted by `updatedAt` descending (the order the UI relies on).
 * @property wearEvents wear events, in file order.
 * @property washEvents wash events, in file order.
 * @property ootds saved outfits, in file order.
 * @property outfits outfit-studio outfits, in file order.
 */
data class WardrobeSnapshot(
    val items: List<ClothingItem>,
    val wearEvents: List<WearEvent>,
    val washEvents: List<WashEvent>,
    val ootds: List<Ootd>,
    val outfits: List<Outfit>
) {
    /** A snapshot with no rows anywhere — the state of a fresh install. */
    companion object {
        val EMPTY = WardrobeSnapshot(
            items = emptyList(),
            wearEvents = emptyList(),
            washEvents = emptyList(),
            ootds = emptyList(),
            outfits = emptyList()
        )
    }
}
