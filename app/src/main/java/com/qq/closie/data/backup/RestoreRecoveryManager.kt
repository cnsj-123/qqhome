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
    fun recoverOnStartup(context: Context, lifeDatabase: () -> LifeDatabase?): RecoveryOutcome {
        // No marker means there is nothing to recover and no reason to open the database. This is the
        // ordinary path and it must stay cheap.
        when (RestoreIntentStore.read(context)) {
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
            is AtomicJson.ReadResult.Success -> Unit
        }

        val outcome = runCatching {
            // The one synchronous point. `runBlocking` is used *only* on this rare, already-abnormal
            // path, and only because the barrier must be complete before `onCreate` returns. The
            // ordinary launch never reaches here.
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                BackupManager.recoverInterruptedRestore(context, lifeDatabase())
            }
        }.getOrElse { throwable ->
            // Recovery itself failed to even run (for example the database could not be opened). That
            // is a blocked gate, not a crash.
            RecoveryOutcome.RetryRequired(throwable)
        }

        when (outcome) {
            is RecoveryOutcome.Completed, is RecoveryOutcome.NoWork -> RestoreStartupGate.markReady()
            is RecoveryOutcome.RetryRequired -> RestoreStartupGate.markBlocked(outcome.error)
        }
        return outcome
    }
}
