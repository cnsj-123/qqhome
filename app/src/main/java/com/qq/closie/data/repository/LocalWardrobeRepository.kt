package com.qq.closie.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qq.closie.data.ImageStore
import com.qq.closie.data.backup.RestoreRecoveryPendingException
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * JSON persistence with atomic replacement and observable in-memory snapshots.
 *
 * The refusal type this class throws — [RestoreRecoveryPendingException] — is owned by the restore
 * barrier that raises it (`data.backup`), not by this package. See that type for the reasoning.
 *
 * ### Visibility: `internal`, and what that does and does not mean
 *
 * This class is `internal` — **module** visibility, not package-private. Kotlin has no package-private
 * modifier; `internal` means "visible anywhere in this Gradle module (the `:app` module), and nowhere
 * outside it". The distinction matters for what it actually buys:
 *
 *  - **It is not a boundary against the rest of the app.** Every other class in `:app` — a ViewModel, a
 *    screen, another repository — can name this type and call it, exactly as before. Nothing about the
 *    call graph changes, and no caller had to be updated for this.
 *  - **It is a boundary against other modules.** A future `:feature-wardrobe` module could not reference
 *    this class directly; it would have to go through [WardrobeRepository]. That is the direction the
 *    architecture is already heading, and it is the real content of this modifier.
 *  - **It does not, by itself, hide the class from tests.** `src/test` is compiled with this module as a
 *    *friend* module (Kotlin's friend-path mechanism, wired by AGP for the unit-test source set), so
 *    `internal` declarations remain fully reachable from `app/src/test`. The tests that construct
 *    `LocalWardrobeRepository(context, testHooks = …)` keep compiling and keep testing the same code.
 *
 * The reason it is `internal` at all is a compile-time one, and it is worth recording because the naive
 * reading of it ("make it internal to hide it") is not the point:
 *
 * ```
 *   class LocalWardrobeRepository(…, private val testHooks: WardrobeTestHooks? = null)
 *   internal interface WardrobeTestHooks { … }
 * ```
 *
 * A `public` constructor may not expose an `internal` parameter type. Kotlin rejects that with *"public
 * constructor exposes its internal parameter type"*, because the constructor is part of the class's
 * public signature while its parameter is not — a caller outside the module could reach a type it cannot
 * name. The fix has to remove the `internal` type from the public signature, and there are exactly two
 * ways: make the parameter type public (which would put a *test seam* on the app's public surface — the
 * opposite of what it is for), or make the *enclosing class* internal, so the constructor is no longer
 * public either. The second is the one taken here: [WardrobeTestHooks] stays `internal`, the constructor
 * stays free of it as a public signature, and the class remains fully usable from `:app` and from the
 * test source set.
 *
 * ### The gate is per call, and what that leaves covered
 *
 * Constructing this class checks [RestoreStartupGate] once ([init]), and every read and every write
 * checks it again: reads in the accessors, writes in [write], which is the single chokepoint all five
 * `putXxx` helpers go through. The extra checks are not redundant. The `init` check runs once; a
 * repository held by a ViewModel outlives it, and an in-process restore that fails to compensate closes
 * the gate *after* those instances exist. Only a per-call check can stop them — see
 * [RestoreStartupGate.gated].
 *
 * ### Why every public mutation also takes a *lease*, not just a check
 *
 * A per-call `requireReady` proves the gate was open at that instant. It cannot make the operation
 * atomic with respect to a restore that starts a moment later. This class is the worst case for that,
 * because its mutations are **multi-file**:
 *
 * ```
 *   deleteItem(id):
 *     requireReady()
 *     putItems(...)      // items.json written
 *     putWear(...)       // wear.json written     <- beginRestore could start here
 *     putWash(...)       // ...and the restore swaps `closie/` underneath the rest
 *     putOotds(...)      // -> putOotds then throws, leaving items/wear/wash new and ootds/outfits old
 * ```
 *
 * A half-applied wardrobe mutation is exactly as bad as a half-restored one. So each public mutation runs
 * its whole durable sequence inside [RestoreStartupGate.withBusinessAccess]: while the lease is held the
 * restore cannot take ownership, and while a restore owns the gate no lease can be taken. The inner
 * `putXxx` helpers do **not** acquire leases of their own — they are internal steps of one operation, and
 * letting each decide its own restore ownership is what would allow a restore to start between two of
 * them.
 *
 * ### One canonical snapshot, and five compatibility views
 *
 * The in-memory truth is a single [MutableStateFlow] of [WardrobeSnapshot], exposed as
 * [WardrobeRepository.snapshot]. This is what makes "restore republishes the wardrobe" atomic with
 * respect to a collector that happens to be resuming on another dispatcher: five `_x.value =`
 * assignments are five atomic stores, and a collector can resume *between* two of them and compose a
 * wardrobe out of two generations. One `snapshot.value =` is one store, so no observer — resuming
 * anywhere — can see a mixed pair.
 *
 * The five `StateFlow`s on [WardrobeRepository] survive for single-surface screens, fed from the same
 * publish. They are deliberately **not** documented as cross-surface atomic, because no arrangement of
 * five stores can be: a collector on `items` cannot be held while `wearEvents` is assigned, and
 * `synchronized` excludes writers rather than readers. A screen that reads several surfaces must
 * collect [WardrobeRepository.snapshot] once — see that property, and [publishLock] for what the lock
 * still does and does not do.
 */
internal class LocalWardrobeRepository(
    private val context: Context,
    /**
     * The seam the concurrency test needs, and the only reason it exists.
     *
     * ### What it is for
     *
     * The property "a wardrobe write that is already inside its durable sequence makes `beginRestore`
     * refuse" can only be *observed* if a write can be held open mid-sequence. Without a seam the test has
     * to hold the lease itself:
     *
     * ```
     *   test: RestoreStartupGate.withBusinessAccess { realRepo.createItem(...); park() }
     *   test: assertThat(beginRestore()).isFalse()
     * ```
     *
     * That is not a test of this class — it is the gate asserted against itself, with the test supplying
     * the very lease it claims the repository takes. It would keep passing if `createItem` stopped taking
     * a lease entirely.
     *
     * So the *production* call is given a place to park. `createItem` takes its real lease, writes its real
     * file, and then calls [afterDurableWrite] while still holding the lease; the test parks there, another
     * thread asserts `beginRestore()` is `false`, and the write completes. Nothing about the assertion is
     * written into the test.
     *
     * ### Why it cannot affect production
     *
     * Default `null` — a no-op lookup on the hot path and no branch in the durable sequence. It is called
     * *after* the durable write and *inside* the lease, so it can neither change what is written nor
     * lengthen the window a restore has to avoid (it can only lengthen the lease, which is what the test
     * wants to observe). Nothing in `app/src/main` passes it.
     *
     * ### Why it is a constructor parameter and not part of the interface
     *
     * [WardrobeRepository] is the boundary every ViewModel holds; a test hook there would change the app's
     * contract and be reachable through it. As a defaulted parameter on the `internal` implementation it
     * is invisible to every existing caller (`LocalWardrobeRepository(context)` still compiles and behaves
     * identically), and it never appears in a public signature — which is also what keeps the `internal`
     * [WardrobeTestHooks] type out of a public constructor.
     */
    private val testHooks: WardrobeTestHooks? = null
) : WardrobeRepository, WardrobeRecoveryPublisher {
    private val gson = Gson()
    private val folder = File(context.filesDir, "closie")
    private val itemType = object : TypeToken<List<ClothingItem>>() {}.type
    private val wearType = object : TypeToken<List<WearEvent>>() {}.type
    private val washType = object : TypeToken<List<WashEvent>>() {}.type
    private val ootdType = object : TypeToken<List<Ootd>>() {}.type
    private val outfitType = object : TypeToken<List<Outfit>>() {}.type

    /**
     * The five surface views' setters, filled in by [projection] during construction.
     *
     * ### Why this is declared *here*, above everything that uses it
     *
     * Kotlin initialises properties in **declaration order**, and an initialiser that reads a property
     * declared later sees the JVM default (`null` for a reference type) rather than an error. The
     * previous revision declared this list *after* the five projection property
     * initialisers, so the first `projections += …` inside the seeding helper added to `null` and the
     * constructor died with an NPE on every construction.
     *
     * The fix is not to move one line but to remove the whole class of bug: no property initialiser in
     * this class builds a projection any more. They are built by an explicit, ordered block inside
     * [init], which runs after every property declared above it — including this one and
     * [_snapshot] — has been initialised. Declaration order is still load-bearing, so the order below is
     * deliberate and commented; but it is now enforced by the compiler rather than by remembering.
     */
    private val projections = mutableListOf<(WardrobeSnapshot) -> Unit>()

    /**
     * The five per-surface stores, filled in by [projection] during [init].
     *
     * ### Why these are declared *here*, above the `override val` views
     *
     * Same trap as [projections], one level down: the `override val items: StateFlow<…>` views are
     * `lateinit var`s assigned in [init], and Kotlin rejects assigning a variable before its own
     * declaration even inside `init` (`variable cannot be initialized before declaration`). So the
     * mutable stores — which the `init` block assigns — have to be declared above them, and the
     * read-only views wrap them with `asStateFlow()`.
     *
     * The ordering rules this file now obeys, top to bottom:
     *
     * ```
     *   projections        (val, empty list)   <- needs nothing
     *   _snapshot          (lateinit var)      <- assigned in init, line 1
     *   _items … _outfits  (lateinit var)      <- assigned in init, line 2 (needs _snapshot)
     *   items … outfits    (lateinit var)      <- assigned in init, line 3 (needs _items …)
     *   snapshot           (lateinit var)      <- assigned in init, line 3 (needs _snapshot)
     * ```
     *
     * Every assignment lives in the one `init` block, in that order, so no property initialiser can run
     * a helper before its dependencies exist — which is the whole bug this restructure removes.
     *
     * `private` because nothing outside this class may swap one out; inside, only [init] assigns them and
     * only [publishSnapshot] advances them.
     */
    private lateinit var _items: MutableStateFlow<List<ClothingItem>>
    private lateinit var _wearEvents: MutableStateFlow<List<WearEvent>>
    private lateinit var _washEvents: MutableStateFlow<List<WashEvent>>
    private lateinit var _ootds: MutableStateFlow<List<Ootd>>
    private lateinit var _outfits: MutableStateFlow<List<Outfit>>

    /**
     * The single in-memory source of truth: one store holding all five wardrobe surfaces.
     *
     * ### Why it is `lateinit` and not initialised in its own declaration
     *
     * The construction read — five file reads, checked by the gate — has to happen *after* [projections]
     * exists, because publishing the initial value is what seeds the projections. A `val _snapshot =
     * MutableStateFlow(…)` initialiser cannot express that ordering: it would run before [projections]
     * if declared above it, and its own seeding of the projections would run before them if declared
     * below. `lateinit var` assigned inside [init] states the ordering explicitly and cannot be reordered
     * by a later edit without a compile error at the first read.
     *
     * Only [init] and [publishSnapshot] ever assign it; everything else goes through [publishSnapshot].
     */
    private lateinit var _snapshot: MutableStateFlow<WardrobeSnapshot>

    /**
     * Read-only view of [_snapshot], and the flow [WardrobeRepository.snapshot] exposes.
     *
     * `.asStateFlow()` rather than `.map { it }`: `map` returns a cold `Flow`, which loses both
     * properties callers depend on — a late subscriber would not receive the current value, and `.value`
     * would not exist. `asStateFlow()` is a view over the *same* store, so it is hot, replayable, has
     * `.value`, and hands out the identical emission instance the repository published.
     *
     * Declared after [init] in source order is irrelevant here — it is a view over a reference, so
     * assigning it at declaration time would capture an uninitialised `_snapshot` and NPE on first use.
     * It is therefore also assigned in [init], last, after `_snapshot` exists. That dependency is exactly
     * why this class no longer relies on declaration order for anything but the trivial fields above.
     */
    override lateinit var snapshot: StateFlow<WardrobeSnapshot>
        private set

    /**
     * The published per-surface views — what [WardrobeRepository.items] and friends resolve to.
     *
     * ### What these are, and the guarantee they do *not* carry
     *
     * Each wraps one of the private stores above, advanced from the canonical snapshot by
     * [publishSnapshot]'s fan-out. They exist for the screens that show exactly one surface — the
     * editor's item list, the detail screen's single item — where one flow per surface is the simpler
     * read and no cross-surface question arises.
     *
     * What they are **not** is cross-surface atomic, and this documentation says so deliberately,
     * because an earlier revision claimed otherwise and the claim was false: five stores are five
     * resume points, so a screen collecting three of them can be woken on `items` before `wearEvents`
     * and observe `(new items, old wear)`. `synchronized` excludes writers, not readers, so nothing in
     * this class closes that. A screen that needs more than one surface must collect
     * [WardrobeRepository.snapshot] once instead — see that property for the full reasoning.
     *
     * `asStateFlow()` gives a read-only view of the same store, so callers keep `.value`, get the
     * current value on a late subscribe, and cannot assign. They are `lateinit` for the same reason
     * [snapshot] is: each wraps a store assigned in [init], and the declaration has to sit above the
     * `init` block that assigns it.
     */
    override lateinit var items: StateFlow<List<ClothingItem>>
        private set
    override lateinit var wearEvents: StateFlow<List<WearEvent>>
        private set
    override lateinit var washEvents: StateFlow<List<WashEvent>>
        private set
    override lateinit var ootds: StateFlow<List<Ootd>>
        private set
    override lateinit var outfits: StateFlow<List<Outfit>>
        private set

    init {
        // The gate, not a second recovery.
        //
        // Recovery itself is owned by `ClosieApplication.onCreate`, which runs the whole barrier —
        // filesystem *and* database — before any repository can be created. That is the guarantee this
        // class depends on, and it is why there is no recovery call here any more: a partial,
        // filesystem-only repair performed from a constructor could leave the Closet reverted while the
        // database was still on the backup's rows, which is precisely the split the barrier exists to
        // prevent.
        //
        // Checking the gate here keeps the guarantee local to the class that actually reads `closie/`.
        // It matters for a repository constructed directly (a test, or any future entry point that does
        // not go through the Application), where nothing has resolved the gate yet — the initial value
        // is BLOCKED, so an unrepaired process fails closed rather than serving a mixture.
        //
        // This used to also refresh the marker's own state; it no longer can, because the gate is the
        // single verdict now, and duplicating the decision in two places is how the two drift.
        //
        // The refusal itself lives on the gate (`requireReady`) rather than being spelled out here, so
        // this class and every Life OS accessor share one implementation of "fail closed". Two copies of
        // `if (!isReady) throw …` would be two places for the message, the cause and the condition to
        // diverge — and only one of them would get fixed.
        RestoreStartupGate.requireReady()
        folder.mkdirs()

        // ---- Ordered initialisation. Every line below depends on the ones above it. --------------
        //
        // 1. The construction read. `read` is gate-checked and returns an empty list on an unreadable
        //    file, so this is the one place the wardrobe is loaded from disk without a mutation.
        //    `items` is sorted by `updatedAt` descending here, once, because that is the order the UI
        //    relies on and the order `copy()` preserves through every later publish.
        _snapshot = MutableStateFlow(
            WardrobeSnapshot(
                items = read<ClothingItem>("items.json", itemType).sortedByDescending { it.updatedAt },
                wearEvents = read<WearEvent>("wear.json", wearType),
                washEvents = read<WashEvent>("wash.json", washType),
                ootds = read<Ootd>("ootds.json", ootdType),
                outfits = read<Outfit>("outfits.json", outfitType)
            )
        )

        // 2. The five per-surface stores of that value. Built here, not in property initialisers, so the
        //    projection helper can never run before `projections` and `_snapshot` exist — the ordering
        //    bug that killed construction outright.
        //
        //    They are stores fed by the fan-out, not `stateIn` views: a `stateIn` needs a scope and a
        //    subscription to become hot, whereas these are correct — and readable via `.value` — from the
        //    instant construction finishes. A screen that starts collecting mid-session therefore still
        //    gets the current value immediately, which is what makes this a drop-in replacement for the
        //    five independent stores it replaces.
        _items = projection { it.items }
        _wearEvents = projection { it.wearEvents }
        _washEvents = projection { it.washEvents }
        _ootds = projection { it.ootds }
        _outfits = projection { it.outfits }

        // 3. The published views, last, because they wrap the stores assigned above. `.asStateFlow()` on
        //    each keeps the read-only contract: callers get `.value` and a replay on subscribe, and
        //    nothing outside this class can assign.
        items = _items.asStateFlow()
        wearEvents = _wearEvents.asStateFlow()
        washEvents = _washEvents.asStateFlow()
        ootds = _ootds.asStateFlow()
        outfits = _outfits.asStateFlow()
        snapshot = _snapshot.asStateFlow()
    }

    /**
     * Reads one JSON file from `closie/`.
     *
     * Gated per call, not once in `init` — see [write] for why; the two are the only paths by which this
     * repository touches the disk, so gating them is gating the repository.
     */
    private fun <T> read(name: String, type: java.lang.reflect.Type): List<T> {
        RestoreStartupGate.requireReady()
        return runCatching { File(folder, name).takeIf { it.exists() }?.let { gson.fromJson<List<T>>(it.readText(), type) } ?: emptyList() }
            .getOrDefault(emptyList())
    }

    /**
     * Writes one JSON file into `closie/`.
     *
     * ### Why the gate is here and not only in `init`
     *
     * Every mutation in this class — `createItem`, `updateItem`, `deleteItem`, `addWear`, `addWash`,
     * `saveOotd`, `saveOutfit`, and every delete — reaches the disk through one of the five `putXxx`
     * helpers, and every one of those calls this method. It is therefore the single chokepoint for
     * writes, and checking the gate here covers all of them with one line.
     *
     * The `init` check is still needed, but it is not sufficient. It runs once, at construction; a
     * repository instance held by a ViewModel that was built while the gate was READY keeps writing
     * after the gate closes, and nothing at construction time can retroactively stop it. Per-call is
     * the only form that can — see [RestoreStartupGate.gated].
     */
    private fun write(name: String, value: Any) {
        RestoreStartupGate.requireReady()
        val f = File(folder, name)
        val t = File(folder, ".$name.tmp")
        t.writeText(gson.toJson(value))
        if (!t.renameTo(f)) { f.delete(); check(t.renameTo(f)) }
    }

    /**
     * Builds one surface's store from a selector over the canonical snapshot, registering its setter in
     * [projections].
     *
     * Called only from [init], in a fixed order, after both [projections] and [_snapshot] exist — which
     * is the whole point of the restructure: it can no longer be reached from a property initialiser
     * that runs too early.
     *
     * `StateFlow` conflation is preserved for free: assigning a value equal to the one already held
     * suppresses the emission exactly as a standalone `MutableStateFlow` did, so a publish that changes
     * only `ootds.json` does not wake the `items` collector.
     */
    private fun <T> projection(select: (WardrobeSnapshot) -> T): MutableStateFlow<T> =
        MutableStateFlow(select(_snapshot.value)).also { store ->
            projections += { next -> store.value = select(next) }
        }

    /**
     * The single publish path: one value, one canonical store, one fan-out.
     *
     * Both writers — a business mutation and the post-restore republish — go through here, and both call
     * it while holding [publishLock], so a publish is never interleaved with another publish.
     * [WardrobeSnapshot] is immutable, so the fan-out below reads one coherent value: every projection is
     * updated from the same instance.
     *
     * Note the order, and note what it does and does not buy. The canonical store is assigned **first**,
     * so any collector of [WardrobeRepository.snapshot] that resumes at any point after this line sees
     * the new generation whole. The five per-surface stores follow, one assignment each; a collector of
     * several of *those* can still resume between two of them. Sequencing the canonical assignment first
     * is what makes the recommended read path correct even in principle — but it cannot make five stores
     * atomic, and that is why the recommended read path is the canonical one.
     */
    private fun publishSnapshot(next: WardrobeSnapshot) {
        _snapshot.value = next
        projections.forEach { it(next) }
    }

    /** Private conveniences for the mutation bodies, so they read as they did before the snapshot. */
    private val currentItems: List<ClothingItem> get() = _snapshot.value.items
    private val currentWear: List<WearEvent> get() = _snapshot.value.wearEvents
    private val currentWash: List<WashEvent> get() = _snapshot.value.washEvents
    private val currentOotds: List<Ootd> get() = _snapshot.value.ootds
    private val currentOutfits: List<Outfit> get() = _snapshot.value.outfits

    /**
     * Serialises the two things that replace the wardrobe wholesale: a business mutation and the
     * post-restore recovery republish.
     *
     * ### What it guarantees, and what it does not have to
     *
     * A durable write and a recovery republish must not interleave: both hold this lock across their
     * whole read-modify-publish, so one never begins from a base the other is halfway through replacing.
     * The lock is held only across the publish, not across the surrounding durable write, so it is a
     * short critical section and not a throughput concern.
     *
     * What it does **not** have to do any more is paper over a five-store publish. It used to: the five
     * `_x.value =` assignments were five independent stores, and no lock can stop a *collector* from
     * resuming between two of them — a reader is not a writer, and mutual exclusion among writers is
     * simply the wrong tool. That is why the in-memory truth is now one [WardrobeSnapshot] behind one
     * `StateFlow`: there is one store, one atomic assignment, and therefore no interleaving for the lock
     * to close. The lock's remaining job is the narrower one above.
     */
    private val publishLock = Any()

    /**
     * The number of **completed** wholesale publishes (business mutation or recovery republish).
     *
     * Read by tests to detect a cross-generation observation; `internal` so it is not part of the app's
     * public surface, and read-only from outside so no caller can move it. Advanced only *after* the
     * single snapshot assignment, so "generation N is visible" means "the whole snapshot is from N".
     *
     * Note what a reader can and cannot conclude from it. Reading it is **not** atomic with reading the
     * snapshot — a caller that samples `items` and then this counter can always be overtaken — which is
     * why the tests use it only for the monotonicity property ("generations never go backwards") and
     * compare generations *within* a single observed emission rather than across two reads.
     */
    @Volatile
    internal var publishedGeneration: Long = 0L
        private set

    init { LegacyMigration(context, this).runIfNeeded() }

    /**
     * The five write paths, in the order that makes a refused write leave **nothing** behind.
     *
     * ### The bug this ordering fixes
     *
     * Each helper used to publish to its `StateFlow` first and check the gate second, inside [write]:
     *
     * ```
     *   deleteWearEvent(id)
     *     -> putWear(remaining)
     *     -> _wear.value = remaining          <-- the UI now shows the deletion
     *     -> write("wear.json", remaining)
     *     -> RestoreStartupGate.requireReady() throws
     *   result: disk unchanged, in-memory CHANGED, an exception on the caller's stack
     * ```
     *
     * The disk was correct — the gate did its job — and the *user* was lied to: the list on screen showed
     * an edit that had not happened and would not survive the process. Worse, that in-memory value is now
     * the base for the next mutation, so a later successful write would persist an edit the user was told
     * had failed. And because a restore is exactly what closes the gate, the lie appeared precisely when
     * the underlying data was most in flux.
     *
     * Nothing about the gate should be observable as a *partial* success, so the durable write goes first
     * and the in-memory publish happens only after it returns. `write` still calls `requireReady` itself;
     * the explicit call here is what makes the failure happen before the publish rather than between the
     * two steps, and it is also what keeps the check visible at the point the ordering depends on.
     *
     * Each helper now replaces the *whole* snapshot, carrying the four lists it is not changing over
     * unchanged. That is what keeps a single emission internally consistent: the new value is built from
     * the old one and published in one store, rather than mutating one of five independent flows.
     *
     * @throws RestoreRecoveryPendingException before anything is written or published.
     */
    private fun putItems(v: List<ClothingItem>) {
        RestoreStartupGate.requireReady()
        val sorted = v.sortedByDescending { it.updatedAt }
        synchronized(publishLock) {
            write("items.json", sorted)
            publishSnapshot(_snapshot.value.copy(items = sorted))
            publishedGeneration++
        }
    }
    private fun putWear(v: List<WearEvent>) {
        RestoreStartupGate.requireReady()
        synchronized(publishLock) {
            write("wear.json", v)
            publishSnapshot(_snapshot.value.copy(wearEvents = v))
            publishedGeneration++
        }
    }

    private fun putWash(v: List<WashEvent>) {
        RestoreStartupGate.requireReady()
        synchronized(publishLock) {
            write("wash.json", v)
            publishSnapshot(_snapshot.value.copy(washEvents = v))
            publishedGeneration++
        }
    }

    private fun putOotds(v: List<Ootd>) {
        RestoreStartupGate.requireReady()
        synchronized(publishLock) {
            write("ootds.json", v)
            publishSnapshot(_snapshot.value.copy(ootds = v))
            publishedGeneration++
        }
    }

    private fun putOutfits(v: List<Outfit>) {
        RestoreStartupGate.requireReady()
        synchronized(publishLock) {
            write("outfits.json", v)
            publishSnapshot(_snapshot.value.copy(outfits = v))
            publishedGeneration++
        }
    }

    /**
     * Reads go through the gate too, for the same reason writes do: a half-restored `closie/` is exactly
     * what the gate exists to stop being served, and an in-memory snapshot taken before the gate closed
     * is a snapshot of that half-restored state.
     *
     * The check is on the accessors rather than on the backing flow so that the private `currentXxx` reads
     * inside the mutation helpers stay single-gated (they all call [write] anyway) and the public surface
     * is uniformly refused.
     */
    override fun listItems() = RestoreStartupGate.gated { items.value }

    override fun getItem(id: String) = RestoreStartupGate.gated { items.value.find { it.id == id } }

    override fun createItem(item: ClothingItem): ClothingItem = RestoreStartupGate.withBusinessAccess {
        val s = item.copy(updatedAt = System.currentTimeMillis())
        putItems(currentItems.filterNot { it.id == s.id } + s)
        // The test seam: called *inside* the lease and *after* the durable write, so a test can park the
        // real operation here and assert from another thread that `beginRestore()` is refused. `null` in
        // production, so this is one null check on a path that just wrote a JSON file.
        //
        // `?.afterDurableWrite()` — a direct call, not `?.afterDurableWrite?.invoke()`. The latter is what
        // a Java-style method reference looks like and it does not compile here: on a *function* member the
        // `f?.invoke()` form requires a property of function *type*, and the declaration is a plain
        // `fun afterDurableWrite()`. The safe-call already supplies the null branch, so the direct call is
        // both shorter and the only correct spelling.
        testHooks?.afterDurableWrite()
        s
    }

    override fun updateItem(item: ClothingItem): ClothingItem = RestoreStartupGate.withBusinessAccess {
        // Inside the lease, so the `getItem`, the `putItems` and the image deletes below are one
        // indivisible business operation: a restore cannot start between reading the old images and
        // removing the ones the update orphaned.
        val old = getItem(item.id)
        val s = item.copy(updatedAt = System.currentTimeMillis())
        putItems(currentItems.map { if (it.id == s.id) s else it })
        val removed = old?.images.orEmpty().mapNotNull { it.localPath }.toSet() - s.images.mapNotNull { it.localPath }.toSet()
        deleteUnreferenced(removed)
        s
    }

    override fun deleteItem(id: String) = RestoreStartupGate.withBusinessAccess {
        // One lease for all five files plus the image deletes. Acquiring per `putXxx` would let a restore
        // start after `putItems` and before `putWear`, leaving the wardrobe half-deleted — see the class
        // doc.
        val old = getItem(id)
        putItems(currentItems.filterNot { it.id == id })
        putWear(currentWear.filterNot { it.itemId == id })
        putWash(currentWash.filterNot { it.itemId == id })
        putOotds(currentOotds.map { it.copy(itemIds = it.itemIds.filterNot { x -> x == id }) })
        putOutfits(currentOutfits.map { it.copy(itemIds = it.itemIds.filterNot { x -> x == id }, placements = it.placements.filterNot { p -> p.itemId == id }) })
        old?.images.orEmpty().mapNotNull { it.localPath }.forEach { ImageStore.deletePrivatePath(context, it) }
    }

    private fun deleteUnreferenced(paths: Set<String>) {
        val used = currentItems.flatMap { it.images }.mapNotNull { it.localPath }.toSet()
        paths.filterNot { it in used }.forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun listWearEvents() = RestoreStartupGate.gated { wearEvents.value }

    override fun addWear(itemId: String, date: String, source: WearSource, ootdId: String?, note: String) =
        RestoreStartupGate.withBusinessAccess {
            if (getItem(itemId) == null) return@withBusinessAccess
            val exists = if (source == WearSource.MANUAL)
                currentWear.any { it.itemId == itemId && it.date == date && it.source == source }
            else
                currentWear.any { it.itemId == itemId && it.ootdId == ootdId && it.source == source }
            if (!exists) putWear(currentWear + WearEvent(itemId = itemId, date = date, source = source, ootdId = ootdId, note = note))
        }

    override fun addWash(itemId: String, date: String, note: String) =
        RestoreStartupGate.withBusinessAccess {
            if (getItem(itemId) != null && currentWash.none { it.itemId == itemId && it.date == date })
                putWash(currentWash + WashEvent(itemId = itemId, date = date, note = note))
        }

    override fun deleteWearEvent(id: String) =
        RestoreStartupGate.withBusinessAccess { putWear(currentWear.filterNot { it.id == id }) }

    override fun deleteWashEvent(id: String) =
        RestoreStartupGate.withBusinessAccess { putWash(currentWash.filterNot { it.id == id }) }

    override fun wearCount(itemId: String) = RestoreStartupGate.gated { wearEvents.value.count { it.itemId == itemId } }
    override fun washCount(itemId: String) = RestoreStartupGate.gated { washEvents.value.count { it.itemId == itemId } }

    override fun listOotds() = RestoreStartupGate.gated { ootds.value }

    override fun saveOotd(ootd: Ootd): Ootd = RestoreStartupGate.withBusinessAccess {
        val old = currentOotds.firstOrNull { it.id == ootd.id }
        val s = ootd.copy(
            itemIds = ootd.itemIds.distinct().filter { getItem(it)?.status == ItemStatus.OWNED },
            updatedAt = System.currentTimeMillis()
        )
        putOotds(if (currentOotds.any { it.id == s.id }) currentOotds.map { if (it.id == s.id) s else it } else currentOotds + s)
        val wanted = s.itemIds.toSet()
        putWear(currentWear.filterNot { it.source == WearSource.OOTD && it.ootdId == s.id && it.itemId !in wanted }
            .map { if (it.source == WearSource.OOTD && it.ootdId == s.id) it.copy(date = s.date, note = s.note) else it })
        // `addWear` takes a nested lease, which is just a counter increment — the outer lease is still the
        // one that keeps the whole save atomic.
        wanted.forEach { addWear(it, s.date, WearSource.OOTD, s.id, s.note) }
        val removed = old?.images.orEmpty().filterNot { it in s.images }
        removed.forEach { ImageStore.deletePrivatePath(context, it) }
        s
    }

    override fun deleteOotd(id: String) = RestoreStartupGate.withBusinessAccess {
        val old = currentOotds.firstOrNull { it.id == id }
        putOotds(currentOotds.filterNot { it.id == id })
        putWear(currentWear.filterNot { it.source == WearSource.OOTD && it.ootdId == id })
        old?.images.orEmpty().forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun listOutfits() = RestoreStartupGate.gated { outfits.value }

    override fun saveOutfit(outfit: Outfit): Outfit = RestoreStartupGate.withBusinessAccess {
        val old = currentOutfits.firstOrNull { it.id == outfit.id }
        val s = outfit.copy(updatedAt = System.currentTimeMillis())
        putOutfits(if (currentOutfits.any { it.id == s.id }) currentOutfits.map { if (it.id == s.id) s else it } else currentOutfits + s)
        val removed = old?.tryOnImages.orEmpty().filterNot { it in s.tryOnImages }
        removed.forEach { ImageStore.deletePrivatePath(context, it) }
        s
    }

    override fun deleteOutfit(id: String) = RestoreStartupGate.withBusinessAccess {
        val old = currentOutfits.firstOrNull { it.id == id }
        putOutfits(currentOutfits.filterNot { it.id == id })
        old?.tryOnImages.orEmpty().forEach { ImageStore.deletePrivatePath(context, it) }
    }

    override fun reloadFromDisk() =
        RestoreStartupGate.withBusinessAccess { reloadFromDiskForRecoveryChecked() }

    /**
     * Republishes the in-memory snapshot from disk **without** consulting the gate, for the restore that
     * owns the closed one.
     *
     * ### Why this exists
     *
     * A restore has to republish the wardrobe into the running process, and it has to do so at the one
     * moment the business gate is guaranteed to be **closed** — after the durable commit and before
     * `endRestoreReady`, so that the first read any business code performs already sees the restored
     * wardrobe rather than the pre-restore one still sitting in memory.
     *
     * The business `reloadFromDisk()` cannot serve that: it takes a lease, which requires a READY gate.
     * Opening the gate first to let the reload through would be worse than the problem — it would publish
     * a window in which the UI reads the *old* in-memory wardrobe against the *new* disk, and a user edit
     * in that window would be written over the restore.
     *
     * ### Why it is checked, and why that is not decoration
     *
     * An earlier revision published each surface as it read it, through a helper ending in
     * `.getOrDefault(emptyList())`. A single unreadable or unparseable file therefore became an **empty
     * list** — indistinguishable, to every downstream reader, from "the user genuinely has no items". The
     * process would then hold `items = []` (or a half-updated mixture: new items, old wear, empty wash)
     * while disk held the restored data, and the coordinator would still call `endRestoreReady()`.
     *
     * So this reads **all five** surfaces into locals first and publishes only once every one succeeded. A
     * failure throws with nothing published, which is the only shape that lets the caller decide what to do
     * — see [com.qq.closie.data.backup.RestoreCoordinator], which treats a failure after the commit point
     * as a reason to block the process rather than to serve a disagreement.
     *
     * ### Why one publish is now literally one publish
     *
     * This used to do five `_x.value =` assignments under [publishLock] and call the result "one publish",
     * with a paragraph explaining that a collector resuming mid-sequence could still see a mixed pair. It
     * could, and the lock was never able to prevent it: `synchronized` excludes other *writers*, while the
     * observer at issue is a *reader* resuming on another dispatcher. A generation counter did not help
     * either — it can describe a mixture after the fact, and only if the observer samples it in the same
     * emission, but it cannot stop one.
     *
     * The fix is not a stronger lock; it is one canonical store — **and consumers that read it**.
     * All five lists now live in a single [WardrobeSnapshot] behind a single `MutableStateFlow`, so this
     * function builds one immutable value and assigns it once: no emission of `_snapshot` can hold lists
     * from two generations.
     *
     * The second half of that sentence is not decoration, and an earlier revision got it wrong. Assigning
     * one store makes the *snapshot* free of mixtures, but it does not by itself make a **screen** free of
     * them: the five per-surface `StateFlow`s are still five stores, and a screen that collects three of
     * them can be resumed on `items` before `wearEvents` and render a mixed pair even though the canonical
     * value was never mixed. So the guarantee is only realised for consumers that collect
     * [WardrobeRepository.snapshot] — which is why that property exists, why the multi-surface screens were
     * converted to it, and why the per-surface flows are documented as *not* cross-surface atomic rather
     * than quietly implying they are.
     *
     * [publishLock] is still taken, but only to serialise this against a business publish (they must not
     * interleave their read-modify-write), which is what it is actually good for.
     *
     * It performs **no durable write**, which is what makes it legitimate under a closed gate.
     *
     * @throws Exception when any surface could not be read or parsed. Nothing has been published.
     */
    override fun reloadFromDiskForRecoveryChecked() {
        val items = readForRecoveryChecked<ClothingItem>("items.json", itemType).sortedByDescending { it.updatedAt }
        val wear = readForRecoveryChecked<WearEvent>("wear.json", wearType)
        val wash = readForRecoveryChecked<WashEvent>("wash.json", washType)
        val ootds = readForRecoveryChecked<Ootd>("ootds.json", ootdType)
        val outfits = readForRecoveryChecked<Outfit>("outfits.json", outfitType)
        // Every read succeeded. Build one immutable value and publish it in one store, under the same lock
        // a business publish takes — see this function's doc for why one store is the whole guarantee.
        synchronized(publishLock) {
            publishSnapshot(WardrobeSnapshot(items = items, wearEvents = wear, washEvents = wash, ootds = ootds, outfits = outfits))
            publishedGeneration++
        }
    }

    /**
     * The ungated, **checked** read used by [reloadFromDiskForRecoveryChecked].
     *
     * Ungated because the caller runs while the gate is closed by design. Checked — as opposed to the
     * business [read], which deliberately falls back to an empty list for a file that does not exist — so
     * that a *failure* to read cannot masquerade as an empty collection.
     *
     * A missing file is still `emptyList()`: that is the legitimate "the user has none of these" case, and
     * it is what `putXxx` writes for a list emptied by deletion. An **unreadable or unparseable** file is
     * not, and propagates.
     *
     * @throws Exception when the file exists but cannot be read or parsed.
     */
    private fun <T> readForRecoveryChecked(name: String, type: java.lang.reflect.Type): List<T> {
        val file = File(folder, name)
        if (!file.exists()) return emptyList()
        // No `runCatching`: an I/O or parse failure must reach the caller, because publishing an empty
        // list in its place is the exact lie this function exists to prevent.
        return gson.fromJson<List<T>>(file.readText(), type) ?: throw IllegalStateException("恢复重载：$name 解析为 null")
    }
}

/**
 * Every wardrobe surface, in one immutable value: the single in-memory truth of a wardrobe.
 *
 * ### Why this type exists
 *
 * The wardrobe is five lists that are only meaningful **together**. `wear.json` records wear events that
 * name item ids; `ootds.json` records outfits that name item ids *and* whose wear events are derived from
 * them; a delete removes an item and rewrites the events and outfits that referenced it. A reader that
 * takes one list from generation *N* and another from generation *N-1* is not looking at a slightly stale
 * wardrobe — it is looking at a wardrobe that never existed on disk, and it can be internally
 * contradictory (a wear event for an item that is not there).
 *
 * So the five lists are modelled as one value, and the repository holds one `StateFlow` of it. Publishing
 * is then a single atomic assignment and *every* observer, on any dispatcher, resumed at any instant, sees
 * a coherent set — the cross-generation mixture is not narrowed or made unlikely, it is made
 * unrepresentable.
 *
 * This is per-emission coherence, not per-logical-mutation coherence: `deleteItem` still publishes
 * three times (items, then wear, then wash), and a collector will see two intermediate snapshots.
 * Each snapshot is internally consistent; the logical operation is not complete until the third
 * publish. Batching a logical mutation into a single publish is not addressed here.
 *
 * ### Immutability is the mechanism, not a style choice
 *
 * `copy()` is the only way to change anything, so a publish is always "take the current value, produce a
 * new whole", never "mutate a part in place". That is what makes the invariant hold under concurrency:
 * there is no window in which the value is half-updated, because the half-updated value is never a value.
 *
 * ### Relationship to the public flows
 *
 * [WardrobeRepository] still exposes `items`, `wearEvents`, `washEvents`, `ootds` and `outfits` as
 * `StateFlow`s, because that is the UI's contract and changing it would be a migration of the app's
 * hottest read path. They are now *projections* of this one flow rather than five independent stores, so
 * the contract is unchanged while the guarantee behind it is strictly stronger.
 *
 * @property items clothing items, sorted by `updatedAt` descending (the order the UI relies on).
 * @property wearEvents wear events, in file order.
 * @property washEvents wash events, in file order.
 * @property ootds saved outfits, in file order.
 * @property outfits outfit-studio outfits, in file order.
 */
/**
 * The one injection point the wardrobe's concurrency test needs.
 *
 * ### Why this exists at all
 *
 * The property under test is *"a wardrobe write already inside its durable sequence makes `beginRestore`
 * refuse"*. Observing it requires a write to be held open mid-sequence, and there is no way to do that
 * from outside: `createItem` is synchronous and its lease is taken and released inside the call.
 *
 * The alternative — which the previous version of the test used — is for the *test* to open the lease and
 * then call a repository method inside it:
 *
 * ```
 *   RestoreStartupGate.withBusinessAccess {
 *       wardrobe.createItem(...)     // the repository's own lease is a no-op nested increment
 *       park()
 *   }
 * ```
 *
 * That asserts the gate against itself. The test supplies the very lease it claims to be testing, so the
 * test keeps passing if `createItem` stops taking a lease entirely — the one regression it is supposed to
 * catch. Writing the property under test into the test is the failure mode this type removes.
 *
 * ### Why it is `internal` and not public
 *
 * `internal` here is **module** visibility: this type is usable anywhere in the `:app` module, including
 * `app/src/test` (which AGP compiles as a friend of the module), and nowhere outside it. It is not
 * package-private — Kotlin has no such modifier — so the point is not "only `data.repository` may see it";
 * any class in `:app` may. The point is that it is *not* on the app's public surface, which is what it
 * would become if it were public, since [LocalWardrobeRepository] is `internal` and its constructor
 * parameter therefore never appears in a public signature.
 *
 * Called **after the durable write and inside the lease**, so it can neither alter what was written nor
 * shorten the window a restore must avoid.
 */
internal interface WardrobeTestHooks {
    /** Invoked while the calling operation still holds its business lease, after its durable write. */
    fun afterDurableWrite()
}

internal class LegacyMigration(private val context: Context, private val repo: WardrobeRepository) {    fun runIfNeeded() {
        val p = context.getSharedPreferences("closie_store", Context.MODE_PRIVATE)
        if (p.getBoolean("migrated_v1", false)) return
        runCatching {
            val g = Gson()
            val olds = g.fromJson<List<LegacyItem>>(p.getString("items", null), object : TypeToken<List<LegacyItem>>() {}.type) ?: emptyList()
            olds.forEach { old ->
                val id = old.id ?: UUID.randomUUID().toString()
                if (repo.getItem(id) == null) repo.createItem(old.toItem(id))
                old.wearDates.orEmpty().distinct().forEach { repo.addWear(id, it) }
                old.washDates.orEmpty().distinct().forEach { repo.addWash(id, it) }
            }
            val ootds = g.fromJson<List<LegacyOotd>>(p.getString("ootds", null), object : TypeToken<List<LegacyOotd>>() {}.type) ?: emptyList()
            ootds.forEach { old ->
                val id = old.id ?: UUID.randomUUID().toString()
                if (repo.listOotds().none { it.id == id }) repo.saveOotd(Ootd(id = id, date = old.date.orEmpty(), itemIds = old.itemIds.orEmpty(), note = old.note.orEmpty()))
            }
        }.onSuccess { p.edit().putBoolean("migrated_v1", true).apply() }
    }

    private fun LegacyItem.toItem(id: String) = ClothingItem(
        id = id,
        status = if (status == "RETURNED") ItemStatus.RETURNED else ItemStatus.OWNED,
        name = name.orEmpty(), category = category ?: "未分类", subcategory = subcategory.orEmpty(),
        brand = brand.orEmpty(), store = store.orEmpty(), purchasePlatform = purchasePlatform.orEmpty(),
        productUrl = productUrl.orEmpty(), price = price, originalPrice = originalPrice,
        purchaseDate = purchaseDate.orEmpty(), sizeLabel = sizeLabel.orEmpty(), safetyCategory = safetyCategory.orEmpty(),
        comment = comment.orEmpty(), rating = rating ?: 0, returnReason = returnReason.orEmpty(),
        materials = materials.orEmpty().map { MaterialPart(name = it.name.orEmpty(), percentage = it.percentage.orEmpty()) },
        measurements = measurements.orEmpty().map { Measurement(name = it.name.orEmpty(), value = it.value.orEmpty(), unit = it.unit ?: "cm") },
        images = images.orEmpty().mapNotNull { img ->
            img.uri?.let { u -> ImageStore.copyFromUri(context, Uri.parse(u))?.let { ClothingImage(kind = runCatching { ImageKind.valueOf(img.kind ?: "FLAT") }.getOrDefault(ImageKind.FLAT), localPath = it) } }
        }
    )

    private data class LegacyItem(val id: String?, val status: String?, val name: String?, val category: String?, val subcategory: String?, val brand: String?, val store: String?, val purchasePlatform: String?, val productUrl: String?, val price: Double?, val originalPrice: Double?, val purchaseDate: String?, val sizeLabel: String?, val safetyCategory: String?, val comment: String?, val rating: Int?, val returnReason: String?, val images: List<LegacyImage>?, val materials: List<LegacyMaterial>?, val measurements: List<LegacyMeasurement>?, val wearDates: List<String>?, val washDates: List<String>?)
    private data class LegacyImage(val kind: String?, val uri: String?)
    private data class LegacyMaterial(val name: String?, val percentage: String?)
    private data class LegacyMeasurement(val name: String?, val value: String?, val unit: String?)
    private data class LegacyOotd(val id: String?, val date: String?, val itemIds: List<String>?, val note: String?)
}
