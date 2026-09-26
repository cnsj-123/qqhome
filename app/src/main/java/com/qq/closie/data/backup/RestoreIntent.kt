package com.qq.closie.data.backup

import android.content.Context
import java.io.File
import java.io.IOException

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
 *
 * ### [RetryRequired] describes the protocol, not the gate
 *
 * A common misreading — and one the code used to encode — is that [RetryRequired] means "the gate must
 * stay blocked". It does not. It means **"some durable work is still outstanding"**, which covers two
 * genuinely different situations:
 *
 *  - **Data repair pending.** A surface may still disagree with the others. Reading it would serve a
 *    mixture, so the gate must stay blocked.
 *  - **Cleanup pending.** Every durable surface is already consistent — the marker reached a terminal
 *    state ([RestoreState.COMMITTED], [RestoreState.ROLLED_BACK] or [RestoreState.FAILED]) before the
 *    failure — and only leftover artefacts remain to be deleted. Nothing is inconsistent, so the gate
 *    **must open**; blocking here would let an undeletable stale directory brick the app.
 *
 * [RestoreRecoveryManager.recoverOnStartup] is the one place that tells them apart, and it does so by
 * re-reading the marker's state rather than by the verdict alone. Callers that only log should treat
 * [RetryRequired] as "recovery had something to say"; the gate is the authority on whether data may be
 * read.
 */
sealed interface RecoveryOutcome {
    /** Nothing pending (no marker at all). */
    object NoWork : RecoveryOutcome

    /** A pending restore was fully resolved (reverted or finished and cleaned up). */
    object Completed : RecoveryOutcome

    /**
     * Recovery did not completely finish. The marker is preserved either way.
     *
     * Whether this blocks the gate depends on the marker's state — see the type-level note above. Do not
     * infer "blocked" from this verdict.
     */
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
 * ### Why this is a per-state decision and not one `includesLifeOs &&` prefix
 *
 * The two inputs are **not** independent, so they cannot be combined by a single conjunction. That
 * shape — `includesLifeOs && state in {DB_COMMITTING, DB_COMMITTED, HEALTH_CHECKING}` — collapses to a
 * state-only predicate the moment `includesLifeOs` is `true`, and it makes the wrong answer for the one
 * state where the flag actually carries information:
 *
 * | state            | what the state alone means                                    | requires DB repair? |
 * |------------------|---------------------------------------------------------------|---------------------|
 * | `DB_COMMITTING`  | the transaction was **opened** (snapshot written, rows moving) | **yes**, always      |
 * | `DB_COMMITTED`   | the transaction **returned**; rows are the backup's            | **yes**, always      |
 * | `HEALTH_CHECKING`| the post-swap check is running — reached by **v1 and v2**      | **only if** v2       |
 * | everything else  | provably before the transaction, or already resolved           | no                   |
 *
 * `HEALTH_CHECKING` is the crux. Both formats pass through it, but the state alone cannot say whether a
 * transaction ever ran: for v2 the rows may already be the backup's, while for **v1** there is no Life OS
 * section at all — no transaction, no rows written, and no snapshot on disk. Answering "yes" for a v1
 * marker would send recovery hunting for a snapshot file that was never written, and — far worse — would
 * let it `withTransaction` a database the archive never mentioned, overwriting 记录 and 资料库 the user
 * accumulated *after* taking that v1 backup. So `HEALTH_CHECKING` is the one state that must consult
 * [RestoreIntent.includesLifeOs], and it is the only one.
 *
 * `DB_COMMITTING` / `DB_COMMITTED` deliberately do **not** consult it. Those states are reachable only
 * by the branch that writes the snapshot and opens the transaction, and that branch is v2-gated — so a
 * marker claiming `DB_COMMITTING` with `includesLifeOs = false` is not a v1 restore to be left alone, it
 * is a **contradictory marker**: the durable record of a database half that is genuinely in flight, with
 * a flag claiming no such half exists. Reading it as "nothing to do" is the dangerous direction: recovery
 * would consume the parked trees, delete the marker, and step over a database that may hold the backup's
 * rows. The conservative answer is `true`, which leaves the marker and the snapshot in place for a later
 * pass that has a database — a retry that may well be unnecessary, but never a silent data loss.
 */
fun RestoreIntent.requiresDatabaseRecovery(): Boolean = when (state) {
    // The transaction is open or has returned: the rows moved, regardless of what the flag says.
    RestoreState.DB_COMMITTING -> true
    RestoreState.DB_COMMITTED -> true
    // Reached by both formats. Only a v2 restore actually ran a transaction to get here.
    RestoreState.HEALTH_CHECKING -> includesLifeOs
    // PREPARING / STAGED / CLOSET_SWAPPED / MEDIA_SWAPPED are all provably before the transaction;
    // COMMITTED / FAILED / ROLLED_BACK are already resolved and must never be replayed over.
    else -> false
}

/**
 * True when the Life OS **media** directory may hold the backup's tree and must be reverted.
 *
 * ### The truth table follows the restore's real ordering, not the flag
 *
 * The v2 path is `PREPARING → STAGED → CLOSET_SWAPPED → MEDIA_SWAPPED → DB_COMMITTING → DB_COMMITTED →
 * HEALTH_CHECKING → COMMITTED`, so *where* a marker sits in that sequence is what decides whether the
 * media directory could have been swapped. `includesLifeOs` alone cannot answer it: the flag says the
 * archive *had* a media section, not that the swap *happened*. A v2 restore killed at `STAGED` has
 * `includesLifeOs = true` and has touched no directory at all — reverting media there would delete a
 * media tree that was never replaced.
 *
 * ```
 * PREPARING, STAGED          -> false   (provably before any swap)
 * CLOSET_SWAPPED             -> includesLifeOs
 * MEDIA_SWAPPED              -> true    (it *is* the swap)
 * DB_COMMITTING, DB_COMMITTED-> true    (these follow MEDIA_SWAPPED, so the swap happened)
 * HEALTH_CHECKING            -> includesLifeOs
 * COMMITTED, FAILED, ROLLED_BACK -> false  (resolved; cleanup only)
 * ```
 *
 * `CLOSET_SWAPPED` is the one state that genuinely needs the flag, and the reason is a kill window: the
 * media rename can complete while the `MEDIA_SWAPPED` marker write is still outstanding, so a
 * `CLOSET_SWAPPED` marker may already have the backup's media live. Only `includesLifeOs` distinguishes
 * "v2, media may be swapped" from "v1, media was never touched".
 *
 * `DB_COMMITTING` / `DB_COMMITTED` are `true` **even when `includesLifeOs = false`**. Those states are
 * reachable only through the v2 branch, and only after `MEDIA_SWAPPED`, so the state itself is stronger
 * evidence that the media moved than a flag that contradicts it. This is the same asymmetry
 * [requiresDatabaseRecovery] applies, and it must be applied here too — otherwise the two predicates
 * disagree about one marker and recovery repairs the database and the Closet while leaving the media on
 * the backup's tree, then deletes the media evidence in cleanup: a permanent half-restore.
 */
fun RestoreIntent.requiresLifeMediaRecovery(): Boolean = when (state) {
    // Provably before the media swap: nothing has been renamed yet.
    RestoreState.PREPARING, RestoreState.STAGED -> false
    // The media rename may have completed without the marker catching up — only the format can say.
    RestoreState.CLOSET_SWAPPED -> includesLifeOs
    // Self-evidencing v2 states: MEDIA_SWAPPED *is* the swap, and the DB states follow it. The flag is
    // deliberately not consulted, because a flag contradicting these states is the flag that is wrong.
    RestoreState.MEDIA_SWAPPED, RestoreState.DB_COMMITTING, RestoreState.DB_COMMITTED -> true
    // Reached by both formats after the swap: only a v2 restore ever swapped media.
    RestoreState.HEALTH_CHECKING -> includesLifeOs
    // Resolved. Reverting here would destroy a committed restore or redo a finished rollback.
    RestoreState.COMMITTED, RestoreState.FAILED, RestoreState.ROLLED_BACK -> false
}

/**
 * The name of the first piece of evidence recovery would need but the marker does not carry, or `null`
 * when the marker is complete enough to act on.
 *
 * ### Why completeness is checked from what recovery *will do*, not from the flag
 *
 * A marker is only actionable if it names the paths the rollback is about to use. The rule is therefore
 * derived from the three predicates, so the check cannot drift away from them:
 *
 *  - if the state may require a Closet rollback ([swapInScope]), `closetOldDir` must exist;
 *  - if [requiresLifeMediaRecovery], `mediaStageDir` and `mediaOldDir` must exist;
 *  - if [requiresDatabaseRecovery], `dbSnapshot` must exist.
 *
 * Note this deliberately does **not** ask whether the *files* still exist. A stage path is expected to
 * be gone after its rename — the path is metadata describing where the swap happened, and the
 * `existedBefore` flags are what say whether there was anything there. Checking for files would reject
 * every post-swap marker, which is exactly when recovery is needed.
 *
 * ### Why this is not limited to contradictory markers
 *
 * An earlier version short-circuited on `includesLifeOs == true`, on the theory that the flag establishes
 * v2 so the metadata must be present. It does not. `includesLifeOs` records what the *archive* contained,
 * not what this marker file managed to persist — and a `DB_COMMITTED` marker with `mediaOldDir == null`
 * would roll the database and the Closet back, fail to revert media, and then delete the marker over a
 * resulting half-restore. The flag proves nothing about completeness, so completeness is checked
 * directly, for every non-terminal marker.
 *
 * ### Terminal markers are exempt on purpose
 *
 * `COMMITTED` / `ROLLED_BACK` / `FAILED` mean every durable surface already agrees and only cleanup
 * remains. Demanding rollback metadata there would be wrong twice over: it would fail a recovery that has
 * nothing left to undo, and it would do so over data that is already consistent.
 */
fun RestoreIntent.missingRecoveryEvidence(): String? {
    when (state) {
        // Cleanup only: no rollback evidence is required, and inventing a requirement would block a
        // recovery that has already succeeded.
        RestoreState.COMMITTED, RestoreState.FAILED, RestoreState.ROLLED_BACK -> return null
        else -> Unit
    }
    if (swapInScope && closetOldDir == null) return "closetOldDir"
    if (requiresLifeMediaRecovery()) {
        if (mediaStageDir == null) return "mediaStageDir"
        if (mediaOldDir == null) return "mediaOldDir"
    }
    if (requiresDatabaseRecovery() && dbSnapshot == null) return "dbSnapshot"
    return null
}

/** Whether the marker is missing evidence recovery would need. See [missingRecoveryEvidence]. */
fun RestoreIntent.hasIncompleteLifeOsEvidence(): Boolean = missingRecoveryEvidence() != null

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

    /**
     * Clears the marker and **proves** it is gone.
     *
     * [clear] is best-effort — `AtomicFile.delete()` returning `false` is swallowed — and that is
     * acceptable only where the marker's absence is a convenience. It is *not* acceptable on the paths
     * that conclude a recovery: the marker is the only durable record that an interrupted restore is
     * outstanding, and every caller that treats "no marker" as "the user's data is consistent" is
     * trusting this delete. A `clear` that did not actually delete would report a finished recovery over
     * a marker that is still there — and, because the gate opens on the same verdict, over a Closet the
     * next start would then find ambiguous.
     *
     * The verification goes through **the same read** the rest of recovery uses rather than testing the
     * tmp/parent files itself. `AtomicFile` owns the backup-file protocol, so a hand-rolled check could
     * disagree with it — reporting a live marker as gone (or vice versa) precisely when a torn write had
     * been recovered. Re-reading through [AtomicJson.read] asks the one question that matters and asks it
     * with the one implementation that will answer it at the next startup.
     *
     * @throws IOException when the marker survives the clear. Callers leave their own evidence intact and
     *   report a retry, so a later start repeats this instead of assuming a clean slate.
     */
    fun clearChecked(context: Context) {
        val file = markerFile(context)
        clear(context)
        // A surviving marker is indistinguishable from a restore still in progress. Treating it as
        // "cleared" is the failure mode this function exists to make impossible.
        if (AtomicJson.read<RestoreIntent>(file) !is AtomicJson.ReadResult.Missing) {
            throw IOException("恢复标记删除后仍然存在: ${file.absolutePath}")
        }
    }
}
