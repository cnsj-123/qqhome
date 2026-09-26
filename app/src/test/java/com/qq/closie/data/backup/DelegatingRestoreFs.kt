package com.qq.closie.data.backup

import java.io.File

/**
 * A [RestoreFs] that forwards every operation to [RealRestoreFs] unchanged, for tests that want to
 * override exactly one member by delegation.
 *
 * ### Why delegation rather than a second hand-written fake
 *
 * `FailingRestoreFs` already exists and is *deliberately* a full re-implementation, because its job is
 * to fail one named operation and otherwise mirror production exactly — including
 * [RealRestoreFs.cleanupChecked] field for field. Writing a third full copy for the preflight test would
 * create a third place for those field lists to drift.
 *
 * Kotlin's interface delegation gives the honest middle ground: this class forwards everything, so a test
 * can override the single member it is about and inherit production behaviour for the rest by
 * construction. That is strictly safer than a copy, because a member added to [RestoreFs] later is
 * forwarded here without anyone remembering to update it — whereas a hand-written fake would silently
 * stop compiling, or worse, keep compiling with a default that diverges.
 *
 * Used by [RestoreCoordinatorTest.staleParkingSlot_refusesAsFailureWithoutStickyBlockingOrTouchingAnything],
 * where the refusal must happen in the *real* preflight branch of `RestoreCoordinator.restore()` — the
 * point of that test is the gate handling around the refusal, not the predicate — so everything except
 * [RestoreFs.isSlotOccupied] has to behave exactly as production does, and `park`/`rename`/`deleteTree`
 * must not be reached at all.
 *
 * `open` so a test can override a single member inline; the `by RealRestoreFs` clause supplies the rest.
 */
internal open class DelegatingRestoreFs : RestoreFs by RealRestoreFs {

    override fun park(live: File, old: File, existedBefore: Boolean) =
        RealRestoreFs.park(live, old, existedBefore)

    override fun rename(from: File, to: File, message: String) =
        RealRestoreFs.rename(from, to, message)

    override fun deleteTree(dir: File) = RealRestoreFs.deleteTree(dir)

    override fun isSlotOccupied(slot: File): Boolean = RealRestoreFs.isSlotOccupied(slot)

    override fun cleanupChecked(intent: RestoreIntent) = RealRestoreFs.cleanupChecked(intent)
}
