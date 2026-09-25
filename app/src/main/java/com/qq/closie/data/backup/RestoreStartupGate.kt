package com.qq.closie.data.backup

import java.util.concurrent.atomic.AtomicReference

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
 * It is deliberately tiny. It holds one value and does nothing else — no restore logic, no lifecycle,
 * no coroutines, no dependency injection. The restore itself stays in [RestoreCoordinator] and
 * [BackupManager]; the gate only records the *verdict*. Adding orchestration here would recreate the
 * multi-layer manager this replaced.
 *
 * `BLOCKED` is the initial value on purpose: "unanswered" must never read as "safe". A process that
 * never runs recovery (an isolated unit test constructing a repository directly) stays blocked until
 * something explicitly resolves it, which is the honest default.
 */
internal object RestoreStartupGate {

    private data class State(val status: Status, val error: Throwable? = null)

    private val state = AtomicReference(State(Status.BLOCKED))

    /** Whether business code may read the user's data. */
    internal enum class Status { READY, BLOCKED }

    /** True when repositories may serve data. */
    val isReady: Boolean get() = state.get().status == Status.READY

    /** The failure that left the gate blocked, if any — for diagnostics and for error messages. */
    val blockReason: Throwable? get() = state.get().error

    /** Records that recovery finished (or that there was nothing to recover). */
    fun markReady() {
        state.set(State(Status.READY, null))
    }

    /**
     * Records that recovery could not finish. The marker, the parked trees and the snapshot are all
     * still on disk, so the next process start will retry.
     */
    fun markBlocked(error: Throwable?) {
        state.set(State(Status.BLOCKED, error))
    }

    /** For tests: puts the gate back to its initial, unanswered state. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        state.set(State(Status.BLOCKED, null))
    }
}
