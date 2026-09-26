package com.qq.closie.data.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide answer to one question: *may business code read the user's data yet?*
 *
 * ### Why a gate is needed at all
 *
 * A restore spans three independently-durable surfaces (the Closet directory tree, the Life OS media
 * directory tree, and the Room database). Until an interrupted restore has been fully repaired, those
 * three can legitimately disagree: the Closet may be back on the user's version while the database
 * still holds the backup's rows. Any repository that reads in that window serves a state that never
 * existed — and if the user writes during it, a snapshot replay that lands afterwards can overwrite
 * what they just wrote.
 *
 * The previous design ran the filesystem repair synchronously and the database repair in a background
 * coroutine. That is not a barrier: `Application.onCreate` returns, the UI starts, repositories are
 * constructed, and the database half is still in flight. The fix is to make the *whole* recovery —
 * filesystem and database — finish before any repository may be handed out, and to have a single
 * explicit place that records whether it succeeded.
 *
 * ### What this is not
 *
 * It is deliberately tiny. It holds one value and does nothing else — no restore logic, no coroutine
 * orchestration beyond a `StateFlow`, no dependency injection. The restore itself stays in
 * [RestoreCoordinator] and [BackupManager]; the gate only records the *verdict*, grants *ownership*,
 * and counts *in-flight business operations*.
 *
 * ### One canonical state — and exactly one
 *
 * Everything below is derived from a single [MutableStateFlow]. There is deliberately **no** second
 * `AtomicBoolean`/`AtomicReference`/`@Volatile var` recording any part of the verdict. Two
 * representations of one decision is how "what happened" and "what happened most recently" end up
 * disagreeing, and every reader then has to know which one to trust.
 *
 * That is not a stylistic preference — it is a race. The previous revision stored the terminal block as
 * a `@Volatile Boolean` beside the status:
 *
 * ```
 *   markReady():   reads the sticky flag == false
 *   other thread:  markRestoreUnfinished() -> sticky flag = true; _state = BLOCKED
 *   markReady():   ...continues: _state = READY
 *   -> sticky flag == true AND status == READY: two answers to one question
 * ```
 *
 * So the sticky flag lives *inside* [State] and is mutated only by a single compare-and-set, which is
 * what makes the whole verdict atomic by construction rather than by argument.
 *
 * | status                | business read/write | who ends it                             |
 * |-----------------------|---------------------|-----------------------------------------|
 * | [Status.READY]        | allowed             | —                                       |
 * | [Status.RESTORING]    | refused             | the owning restore, in this process     |
 * | [Status.BLOCKED]      | refused             | a later startup recovery pass           |
 *
 * `BLOCKED` is the initial value on purpose: "unanswered" must never read as "safe". A process that
 * never runs recovery (an isolated unit test constructing a repository directly) stays blocked until
 * something explicitly resolves it, which is the honest default.
 *
 * ### Why `RESTORING` is a distinct status and not just `BLOCKED`
 *
 * A restore in flight and a failed recovery both refuse business access, so one state could express
 * both. They are separated because they differ in exactly the way the lifecycle depends on:
 *
 *  - `RESTORING` is **owned by a live restore** and will end on its own, in this process, in one of two
 *    ways: the restore completes ([endRestoreReady]) or its compensation fails
 *    ([markRestoreUnfinished], which is terminal). A collector waiting on the gate must therefore expect
 *    to be resumed, which is what makes `emptyFlow` + re-subscribe the right policy in [gateAwareFlow].
 *  - `BLOCKED` is **nobody's active work**: it is cleared by a later startup recovery pass, not by
 *    whoever is holding it.
 *
 * It is also the mechanism for restore ownership. Acquiring a restore is a `READY -> RESTORING`
 * compare-and-set on this one flow ([beginRestore]), so two concurrent restores cannot both proceed and
 * no separate "restore in progress" flag exists to drift out of sync with the verdict.
 *
 * ### Why the gate owns a business-operation count
 *
 * A `requireReady()` check at the top of an operation proves only that the gate was open **at that
 * instant**. It is not a barrier, because operations are not instantaneous:
 *
 * ```
 *   LifeRepository.createEntity():   gated DAO getter -> ... -> suspend insert lands on disk
 *                                   ^ beginRestore() can slip into this window
 *   LocalWardrobe.createItem():      requireReady() -> write temp -> rename
 *                                   ^ and into this one
 * ```
 *
 * The gate closes *after* the check and *before* the write, so the write commits into a restore that is
 * already swapping the very tree it targets. The fix is for the gate to know how many business
 * operations are mid-flight: an operation holds a **lease** for its whole durable sequence
 * ([withBusinessAccess] / [withBusinessAccessSuspending]), and [beginRestore] refuses to start while any
 * lease is out. That makes the barrier **bidirectional**:
 *
 *  - a business operation already running   -> `activeBusinessOps > 0` -> the restore cannot start;
 *  - a restore already running              -> `status != READY`     -> no new lease can be taken.
 *
 * The count is a field of the same [State] as the status, so acquiring a lease and reading the verdict
 * are one atomic step, and there is no second lock to reason about. Leases are re-entrant by counting
 * (nested repository calls are normal), and are never held across a `synchronized` block or used to
 * block a thread — see [withBusinessAccessSuspending].
 */
internal object RestoreStartupGate {

    /**
     * The gate's verdict.
     *
     * `RESTORING` and `BLOCKED` are both refusals for business code; the difference is *who* ends them.
     * See the class doc.
     */
    internal enum class Status { READY, RESTORING, BLOCKED }

    /**
     * The entire gate verdict — the single source of truth for every question this object answers.
     *
     * Every field here changes together, under one compare-and-set, which is what removes the drift the
     * previous two-container design allowed:
     *
     *  - [status] — may business code touch data?
     *  - [error] — the failure behind a refusal, carried into [RestoreRecoveryPendingException].
     *  - [stickyUntilRestart] — a terminal, cannot-be-reopened `BLOCKED` ([markRestoreUnfinished]).
     *  - [activeBusinessOps] — how many business operations currently hold a lease.
     */
    internal data class State(
        val status: Status,
        val error: Throwable? = null,
        val stickyUntilRestart: Boolean = false,
        val activeBusinessOps: Int = 0
    ) {
        /** True when a new business operation may take a lease. */
        val canAcquireBusinessLease: Boolean get() = status == Status.READY

        /** True when a restore may take ownership right now. */
        val canBeginRestore: Boolean
            get() = status == Status.READY && !stickyUntilRestart && activeBusinessOps == 0
    }

    /**
     * The one canonical gate state. Every public member reads from here; nothing else stores a status,
     * a sticky flag, or an operation count.
     */
    private val _state = MutableStateFlow(State(Status.BLOCKED))

    /**
     * The gate as an observable value.
     *
     * Exposed because a long-lived Room `Flow` has to *react* to the gate rather than merely check it
     * once — see [gateAwareFlow]. Also the reason this is a `StateFlow`: it re-emits on every change, so
     * a flat-mapped flow can drop its durable subscription while blocked and re-establish it on the next
     * `READY`, without the collector that is already running having to do anything.
     *
     * Note for subscribers: this flow also re-emits when [State.activeBusinessOps] changes. A consumer
     * that only cares about whether it may subscribe must observe the *status* — see [statusFlow] — or it
     * will tear down and rebuild its subscription on every lease acquisition.
     */
    internal val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The status alone, de-duplicated — the signal a durable `Flow` should react to.
     *
     * [gateAwareFlow] observes this rather than [state] on purpose. Once [State] carries
     * `activeBusinessOps`, every business lease acquisition and release changes [state]; a flow that
     * flat-mapped over the whole [State] would cancel and re-subscribe its DAO subscription on each one,
     * producing pointless churn — and, worse, a *new query* each time. `map { it.status }
     * .distinctUntilChanged()` makes the re-subscription depend only on the thing that actually decides
     * it.
     */
    private val statusFlow: Flow<Status> = _state.map { it.status }.distinctUntilChanged()

    /**
     * [statusFlow] as a `StateFlow`, for UI that has to *render* the gate rather than merely check it.
     *
     * ### Why this exists
     *
     * A screen that reports the outcome of an export or a restore needs to tell "the app is restoring,
     * try again in a moment" apart from "this backup file is broken". Both arrive as `Result.failure`,
     * and the exception's own message is a technical one, so the screen has to look at the gate.
     * Reading [isRestoring] once is not enough: the flag can change while the message is on screen — a
     * restore finishes and the user retries — and a snapshot read would leave the wording stale.
     *
     * ### What it deliberately is not
     *
     * It exposes **only the status**, never [State]. `activeBusinessOps` changes on every ordinary
     * lease acquisition and release, so a UI collecting the whole state would recompose the settings
     * screen every time any business operation anywhere in the app took a lease — for a value it does
     * not render. `distinctUntilChanged` over the status means a screen recomposes when the verdict
     * actually changes and at no other time.
     *
     * It is read-only (`StateFlow`, not `MutableStateFlow`) and grants nothing: being able to observe
     * the gate is not the same as being able to open it, and nothing here is settable from outside.
     *
     * ### Why it is `Boolean` and not `Status`
     *
     * [Status] is `internal`, and a `public` property cannot expose an `internal` type — so this is
     * `StateFlow<Boolean>`: "a restore currently owns the data", the one question the screen actually
     * asks. The distinction the UI makes is between *restore in progress* and *this backup file is
     * broken*, and both `RESTORING` and the terminal `BLOCKED` are answered by the same user-facing
     * wording; a three-way enum would be public API surface bought with no caller.
     *
     * The mapping is over the *status*, so leases taken and released by ordinary business operations
     * never recompose a screen that renders this.
     */
    val restoreInProgressFlowForUi: StateFlow<Boolean> = _state
        .map { it.status == Status.RESTORING }
        .distinctUntilChanged()
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            started = SharingStarted.Eagerly,
            initialValue = _state.value.status == Status.RESTORING
        )

    /** The current verdict, for callers that only need the value. */
    internal val status: Status get() = _state.value.status

    /** True when business code may read the user's data. */
    val isReady: Boolean get() = status == Status.READY

    /**
     * True when an in-process restore is currently holding ownership.
     *
     * Distinct from "not ready": a caller that wants to explain *why* access was refused has to tell
     * "a restore is running, wait" apart from "recovery failed, restart".
     */
    val isRestoring: Boolean get() = status == Status.RESTORING

    /**
     * True when the gate is blocked **for the rest of the process**.
     *
     * Set only by [markRestoreUnfinished], and the one thing [markReady] refuses to overwrite. Derived
     * from the single [State] rather than stored beside it, so it cannot disagree with [status].
     */
    val isRestoreUnfinished: Boolean get() = _state.value.stickyUntilRestart

    /** How many business operations currently hold a lease. Diagnostics and tests only. */
    internal val activeBusinessOps: Int get() = _state.value.activeBusinessOps

    /** The failure that left the gate not-ready, if any — for diagnostics and for error messages. */
    val blockReason: Throwable? get() = _state.value.error

    /**
     * The gate as an assertion: returns normally when business code may read data, throws otherwise.
     *
     * ### Why the check lives here and not at each call site
     *
     * Every accessor that hands out something reading the user's data has to make the same decision, and
     * a decision copied is a decision that drifts. The subtle failure is not forgetting the check — it is
     * writing a *slightly different* one: reading [isReady] and falling back to an empty list, wrapping
     * the accessor in `runCatching`, or checking only the repository the author happened to be using.
     * Each of those serves something derived from a half-restored data set, and the caller cannot tell.
     *
     * There is exactly one correct behaviour — refuse — so there is one place that expresses it.
     * Accessors call this **before** constructing anything; a repository built and then guarded would
     * already have read `closie/` into memory in its own `init`.
     *
     * Refuses on both `RESTORING` and `BLOCKED`: during a restore the durable surfaces are actively
     * being swapped, so a business read is exactly as unsafe as it is after a failed recovery.
     *
     * ### This is a *check*, not a *barrier*
     *
     * It answers "is the gate open right now?" and nothing more, so it cannot protect an operation that
     * spans time. Use [withBusinessAccess] / [withBusinessAccessSuspending] for that; see the class doc.
     *
     * @throws RestoreRecoveryPendingException carrying [blockReason], so the cause survives to the log.
     */
    fun requireReady() {
        if (!isReady) throw RestoreRecoveryPendingException(blockReason)
    }

    /**
     * Runs [block] while holding a business-operation lease, and releases it afterwards.
     *
     * The **synchronous** form: for a non-suspending durable sequence. A suspend-aware caller must use
     * [withBusinessAccessSuspending] instead, which differs only in how it is written, not in what it
     * guarantees.
     *
     * ### What the lease buys
     *
     * The lease is what makes [beginRestore] a real barrier rather than a race. While it is held,
     * `activeBusinessOps > 0`, and [beginRestore] refuses — so the restore cannot begin swapping the
     * directory this block is halfway through writing. Without it, [requireReady] at the top of the block
     * would only prove the gate was open at the start, which is precisely the window the previous
     * revision lost data in.
     *
     * Leases nest by counting: a repository method may call another repository method, and the inner
     * acquisition simply increments. The outer release is the one that returns the count to zero. Nothing
     * here can deadlock, because the acquisition never waits — it either succeeds by compare-and-set or
     * throws immediately.
     *
     * @throws RestoreRecoveryPendingException when the gate is not `READY`, i.e. the *same* refusal
     *   [requireReady] gives. Acquiring a lease while a restore runs is refused rather than queued: the
     *   caller is business code that has no business running during a restore, and making it wait would
     *   hold a thread (or a coroutine) for an unbounded time.
     */
    internal fun <T> withBusinessAccess(block: () -> T): T {
        acquireBusinessLease()
        try {
            return block()
        } finally {
            releaseBusinessLease()
        }
    }

    /**
     * The suspend-aware form of [withBusinessAccess], for a durable sequence that suspends.
     *
     * ### Why this is not `suspend inline`
     *
     * The lease is a counter, not a mutex, so the release is a plain compare-and-set and the body may
     * suspend freely — which is the whole point, because the operations that need protecting are exactly
     * the ones that suspend on I/O:
     *
     * ```
     *   withBusinessAccessSuspending {
     *       entityDao.insert(entity)        // suspends on disk
     *       relationDao.insert(relation)    // suspends on disk
     *   }                                    // beginRestore() could not start for the whole of it
     * ```
     *
     * It is *not* `inline`, so that the `try`/`finally` cannot be split across a suspend boundary by the
     * compiler in a way that reforms the block without the release. The cost — one lambda allocation per
     * operation — is irrelevant next to a disk write, and it buys a release that cannot be optimised away.
     *
     * The alternative designs are all worse, and deliberately not used:
     *
     *  - **blocking the thread until the restore finishes** would deadlock the restore, which is itself
     *    running on a dispatcher this call might be occupying;
     *  - **holding a `synchronized`/`ReentrantLock` across a suspend point** is forbidden in coroutines —
     *    the lock would be held by a thread that the scheduler is free to reuse, so the release could run
     *    on a different thread than the acquire;
     *  - **a second mutex/flag** for the lease would reintroduce exactly the dual-source drift the single
     *    [State] exists to remove.
     *
     * @throws RestoreRecoveryPendingException as [withBusinessAccess].
     */
    internal suspend fun <T> withBusinessAccessSuspending(block: suspend () -> T): T {
        acquireBusinessLease()
        try {
            return block()
        } finally {
            releaseBusinessLease()
        }
    }

    /**
     * Takes one business lease, or throws if the gate is closed.
     *
     * The compare-and-set loop is what makes the acquisition atomic with respect to [beginRestore]:
     * either this wins and the count becomes non-zero first (so the restore cannot start), or the restore
     * wins and the status is no longer `READY` (so this refuses). There is no interleaving in which both
     * believe they may proceed — which is the entire point of putting the count in the same [State].
     */
    private fun acquireBusinessLease() {
        while (true) {
            val current = _state.value
            if (!current.canAcquireBusinessLease) throw RestoreRecoveryPendingException(current.error)
            val next = current.copy(activeBusinessOps = current.activeBusinessOps + 1)
            if (_state.compareAndSet(current, next)) return
        }
    }

    /**
     * Releases one business lease.
     *
     * Deliberately does **not** touch [State.status]: releasing a lease must never be able to open the
     * gate, only to make it eligible for a restore to close it.
     *
     * ### Why this is fail-fast and not saturating
     *
     * An earlier revision returned quietly when [State.activeBusinessOps] was already zero:
     *
     * ```
     *   private fun releaseBusinessLease() {
     *       while (true) {
     *           val current = _state.value
     *           if (current.activeBusinessOps == 0) return     // <-- silently accepted an underflow
     *           …
     *       }
     *   }
     * ```
     *
     * The reasoning was "saturate at zero so a double release can only under-count, never produce a count
     * that would let `beginRestore` believe an operation is outstanding when none is". That reasoning is
     * backwards. The count is not a heuristic that should absorb abuse — it is the *only* evidence
     * [beginRestore] has that no business operation is in flight. Under-counting is precisely the
     * dangerous direction:
     *
     * ```
     *   op A: acquire   -> activeBusinessOps = 1
     *   op B: acquire   -> activeBusinessOps = 2
     *   op A: release   -> activeBusinessOps = 1
     *   op A: release   -> activeBusinessOps = 0     (a bug: A released twice)
     *   beginRestore(): activeBusinessOps == 0 -> proceeds, while op B is mid-write
     * ```
     *
     * A silent return makes that bug invisible at the exact moment it is most damaging — the count reaches
     * zero while a real operation is unfinished, the restore takes ownership, and the interleaved write is
     * then erased by the snapshot replay. The corrupt state is the *outcome*; the missing signal is what
     * makes it undiagnosable.
     *
     * So an unbalanced release is a programming error and fails immediately, naming the invariant. The
     * acquire/release pair is not advisory — it is what makes [withBusinessAccess] and
     * [withBusinessAccessSuspending] safe, and both hold it in a `finally` that cannot be skipped on the
     * normal path. Only a bug *outside* those helpers (a hand-rolled acquire, a `finally` that runs twice)
     * can reach this, and it should be a crash with a readable message rather than a silent zero.
     *
     * The message names the count and the likely cause, because the stack trace alone does not distinguish
     * "released without acquiring" from "released twice".
     *
     * @throws IllegalStateException when called with no lease outstanding.
     */
    private fun releaseBusinessLease() {
        while (true) {
            val current = _state.value
            // Fail-fast, not saturating: see the doc above. `check` is used rather than `require` because
            // this is an internal invariant on the object's own state, not a precondition on a caller's
            // argument — the distinction matters for what a reader of the stack trace concludes.
            check(current.activeBusinessOps > 0) {
                "RestoreStartupGate: releaseBusinessLease() with no lease outstanding " +
                    "(activeBusinessOps == 0). Acquire and release must be strictly paired — an " +
                    "unbalanced release here makes the count reach zero while a real operation is " +
                    "unfinished, which is what lets beginRestore() take ownership and interleave."
            }
            val next = current.copy(activeBusinessOps = current.activeBusinessOps - 1)
            if (_state.compareAndSet(current, next)) return
        }
    }

    /**
     * Runs [block] only while the gate is READY, and throws otherwise.
     *
     * ### Why this exists: an already-constructed repository is not a safe repository
     *
     * Everything else in this file guards *construction* — [requireReady] at the top of a `by lazy`
     * accessor, or in a repository's `init`. That is enough for a process that resolves the gate before
     * it builds anything, which is the startup case. It is **not** enough for an in-process restore:
     *
     * ```
     *   user opens Settings, starts a restore
     *   -> the ViewModels are still alive, each holding a repository obtained earlier
     *   -> those instances were built while the gate was READY, so their constructor checks passed
     *   -> ...and they keep reading and writing
     * ```
     *
     * A construction-time check cannot retroactively invalidate an object that already exists. The only
     * place that can is the point where the object actually touches data, so that is where the check has
     * to live — and it has to be re-evaluated on *every* touch, not once at construction.
     *
     * This helper is that check, in the smallest form that can be applied mechanically. It is retained for
     * the cases where a per-access assertion is genuinely what is wanted — a DAO getter whose *only*
     * obligation is to refuse when closed, on the way out to a caller that is itself holding a lease. A
     * durable multi-step operation wants [withBusinessAccess] instead, because a getter-level check cannot
     * span the writes that follow it.
     */
    internal fun <T> gated(block: () -> T): T {
        requireReady()
        return block()
    }

    /**
     * Wraps a durable Room `Flow` so that it is subscribed **only while the gate is READY**.
     *
     * ### The hole this closes
     *
     * Gating a repository's DAO accessor is enough for one-shot calls, because each call re-evaluates the
     * gate. It is *not* enough for a `Flow`:
     *
     * ```
     *   ViewModel starts, gate is READY
     *   -> observeAll() checks the gate, returns the Room Flow
     *   -> the collector stays subscribed for the life of the screen
     *   -> user starts a restore; the gate closes
     *   -> the existing subscription still receives every Room invalidation
     *   -> the UI renders the backup's rows, then the rolled-back rows, live
     * ```
     *
     * A `Flow` handed out before the gate closed never calls the accessor again, so no per-access check
     * can reach it. The subscription itself has to be conditional, which means the gate must be
     * *observable* — which is why [state] exists.
     *
     * ### Why the subscription key is the status alone
     *
     * The upstream is [statusFlow] (`status` + `distinctUntilChanged`), **not** the whole [State]. Once
     * the state carries `activeBusinessOps`, observing all of it would cancel and rebuild the durable
     * subscription every time any business operation takes or releases a lease — a churn that is not just
     * wasted work but a stream of needless re-queries, and one that would make a busy screen's data flicker
     * for no reason. Only the status decides whether a subscription should exist, so only the status is
     * observed.
     *
     * ### Why `emptyFlow` rather than throwing
     *
     * The obvious alternative — emit an error and let the collector die — trades a stale UI for a dead
     * one. A restore is *expected* to succeed: after [endRestoreReady] the data is coherent again, and a
     * collector that has already terminated would never see it. The screen would stay blank until the
     * user navigated away and back, which is a worse bug than the one being fixed.
     *
     * `emptyFlow` completes without emitting, and `flatMapLatest` re-subscribes the moment the status
     * returns to `READY`. The collector is untouched throughout: it sees no emissions while the gate is
     * closed, then a fresh coherent snapshot when it reopens. Screen state already rendered stays
     * rendered, and the first emission after resume is a **full read** of the repaired table — not a
     * delta — so no half-restored row can survive in the UI by being "already there".
     *
     * ### Why `flatMapLatest` and not `filter`
     *
     * `filter` would keep `block()` subscribed and merely drop its emissions, which means the restore's
     * intermediate writes would still be *observed* — exactly the reads this is meant to prevent — and a
     * database cursor would be held across the swap. `flatMapLatest` closes the durable subscription for
     * the duration (`block` is not even called), so nothing is queried.
     *
     * `block` is re-invoked on each reopen rather than captured once, because the `Flow` a DAO returns is
     * a cold description: re-creating it is what produces a fresh query against the repaired tables.
     *
     * ### The cancellation race this still closes
     *
     * `flatMapLatest` cancels the old inner flow when the status changes, but that cancellation is not
     * synchronous with the value that triggered it: a Room emission already in flight can be delivered
     * after [beginRestore] has succeeded. So each value is passed through [emitUnderBusinessLease], which
     * refuses to hand it on unless a lease can be taken — see that function.
     */
    internal fun <T> gateAwareFlow(block: () -> Flow<T>): Flow<T> =
        // `flatMapLatest` is still marked experimental in the version this project compiles against. The
        // opt-in is scoped to this one expression rather than the file or the object: the semantics relied
        // on here (`flatMapLatest` cancels the previous inner flow — dropping the durable subscription —
        // and re-invokes the lambda on each new upstream value) are exactly what the surrounding doc
        // argues for, and `filter` cannot express them. A future signature change would be caught by
        // `GateAwareFlowTest`'s subscription counter rather than by a compile error.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        statusFlow.flatMapLatest { s ->
            if (s == Status.READY) emitUnderBusinessLease(block()) else emptyFlow()
        }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Atomically takes restore ownership: `READY -> RESTORING`.
     *
     * This is the *only* way a restore may start, and it is the reason two restores cannot run at once.
     * The compare-and-set is on the same state the verdict lives in, so "am I allowed to start?" and "is
     * business blocked?" cannot answer inconsistently — a separate flag would let one say yes while the
     * other still said READY, which is precisely the window in which two restores could interleave their
     * markers and snapshots.
     *
     * ### Why it also requires `activeBusinessOps == 0`
     *
     * A `READY` status alone would let the restore start *underneath* a business operation that is already
     * past its own gate check and midway through a durable write — the TOCTOU the class doc describes. The
     * count is the second half of the barrier: if any operation holds a lease, ownership is refused, and
     * the caller reports a retryable failure instead of a half-restored data set.
     *
     * Refusal is deliberate, and not a queue: waiting here would block the restore *and* the operation
     * that is waiting for it, and the operation may legitimately take an unbounded time (a large import).
     * A refusal the user can retry is honest and cannot deadlock.
     *
     * Note what it deliberately does **not** check: whether a durable marker is left over from a previous
     * run. Those are different questions — a `COMMITTED` marker with pending cleanup leaves the *data*
     * consistent, so the business gate is legitimately `READY`, while starting a new restore would
     * overwrite the marker that still points at the leftovers. That check belongs next to the marker
     * store, with the other preflight conditions, and lives in [BackupManager.restore].
     *
     * @return true when ownership was granted and the gate is now `RESTORING`; false when someone else
     *   already holds it, a business operation is in flight, or the gate is not `READY` (a blocked process
     *   must never start a restore).
     */
    internal fun beginRestore(): Boolean {
        while (true) {
            val current = _state.value
            if (!current.canBeginRestore) return false
            val next = current.copy(status = Status.RESTORING, error = null)
            if (_state.compareAndSet(current, next)) return true
        }
    }

    /**
     * Releases restore ownership after the restore reached a coherent state: `RESTORING -> READY`.
     *
     * Must be called only once every durable surface **and** the in-memory snapshots agree, because the
     * moment it returns, business readers are allowed back in. In particular the recovery-owned wardrobe
     * reload has to have run first — publishing afterwards would leave a window in which the UI holds a
     * pre-restore in-memory wardrobe while the disk holds the restored one.
     *
     * A no-op when the gate is not `RESTORING`: a `BLOCKED` gate (sticky or from the startup barrier)
     * must not be reopened by a restore finishing, and a stray second call must not be able to publish
     * readiness nobody established.
     *
     * Preserves any in-flight lease count, and — crucially — never clears [State.stickyUntilRestart]: a
     * finished restore cannot un-block a process whose own compensation failed. The compare-and-set
     * re-checks the status, so a `markRestoreUnfinished` racing this call wins rather than being
     * overwritten.
     */
    internal fun endRestoreReady() {
        while (true) {
            val current = _state.value
            if (current.status != Status.RESTORING) return
            val next = current.copy(status = Status.READY, error = null)
            if (_state.compareAndSet(current, next)) return
        }
    }

    /** Records that recovery finished (or that there was nothing to recover). */
    fun markReady() {
        while (true) {
            val current = _state.value
            // A process whose own restore failed to compensate is not reopenable: see
            // markRestoreUnfinished. Silently ignoring the call would be worse than refusing it — the
            // caller would believe data is safe to read when nothing has established that — so this
            // returns without changing anything, and the status stays BLOCKED for anyone who asks.
            if (current.stickyUntilRestart) return
            val next = current.copy(status = Status.READY, error = null)
            if (_state.compareAndSet(current, next)) return
        }
    }

    /**
     * Records that recovery could not finish. The marker, the parked trees and the snapshot are all
     * still on disk, so the next process start will retry.
     *
     * Reopenable by design: this is the startup barrier's verdict, and a later successful pass in the
     * same process is a legitimate way to clear it. That is the difference from
     * [markRestoreUnfinished], which sets [State.stickyUntilRestart] and cannot be cleared in-process.
     */
    fun markBlocked(error: Throwable?) {
        while (true) {
            val current = _state.value
            if (current.stickyUntilRestart) return
            val next = current.copy(status = Status.BLOCKED, error = error)
            if (_state.compareAndSet(current, next)) return
        }
    }

    /**
     * Records that an **in-process** restore could not be compensated: the durable surfaces may now
     * disagree, the marker/snapshot/parked evidence is deliberately still on disk, and no code in this
     * process can prove the data is consistent again.
     *
     * ### What this commits to
     *
     *  1. **The evidence stays.** Callers must not delete the marker, the snapshot or the parked trees;
     *     they are what the next process start repairs from. (They are also the reason the compensation
     *     path writes its terminal state *before* cleaning up.)
     *  2. **This process stops doing business.** From here, every business read and write refuses —
     *     including through repository instances constructed earlier, via [gated] and the lease helpers,
     *     and including long-lived `Flow` subscriptions, via [gateAwareFlow].
     *  3. **The next start goes through the barrier.** [RestoreRecoveryManager.recoverOnStartup] runs in
     *     `Application.onCreate` of the new process, finds the surviving marker, and finishes the job.
     *
     * ### Why it cannot be undone in-process
     *
     * Reopening would require proving that the Closet, the media and the database all agree again. The
     * only code that can do that is the startup barrier, and running it here is not a shortcut but a
     * different operation: it would re-read a marker the failed compensation deliberately left untouched,
     * over state that is mid-restore. So the honest answer is a one-way door whose exit is a fresh process
     * — expressed here as a status the gate will not leave.
     *
     * ### Why the status and the sticky flag move together
     *
     * Both are fields of one [State] written by one compare-and-set, so there is no instant at which a
     * reader can see `BLOCKED` with the flag still clear (which would look reopenable and could be cleared
     * by a stray [markReady]), or `READY` with the flag set (which is the drift the previous
     * `@Volatile Boolean` allowed).
     *
     * @param error the compensation failure, kept so [requireReady] can name it. Must be non-null: a
     *   block with no cause is a block nobody can diagnose.
     */
    fun markRestoreUnfinished(error: Throwable) {
        while (true) {
            val current = _state.value
            val next = current.copy(
                status = Status.BLOCKED,
                error = error,
                stickyUntilRestart = true
            )
            if (_state.compareAndSet(current, next)) return
        }
    }

    /** For tests: puts the gate back to its initial, unanswered state. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        // One write, one place: there is no second flag to forget, which is exactly how the previous
        // revision's tests could pass while leaving a stale sticky boolean behind.
        _state.value = State(Status.BLOCKED)
    }

    // ------------------------------------------------------------------
    //  Flow emission guard
    // ------------------------------------------------------------------

    /**
     * Passes each value of [upstream] on only while a business lease can be taken; drops the rest.
     *
     * ### The race this closes
     *
     * `flatMapLatest` cancels the previous inner flow when the status changes, but the cancellation is
     * **not** synchronous with the change: a Room emission already in flight can be delivered to the outer
     * collector after [beginRestore] has already succeeded. The gate would then be `RESTORING` while the UI
     * still received a row from the old subscription — a half-restored value reaching the screen, which is
     * the exact outcome `gateAwareFlow` exists to prevent.
     *
     * Taking a lease around each emission closes it from both sides:
     *
     *  - if the status is already `RESTORING`/`BLOCKED`, the lease cannot be taken and the value is
     *    **dropped** — not thrown, because an error would kill the collector permanently ([emptyFlow]'s
     *    reasoning);
     *  - if the lease *is* taken, then [beginRestore] sees `activeBusinessOps > 0` and cannot succeed
     *    until the lease is released, so the value cannot be overtaken *while it is being handed over*.
     *
     * ### What the lease does **not** cover, corrected
     *
     * This doc used to claim the lease "never spans a suspension the collector performs downstream" and
     * "is released as soon as the value is emitted". That is false about `Flow`, and stating it as a
     * guarantee invites code to rely on something that is not there. What actually happens:
     *
     * ```
     *   emit(value)               <- a suspending call; it hands the value to the downstream collector
     *     downstream.collect {    <- runs *inside* the emit, on the collector's dispatcher
     *         doWork()            <- can suspend for an unbounded time
     *     }
     *   releaseBusinessLease()    <- only reached once the collector returned
     * ```
     *
     * `Flow.emit` is a suspending function and back-pressure is real: the value is not "handed off" and
     * forgotten, it is delivered and the collector runs before `emit` returns. So a slow collector —
     * a Room write, a network call, a `delay` in `onEach` — holds this emission lease for as long as it
     * takes, and during that whole time [beginRestore] returns `false`. A UI that collects a repository
     * flow and does slow work per emission can therefore postpone a restore indefinitely.
     *
     * That is a real, bounded trade-off rather than a defect to hide, and the options were: claim the
     * guarantee (wrong), redesign the guard, or accept the behaviour explicitly. The redesigns all fail on
     * a common point — the value must be delivered *while* the gate is known to be open, and any
     * `emit`-based delivery is exactly as long as the collector makes it. Buffering the value and emitting
     * outside the lease reintroduces the very race this guard exists to close (the buffered value would be
     * delivered after `beginRestore` succeeded). So the behaviour is accepted, bounded, and documented here:
     *
     *  - the lease is per **emission**, not per subscription, so it is held only while a value is being
     *    delivered — an idle collector holds nothing;
     *  - `beginRestore` returning `false` is a *refusal*, never a block or an exception, so a postponed
     *    restore fails visibly ("已有恢复正在进行") rather than deadlocking, and the user can retry;
     *  - `app`'s collectors are `collect { rows -> uiState = rows }`-shaped, i.e. they assign and return, so
     *    in practice the lease is held for the duration of a list assignment.
     *
     * What remains guaranteed, and is what the tests below pin: a value is never delivered after the status
     * has left `READY`, and no value is ever delivered *twice* or lost by the retry.
     *
     * Note this is deliberately *not* a second state: it reads the same [State] and uses the same lease
     * mechanism as every other business operation.
     */
    private fun <T> emitUnderBusinessLease(upstream: Flow<T>): Flow<T> =
        kotlinx.coroutines.flow.flow {
            upstream.collect { value ->
                // Retry the compare-and-set rather than giving up on the first failure: a failed CAS only
                // means the state changed underneath (another lease taken or released), not that the gate
                // is closed. Only a status that is no longer READY decides to drop the value.
                if (!tryAcquireEmissionLease()) return@collect
                try {
                    // Suspends until the downstream collector has processed the value — see the note above
                    // on what that means for how long this lease is held. The `finally` guarantees it is
                    // released even if the collector throws or is cancelled.
                    emit(value)
                } finally {
                    releaseBusinessLease()
                }
            }
        }

    /**
     * Takes a lease for one flow emission, or reports that the gate has closed.
     *
     * Retries the compare-and-set so that contention with an ordinary business operation — which changes
     * [State.activeBusinessOps] without changing the status — does not cause a spurious drop. Returns
     * false only when the status itself has left `READY`, which is the one condition that means "this
     * value must not reach the UI".
     */
    private fun tryAcquireEmissionLease(): Boolean {
        while (true) {
            val current = _state.value
            if (!current.canAcquireBusinessLease) return false
            val next = current.copy(activeBusinessOps = current.activeBusinessOps + 1)
            if (_state.compareAndSet(current, next)) return true
        }
    }
}

/**
 * File-scope entry point to the gate-aware `Flow` policy, so repositories can write the obvious
 * `import com.qq.closie.data.backup.gateAwareFlow` instead of naming
 * `RestoreStartupGate.Companion.gateAwareFlow`.
 *
 * There is no second implementation and no second state: this forwards to the single canonical
 * [RestoreStartupGate.state], so a status is still stored in exactly one place.
 */
internal fun <T> gateAwareFlow(block: () -> Flow<T>): Flow<T> = RestoreStartupGate.gateAwareFlow(block)
