package com.qq.closie.data.backup

import android.content.Context
import java.io.File

/**
 * The furthest point a restore durably reached. The ordering below is the ordering the restore
 * actually performs, and that ordering is what makes recovery decidable after a process kill.
 *
 * Two independent filesystem surfaces are managed: the Closet (`closie/`) and Life OS media
 * (`media/`). They are published in two separate directory swaps, so the single swapped state of
 * the previous design is split into [CLOSET_SWAPPED] and [MEDIA_SWAPPED] — recovery must know exactly
 * which of the two (if either) is currently live.
 *
 * Normal v2 path:
 *   PREPARING → STAGED → CLOSET_SWAPPED → MEDIA_SWAPPED → DB_COMMITTING → DB_COMMITTED →
 *   HEALTH_CHECKING → COMMITTED
 *
 * v1 path (no Life OS section — never touches the database or Life media):
 *   PREPARING → STAGED → CLOSET_SWAPPED → HEALTH_CHECKING → COMMITTED
 *
 * ### Why this enum carries no "does the database need repair?" answer
 *
 * It used to. But [HEALTH_CHECKING] is reached by *both* formats, and it means something different in
 * each: for v2 the database transaction has already run and may hold the backup's rows, while for v1
 * there is no Life OS section at all — no rows were written, no snapshot exists, and touching the
 * database (or the media directory) would corrupt data the archive never mentioned. A state alone
 * cannot tell those apart, so the predicate lives on [RestoreIntent], which knows `includesLifeOs`.
 */
enum class RestoreState {
    /** Marker written; archive being unpacked and staged. Nothing durable has changed. */
    PREPARING,

    /** Archive unpacked, closet staged, Life media staged and fully validated. Nothing durable changed. */
    STAGED,

    /** `stage` is live as `closie`; the previous tree is parked as `closetOldDir`. Database still old. */
    CLOSET_SWAPPED,

    /** Life media `stage` is live as `media`; the previous tree is parked as `mediaOldDir`. */
    MEDIA_SWAPPED,

    /** Pre-restore rows saved to disk; the Life OS transaction may or may not have committed. */
    DB_COMMITTING,

    /** The Life OS transaction returned successfully. */
    DB_COMMITTED,

    /** The post-swap health check is running. */
    HEALTH_CHECKING,

    /** Restore finished and cleanup completed. The marker is cleared immediately after. */
    COMMITTED,

    /** Failed before any durable mutation (no swap, no DB write). Safe to clear. */
    FAILED,

    /** Every durable half was reverted. Only leftover cleanup remains. */
    ROLLED_BACK
}

/**
 * The result of one recovery attempt.
 *
 * Recovery must be explicit about whether it finished, found nothing to do, or must be retried.
 * [RetryRequired] is what drives the startup gate to stay blocked: a recovery that could not finish
 * must not be mistaken for one that did, or the app would serve a half-restored data set.
 */
sealed interface RecoveryOutcome {
    /** Nothing pending (no marker at all). */
    object NoWork : RecoveryOutcome

    /** A pending restore was fully resolved (reverted or finished and cleaned up). */
    object Completed : RecoveryOutcome

    /** Recovery could not finish and must be retried on a later start. The marker is preserved. */
    class RetryRequired(val error: Throwable) : RecoveryOutcome
}

/**
 * The on-disk record of an in-flight restore.
 *
 * Every piece of state recovery needs to finish the job after a process kill is recorded here:
 *  - the paths of the staged and parked directories, so recovery can locate them by explicit path
 *    (never by scanning for "the newest" directory — there must be exactly one active restore);
 *  - [closetExistedBefore] / [mediaExistedBefore], because "roll back to a directory that never
 *    existed" means *deleting* the swapped-in tree, not renaming something back, and recovery must
 *    not guess that from whether an old directory happens to be present.
 *
 * The marker is written through [AtomicJson] (tmp-file commit), so a torn write is impossible: the
 * file on disk is either the previous value or the new one, never a half-record.
 */
data class RestoreIntent(
    val id: Long = 0L,
    val state: RestoreState = RestoreState.PREPARING,
    /** Whether the archive carried a Life OS section (v2). Drives the media swap and DB apply. */
    val includesLifeOs: Boolean = false,
    val closetStageDir: String? = null,
    val closetOldDir: String? = null,
    val mediaStageDir: String? = null,
    val mediaOldDir: String? = null,
    /** Path of the typed pre-restore Life OS snapshot, written just before the transaction. */
    val dbSnapshot: String? = null,
    val closetExistedBefore: Boolean = false,
    val mediaExistedBefore: Boolean = false
)

/**
 * True when the Life OS database may hold the *backup's* rows and must be replayed from the snapshot.
 *
 * Both halves of the condition are load-bearing:
 *
 *  - the three states are exactly those reachable only *after* the transaction was opened, so they are
 *    the only ones where the rows can have moved;
 *  - [RestoreIntent.includesLifeOs] is what keeps a **v1** restore out of the database entirely. A v1
 *    archive has no Life OS section, so a process killed during its health check must not send recovery
 *    looking for a snapshot that was never written, and must not let it touch `filesDir/media`.
 */
fun RestoreIntent.requiresDatabaseRecovery(): Boolean =
    includesLifeOs && (state == RestoreState.DB_COMMITTING ||
        state == RestoreState.DB_COMMITTED ||
        state == RestoreState.HEALTH_CHECKING)

/**
 * Whether this marker's state is one where a directory swap may already have been performed.
 *
 * [RestoreState.PREPARING] is the only state that is provably *before* any rename: the marker is written
 * at PREPARING and at STAGED, and both renames happen after STAGED. So a PREPARING marker can never
 * justify deleting a live directory — only an actual swap can. Everything from STAGED onwards is in
 * scope, including [RestoreState.COMMITTED], which is exactly why the "delete a live directory that had
 * no original" branch must be gated on this rather than on `state == CLOSET_SWAPPED`.
 *
 * It lives here, next to [requiresDatabaseRecovery], because both are the same kind of thing: a decision
 * derived from the durable marker. Recovery reads both, and keeping them side by side is what stops a
 * second, subtly different copy from growing at the call site.
 */
internal val RestoreIntent.swapInScope: Boolean
    get() = state != RestoreState.PREPARING

internal object RestoreIntentStore {

    const val MARKER_NAME = ".life_restore_intent.json"

    fun markerFile(context: Context): File = File(context.filesDir, MARKER_NAME)

    /**
     * Reads the marker, preserving the difference between "absent" and "unreadable".
     *
     * Recovery **must** branch on this rather than on a nullable value: a corrupt marker returned as
     * `null` would be indistinguishable from a clean slate, and the next thing recovery does with a
     * clean slate is delete the parked trees and the snapshot — destroying the user's only copy of the
     * data the interrupted restore was in the middle of moving.
     */
    fun read(context: Context): AtomicJson.ReadResult<RestoreIntent> =
        AtomicJson.read(markerFile(context))

    fun write(context: Context, intent: RestoreIntent) = AtomicJson.write(markerFile(context), intent)

    fun clear(context: Context) = AtomicJson.clear(markerFile(context))
}
