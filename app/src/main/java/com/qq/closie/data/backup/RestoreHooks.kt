package com.qq.closie.data.backup

/**
 * Fault-injection seam for the restore pipeline.
 *
 * The restore commit boundary spans three resources that cannot share a transaction — a Room database
 * and two directory trees — so its correctness is a property of what happens *between* the commits, not
 * of any single call. That makes it the one part of the codebase that cannot be verified by testing the
 * happy path: the bugs live in the failure windows, and those windows are microseconds wide on a healthy
 * filesystem.
 *
 * Every hook below is called at a point where the restore has just changed durable state, which is
 * exactly where a regression test needs to stand. Production passes [NoOpRestoreHooks], so the hooks
 * compile to empty inline calls and cost nothing; tests pass an implementation that throws at the one
 * stage under test.
 *
 * This is deliberately *not* a `testMode` flag. A flag would have to be threaded through the restore
 * logic and branched on inside it, which means the code path under test is not the code path that
 * ships — the classic way a passing test proves nothing. Hooks invert that: the shipping code path is
 * byte-identical, and only the *injected observer* differs.
 *
 * ### Order
 *
 * The hooks are declared in call order. A restore walks them exactly once:
 * ```
 * beforeClosetSwap -> [closie -> oldDir, stage -> closie] -> afterClosetSwap
 *   -> beforeMediaSwap -> [media -> oldDir, stage -> media] -> afterMediaSwap
 *   -> beforeDbCommit -> [Room transaction] -> afterDbCommit
 *   -> beforeHealthCheck -> [health check] -> afterHealthCheck
 * ```
 *
 * The database commit is intentionally **last** and the two directory swaps are in front of it: each
 * swap has a self-describing undo (the parked `oldDir`), so ordering the irreversible-ish directory
 * publication before the reversible database transaction is what keeps the end states decidable.
 */
interface RestoreHooks {
    /** Called after everything is staged and validated, immediately before the Closet swap. */
    fun beforeClosetSwap() {}

    /** Called once the staged Closet is live and the old tree is parked as `closetOldDir`. */
    fun afterClosetSwap() {}

    /** Called immediately before the Life media directory swap. */
    fun beforeMediaSwap() {}

    /** Called once the staged Life media is live and the old tree is parked as `mediaOldDir`. */
    fun afterMediaSwap() {}

    /** Called before the Life OS database transaction opens. */
    fun beforeDbCommit() {}

    /** Called after the database transaction has committed. */
    fun afterDbCommit() {}

    /** Called immediately before the post-swap health check runs. */
    fun beforeHealthCheck() {}

    /** Called after the health check has passed, before the intent marker is cleared. */
    fun afterHealthCheck() {}
}

/** The production implementation: every hook is a no-op, so the restore path is unobstructed. */
object NoOpRestoreHooks : RestoreHooks
