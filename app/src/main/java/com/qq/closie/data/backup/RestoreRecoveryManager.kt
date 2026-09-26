package com.qq.closie.data.backup

import android.content.Context
import com.qq.closie.life.data.database.LifeDatabase

/**
 * The startup recovery **barrier**: finishes an interrupted restore before anything may read data.
 *
 * ### Why this is not on an Activity
 *
 * Recovery used to be wired into `MainActivity.onCreate`. That is the wrong owner, because
 * `MainActivity` is not the only way this process starts: `QuickCaptureActivity`,
 * `CapturePreviewActivity` and `QuickCaptureService` are separate entry points into the same
 * `ClosieApplication`. A restore interrupted before one of those starts would not be recovered until
 * the user happened to open the main screen — and until then the app would be reading a Closet that is
 * [RestoreState.CLOSET_SWAPPED] away from the database it is being paired with.
 *
 * So recovery is driven from [com.qq.closie.ClosieApplication.onCreate], which every entry point
 * shares.
 *
 * ### One pass, not two — and it is a barrier
 *
 * An earlier revision split this into a synchronous filesystem pass and an asynchronous
 * database-aware pass. **That was unsound and has been removed.** The async pass returned control to
 * `Application.onCreate` while the database half was still outstanding, which left a real window in
 * which the Closet and the media were on the user's version while the database still held the
 * backup's rows — and the UI, already constructed, could read that mixture. Worse, if the user wrote
 * anything in that window, the snapshot replay could land afterwards and overwrite it.
 *
 * The rule now is a barrier: **if a marker exists, recovery runs to completion — filesystem *and*
 * database — before any repository is allowed to serve data.**
 *
 * ### Cost is paid only when there is something to recover
 *
 * The marker is checked first. An ordinary launch (no marker) does not open the database at all and
 * does not block: the gate is marked ready and startup proceeds exactly as before. Opening Room on
 * every start just to discover there is nothing to do would tax every user for a rare one's recovery.
 *
 * When a marker *is* present, the cost is deliberately accepted. A pending restore is abnormal, and a
 * launch that is slow once is strictly better than business code running against three surfaces that
 * disagree.
 */
internal object RestoreRecoveryManager {

    /**
     * Runs recovery to completion if a restore was interrupted, and resolves [RestoreStartupGate].
     *
     * Must be called from `Application.onCreate` **before** any repository is created. Blocks the
     * calling thread only when there is a marker; a normal launch returns immediately.
     *
     * Never throws — an exception escaping here would take down every entry point of the app. Failure
     * is expressed as a blocked gate, which is the safe direction: the data stays untouched and
     * unusable until a later start repairs it.
     *
     * @param lifeDatabase opens the live database. Evaluated **only** when a marker exists, and only
     *   from inside the recovery path — which is what keeps `Room.databaseBuilder(...).build()` off
     *   the ordinary launch path. Recovery must run on a background dispatcher rather than the calling
     *   (main) thread because replaying a snapshot runs `database.withTransaction` and the production
     *   database is built without `allowMainThreadQueries()`.
     * @return the outcome, for tests and callers that want to observe the verdict.
     */
    fun recoverOnStartup(context: Context, lifeDatabase: () -> LifeDatabase?): RecoveryOutcome =
        recoverOnStartup(context, lifeDatabase, RealRestoreFs)

    /**
     * [recoverOnStartup] with an explicit [RestoreFs].
     *
     * `internal` because [RestoreFs] is a test seam, not production API — the same reasoning as
     * [BackupManager.recoverInterruptedRestore]. It exists so a test can make one *named* filesystem
     * operation fail and observe what the manager does with the failure, which is how the cleanup-failure
     * branches below are reachable without a read-only filesystem.
     *
     * ### Orchestration: decide *which* recovery is needed before running one
     *
     * The marker is read first and its one substantive question — [RestoreIntent.requiresDatabaseRecovery]
     * — decides the route. There are four cases and they are deliberately not collapsed:
     *
     * | marker                     | route                                                        |
     * |----------------------------|--------------------------------------------------------------|
     * | no marker                  | mark ready, return; the database is never opened              |
     * | corrupt                    | stay blocked; do not guess                                     |
     * | no DB work needed          | filesystem-only; the database is never opened                 |
     * | DB work needed             | obtain the provider, then **full** recovery                    |
     *
     * ### Why the database case must not run a filesystem pass first
     *
     * The previous revision ran `recoverFilesystemOnly` unconditionally and only then, if the marker still
     * wanted a database, obtained the provider and ran the full recovery. That looks harmless — the full
     * recovery reverts the filesystem again — and it is not:
     *
     * ```
     *   database available, snapshot corrupt
     *   -> filesystem pass reverts the Closet and the media   (parked trees CONSUMED)
     *   -> full recovery reads the snapshot -> corrupt -> RetryRequired
     *   -> the marker survives, but the parked trees it named are already gone
     * ```
     *
     * The gate does end up blocked, so no inconsistent data is served — but the *one* thing the full
     * recovery's ordering exists to guarantee has been thrown away: that a snapshot which cannot be
     * replayed leaves the filesystem evidence intact. The window in which the Closet is on the user's
     * version while the database is still on the backup's becomes unbounded, because the trees that would
     * have finished the rollback no longer exist.
     *
     * So when the marker needs the database, the full recovery runs **alone**, first, in its own strict
     * order (validate snapshot → replay → revert → terminal → cleanup). The filesystem pass is not a
     * cheaper prologue to it; it is a different route, taken only when the database is genuinely not
     * obtainable.
     *
     * ### And when the database cannot be obtained, the filesystem repair still happens
     *
     * Both failure shapes — the provider returns `null`, and the provider *throws* — fall back to
     * [RestoreCoordinator.recoverFilesystemOnly]. That repairs the Closet and the media, which needs no
     * database, while preserving the marker and the snapshot for a later start. The throwing case is
     * caught rather than propagated: an exception escaping here would take down `Application.onCreate`
     * and every entry point with it, and skipping the repair would defer work that was safe to do.
     *
     * Everything here is **synchronous** by construction. `runBlocking` is used only on this already
     * abnormal path, and only because the barrier must be complete before `onCreate` returns. There is
     * deliberately no `launch`, no `GlobalScope`, no `async` and no fire-and-forget: any of those would
     * hand control back to `onCreate` with the database half outstanding, which is the exact regression
     * this class exists to prevent.
     */
    internal fun recoverOnStartup(
        context: Context,
        lifeDatabase: () -> LifeDatabase?,
        fs: RestoreFs
    ): RecoveryOutcome {
        // ---- Read the marker once, and let it choose the route. -------------------------------------
        val intent = when (val read = RestoreIntentStore.read(context)) {
            // The ordinary path: nothing to recover, nothing to open, and it must stay cheap.
            is AtomicJson.ReadResult.Missing -> {
                RestoreStartupGate.markReady()
                return RecoveryOutcome.NoWork
            }
            // A marker we cannot read is *not* "no marker": it is the user's only record of an
            // interrupted restore, so it stays on disk and the gate stays blocked.
            is AtomicJson.ReadResult.Corrupt -> {
                val error = IllegalStateException("恢复标记损坏，无法安全恢复；已保留证据等待人工处理")
                RestoreStartupGate.markBlocked(error)
                return RecoveryOutcome.RetryRequired(error)
            }
            is AtomicJson.ReadResult.Success -> read.value
        }

        // ---- Route 1: no database work. The database is never opened on this path. ------------------
        if (!intent.requiresDatabaseRecovery()) {
            return conclude(context, RestoreCoordinator.recoverFilesystemOnly(context, fs))
        }

        // ---- Route 2: database work is needed, so try to obtain the provider *before* any mutation. --
        //
        // Obtained here — not inside the filesystem pass — precisely so that a provider failure cannot
        // consume the parked trees. Note this is the only call site of the lambda: "did recovery open the
        // database?" has exactly one answer.
        val database: LifeDatabase? = try {
            lifeDatabase()
        } catch (throwable: Throwable) {
            // The database could not be opened. Its half genuinely cannot be done now, but the filesystem
            // repair still can, and it needs no database — so do it, keep the evidence, and report a
            // retry. Swallowing here is required: this runs inside `Application.onCreate`, and an escape
            // would take down every entry point of the app.
            val fallback = RestoreCoordinator.recoverFilesystemOnly(context, fs)
            val error = IllegalStateException(
                "Life OS 数据库无法打开，已先修复文件系统部分并保留恢复标记；下次启动将继续恢复",
                throwable
            )
            // The verdict describes what is on disk: the fallback's own failure when it had one, or the
            // provider failure when the filesystem half was fine and only the database was unavailable.
            val verdict = if (fallback is RecoveryOutcome.RetryRequired) fallback
            else RecoveryOutcome.RetryRequired(error)
            return conclude(context, verdict)
        }

        if (database == null) {
            // Same shape as the throwing case: no database, so only the filesystem half can be repaired.
            val fallback = RestoreCoordinator.recoverFilesystemOnly(context, fs)
            val error = IllegalStateException(
                "Life OS 数据库不可用，已先修复文件系统部分并保留恢复标记；下次启动将继续恢复"
            )
            val verdict = if (fallback is RecoveryOutcome.RetryRequired) fallback
            else RecoveryOutcome.RetryRequired(error)
            return conclude(context, verdict)
        }

        // ---- Full recovery: validate snapshot -> replay -> revert -> terminal -> cleanup. ------------
        val outcome = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                BackupManager.recoverInterruptedRestore(context, database, fs)
            }
        } catch (throwable: Throwable) {
            RecoveryOutcome.RetryRequired(throwable)
        }
        return conclude(context, outcome)
    }

    /**
     * Turns a completed [RestoreCoordinator] outcome into a gate decision, and is the one place that
     * decides whether a `RetryRequired` blocks the app.
     *
     * ### Why `RetryRequired` does **not** always mean "blocked"
     *
     * The gate exists to stop business code reading **inconsistent** data. It is not a requirement that
     * every scrap of leftover litter be deleted before the app may be used, and treating it as one
     * produces an unrecoverable app:
     *
     * ```
     *   marker = COMMITTED (data is official and consistent)
     *   -> cleanupChecked fails: an old parked directory cannot be deleted (permissions, open handle)
     *   -> RetryRequired
     *   -> gate BLOCKED
     *   -> the wardrobe repository and every Life OS repository refuse
     *   -> the app is unusable, indefinitely, because a stale directory is undeletable
     * ```
     *
     * That inverts the gate's purpose. So the question asked here is not "did recovery return
     * `RetryRequired`?" but "**is the durable data consistent?**", and that is exactly what the marker's
     * state answers:
     *
     * | marker state                          | durable data              | gate    |
     * |---------------------------------------|---------------------------|---------|
     * | `COMMITTED`                           | the backup's, consistent  | READY   |
     * | `ROLLED_BACK`                         | the user's, consistent    | READY   |
     * | `FAILED`                              | never published           | READY   |
     * | `DB_COMMITTING`/`DB_COMMITTED`/`HEALTH_CHECKING`/`MEDIA_SWAPPED`/`CLOSET_SWAPPED`/`STAGED` | may be split | BLOCKED |
     *
     * The terminal states are terminal for this reason: their definition is "the durable surfaces agree".
     * A failure there can only be cleanup, and cleanup is always safe to defer — the marker is kept, so
     * the next start retries it. A non-terminal state means a surface may still disagree, and that is the
     * only thing worth blocking on.
     *
     * The outcome is still returned as `RetryRequired` in either case, so [com.qq.closie.ClosieApplication]
     * can log it. The verdict and the gate decision are deliberately separate: one describes the
     * protocol, the other describes whether reading data is safe.
     */
    private fun conclude(context: Context, outcome: RecoveryOutcome): RecoveryOutcome {
        when (outcome) {
            is RecoveryOutcome.Completed, is RecoveryOutcome.NoWork -> RestoreStartupGate.markReady()
            is RecoveryOutcome.RetryRequired -> {
                // Re-read the marker: the recovery pass may have advanced it to a terminal state before it
                // failed (this is the whole point of persisting the terminal state before cleanup). What
                // matters for the gate is the state *now*, not the state recovery started from.
                val current = when (val read = RestoreIntentStore.read(context)) {
                    is AtomicJson.ReadResult.Success -> read.value.state
                    // Gone means the clear succeeded after all, or the marker was never re-written; the
                    // outcome already carries the retry detail, so leave the verdict alone.
                    else -> null
                }
                if (current != null && isConsistentTerminalState(current)) {
                    // Cleanup pending only. The marker stays so the next start finishes the tidying, but
                    // nothing may read a mixture — so the app is allowed to run.
                    RestoreStartupGate.markReady()
                } else {
                    RestoreStartupGate.markBlocked(outcome.error)
                }
            }
        }
        return outcome
    }

    /**
     * True when a marker's state means "every durable surface agrees, only cleanup is left".
     *
     * These are the states [RestoreCoordinator.terminalStateAfterRollback] produces or preserves, plus
     * [RestoreState.COMMITTED] — which is the successful restore's own terminal state. A marker in one of
     * them may still have leftovers on disk (which is why recovery can fail there), but the *data* the
     * gate protects is consistent.
     */
    private fun isConsistentTerminalState(state: RestoreState): Boolean = when (state) {
        RestoreState.COMMITTED,
        RestoreState.ROLLED_BACK,
        RestoreState.FAILED -> true
        else -> false
    }
}
