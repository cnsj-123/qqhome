package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.repository.WardrobeRecoveryPublisher
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import java.io.File

/**
 * Backup format version.
 *
 *  - **1** — Closie only: five JSON files, the drafts, and the wardrobe images.
 *  - **2** — v0.3.0: adds the Life OS side (the Room database plus its managed media), which format
 *    1 did not know about at all. Restoring a v1 archive is still supported — see
 *    [BackupManager.restore], which treats v1 as "no Life OS section" rather than as an error.
 *
 * v2 exists because a v1 backup was *silently incomplete* once Life OS shipped: a user backing up
 * their wardrobe and restoring on a new phone would lose every 记录, 资料库 entry, 计划 and piece of
 * media without a single warning. A backup that silently omits half the app is worse than no backup,
 * because it is trusted.
 */
const val BACKUP_FORMAT_VERSION = 2
const val BACKUP_SCHEMA_VERSION = 1

/** Backward/forward-compatible backup format version. */
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val createdAt: String = "",
    /**
     * Whether the archive carries the Life OS section (`life/…`).
     *
     * Explicit in the manifest rather than inferred from the entries present: a v2 backup of an app
     * whose Life OS database happens to be empty is still a v2 backup, and inferring would make an
     * empty-but-valid archive indistinguishable from a truncated one.
     */
    val includesLifeOs: Boolean = false
)

/**
 * The public entry point for backup and restore.
 *
 * ### Why this is now only a facade
 *
 * This object used to be one ~800-line class that owned the ZIP format, the validation rules, the
 * restore coordination, the database apply and the rollback snapshot at once. That is how the
 * restore atomicity defect survived: the ordering that made a half-restore possible was invisible
 * inside a file whose stated job was "backup", so each new safeguard was added as another branch
 * around the same commit sequence rather than as a change to the sequence itself.
 *
 * The work is now split by direction and by irreversibility:
 *
 *  - [BackupValidator] — what counts as an acceptable archive or directory. Reads only.
 *  - [BackupExporter] / [BackupImporter] — the two directions of the archive format.
 *  - [LifeBackupApplier] — applying a typed payload to the live database, and the media write log.
 *  - [RestoreCoordinator] — the commit boundary: staging, the swap, the database commit, the health
 *    check, and recovery of an interrupted run.
 *
 * What remains here is the stable surface the UI and tests already depend on.
 *
 * ### The completeness contract
 *
 * A "complete backup" in v0.3 means all three surfaces: the Closet, the Life OS database and the Life
 * OS media. [export] and [restore] therefore require a non-null [LifeDatabase]. The old
 * `lifeDatabase: LifeDatabase? = null` default permitted a *v2 archive with no Life OS section* —
 * structurally valid, and a silent data-loss trap for the user who trusted it. Requiring the database
 * removes the possibility rather than documenting against it.
 *
 * v1 remains a **restore-only** format: archives written before v0.3.0 stay restorable forever, but
 * this build never produces one. Tests that need a v1 archive build one directly rather than asking
 * the exporter to fake one.
 */
object BackupManager {
    const val FORMAT_VERSION = BACKUP_FORMAT_VERSION
    const val SCHEMA_VERSION = BACKUP_SCHEMA_VERSION

    /**
     * Recovers from a restore that was interrupted (e.g. process killed) between publishing the
     * wardrobe and committing the Life OS half. Must run before the repository reads JSON.
     *
     * Never throws — it reports. Callers **must** inspect the returned [RecoveryOutcome], but the
     * meaning of each verdict is narrower than it looks:
     *
     *  - [RecoveryOutcome.Completed] — the whole protocol is finished. Both filesystem surfaces are back
     *    on the pre-restore version, nothing was left outstanding, the leftovers are gone and the marker
     *    is verifiably deleted. `closie/` may be read.
     *  - [RecoveryOutcome.RetryRequired] — *some durable work is still outstanding*. Read this as "the
     *    recovery protocol did not finish", **not** as "the Closet was not repaired". Which half is
     *    outstanding depends on the marker: for a marker whose database half is in scope this overload
     *    fixes it, so what remains is a filesystem operation that failed; for one it can complete, this
     *    verdict is never returned unless something actually failed. It also does **not** by itself mean
     *    the gate must stay blocked — a failure that leaves the durable data consistent (cleanup only) is
     *    reported as `RetryRequired` while the gate still opens. See
     *    [RestoreRecoveryManager.recoverOnStartup].
     *
     * That distinction is the point of this overload existing. It cannot see the Life OS database, so it
     * repairs the filesystem halves and, when a database replay is still required, deliberately returns
     * `RetryRequired` with the marker **and the snapshot** preserved — because the snapshot is the only
     * record of the rows to replay, and clearing the marker here would strand them. Call
     * [recoverInterruptedRestore] with a database to finish that half.
     *
     * Doing the wardrobe half here is the right split: the repository constructor is the earliest point
     * at which `closie/` must already be correct.
     */
    fun recoverInterruptedRestore(context: Context): RecoveryOutcome =
        RestoreCoordinator.recoverFilesystemOnly(context, RealRestoreFs)

    /**
     * [recoverInterruptedRestore] with an explicit [RestoreFs].
     *
     * `internal` because [RestoreFs] is a test seam, not production API: it exists so a test can fail one
     * named filesystem operation and reach the rollback-failure paths. Exposing it publicly would make
     * the internal filesystem abstraction part of the app's stable surface, which is exactly what the
     * "public function exposes its internal parameter type" error was warning about.
     */
    internal fun recoverInterruptedRestore(context: Context, fs: RestoreFs): RecoveryOutcome =
        RestoreCoordinator.recoverFilesystemOnly(context, fs)

    /**
     * Full recovery, including the Life OS database.
     *
     * Needed because a restore that was killed *after* its database transaction committed leaves both
     * halves on the backup's version, and undoing that requires the rows saved before the transaction.
     * Requires the live database, so it belongs at a point in startup where one exists — not in a
     * constructor.
     *
     * Unlike the filesystem-only overload, this one can finish a marker whose database half is
     * outstanding, and it does so in the order that keeps the evidence usable: it **validates the
     * snapshot, replays it into the database, and only then reverts the Closet and the media**. A snapshot
     * that is absent, corrupt or unreplayable therefore aborts with every parked tree untouched and the
     * marker preserved — rather than rolling the Closet back and leaving the database on the backup's
     * rows. `RetryRequired` here always means something genuinely failed; this overload never returns it
     * merely because the database was needed.
     */
    suspend fun recoverInterruptedRestore(
        context: Context,
        lifeDatabase: LifeDatabase?
    ): RecoveryOutcome = RestoreCoordinator.recover(context, lifeDatabase, RealRestoreFs)

    /** [recoverInterruptedRestore] with an explicit [RestoreFs]. `internal` for the reason above. */
    internal suspend fun recoverInterruptedRestore(
        context: Context,
        lifeDatabase: LifeDatabase?,
        fs: RestoreFs
    ): RecoveryOutcome = RestoreCoordinator.recover(context, lifeDatabase, fs)

    /**
     * Returns true when a data directory contains all five core JSON files and they all parse.
     * Draft files are optional (older backups have none); when present they must also parse.
     */
    fun validateDataDirectory(dir: File): Boolean = BackupValidator.validateDataDirectory(dir)

    /**
     * Writes a complete backup ZIP to [outputUri] (SAF document).
     *
     * [lifeDatabase] is required: a v0.3 backup always contains the Life OS database and media. See the
     * completeness contract on this object for why the nullable form was removed rather than
     * documented.
     */
    suspend fun export(
        context: Context,
        repo: WardrobeRepository,
        outputUri: Uri,
        lifeDatabase: LifeDatabase
    ): Result<String> = BackupExporter.export(context, repo, outputUri, lifeDatabase)

    /**
     * Restores a backup ZIP via a staged, crash-consistent flow.
     *
     * Both format versions are accepted:
     *
     *  - **v1** has no `life/` section. It restores the wardrobe and leaves the Life OS database
     *    completely untouched — not cleared, not recreated. A user who kept a v1 backup from before
     *    v0.3.0 and restores it should get their wardrobe back, not lose the 记录 and 资料库 they have
     *    accumulated since. Passing the live database alongside a v1 archive is harmless: the v1
     *    protocol never reads it.
     *  - **v2** additionally carries `life/data.json` (a typed [LifeBackupPayload]) and `life/media/…`.
     *
     * [lifeDatabase] is required even though a v1 archive will not use it. Keeping it non-null here is
     * what stops a production caller from forgetting it: a restore that silently ran without a database
     * would fail *after* it had already swapped the Closet, which is the worst possible moment.
     *
     * The ordering and rollback rules live in [RestoreCoordinator]; this method only forwards.
     */
    suspend fun restore(
        context: Context,
        repo: WardrobeRepository,
        inputUri: Uri,
        lifeDatabase: LifeDatabase
    ): Result<Unit> = restore(context, repo, inputUri, lifeDatabase, NoOpRestoreHooks)

    /** [restore] with an explicit [RestoreHooks] — the seam regression tests use to fail a stage. */
    suspend fun restore(
        context: Context,
        repo: WardrobeRepository,
        inputUri: Uri,
        lifeDatabase: LifeDatabase,
        hooks: RestoreHooks
    ): Result<Unit> = restore(context, repo, inputUri, lifeDatabase, hooks, RealRestoreFs)

    /**
     * [restore] with both seams explicit: [hooks] fails a *stage* of the sequence, [fs] fails an
     * individual filesystem operation. The latter is what makes the "rollback itself fails" paths
     * reachable — those are the states where recovery must keep its evidence and retry rather than
     * claim success.
     *
     * `internal` because [RestoreFs] is a test seam: a public signature here would expose the internal
     * filesystem abstraction as part of the app's API. [RestoreHooks] stays public on the overload above
     * because it is a legitimate stage-level seam callers can compose, but [RestoreFs] is not.
     *
     * ### The three things that must happen before anything is mutated
     *
     * A restore is the most destructive operation in the app, and it starts from a user tap — not from
     * `Application.onCreate`. So unlike recovery, nothing has already established that the process is in
     * a sane state. Three separate preconditions are checked here, in this order, and each one exists
     * because of a distinct way the previous revision lost data:
     *
     * 1. **No leftover marker.** A marker on disk means a restore (or its cleanup) did not finish. A
     *    `COMMITTED`/`ROLLED_BACK` marker is *consistent*, so the business gate is legitimately READY and
     *    the user may keep using the app — but it still points at a parked directory and a snapshot that
     *    are only deleted by the next cleanup pass. Writing a new marker would overwrite it, and that
     *    pointer would be gone: the leftover would become permanent, unattributed litter holding the
     *    user's previous wardrobe. **Business-READY is not the same permission as restore-allowed.**
     * 2. **Restore ownership.** [RestoreStartupGate.beginRestore] is an atomic `READY -> RESTORING` on
     *    the gate, so a second restore started while the first is mid-protocol cannot interleave its
     *    marker and snapshot with the first one's. Without this the two would share
     *    `.life_restore_intent.json`, and the loser's compensation would roll back the winner's data.
     * 3. **Free parking slots.** See [RestoreCoordinator]: a stale `.closie_restore_old_*` directory is
     *    not inert — both revert paths treat an existing parked tree as *the user's data* and rename it
     *    into place. Checked in the coordinator, before the marker that names those paths is written.
     *
     * ### Why the gate is *closed* for the duration, and not merely checked
     *
     * Checking `requireReady()` at the top is not enough, and the gap is not theoretical. Between that
     * check and the database transaction there is a window in which:
     *
     * ```
     *   - the snapshot has been written from "the database as it is now"
     *   - QuickCapture (or any live ViewModel) inserts one more row
     *   - the restore applies the archive over it
     *   -> the row the user created during the restore is gone, and no surface disagrees
     * ```
     *
     * The same window exists for the media swap: `MediaStoreImporter` writes into `filesDir/media`, the
     * exact tree the restore is about to delete and replace. A read-only check cannot close it, because
     * the writers are already past their own checks. Only *closing the gate* for the duration does, since
     * every business entry point re-consults it per call.
     *
     * ### The lifecycle, and every way it can end
     *
     * ```
     *   beginRestore (READY -> RESTORING, atomic)
     *     -> Phase A                      any failure may be compensated
     *     -> COMMITTED (the commit point)  the new data is official
     *     -> wardrobe reload (recovery-owned)
     *     -> endRestoreReady (RESTORING -> READY)
     *     -> Phase B cleanup               never rolls back
     * ```
     *
     *  - **Success** — `endRestoreReady()`, after the in-memory wardrobe snapshot has been republished
     *    from disk but *before* the cleanup, which cannot affect the verdict.
     *  - **Phase A failed, compensation succeeded** — the surfaces are back on the pre-restore version,
     *    so `endRestoreReady()`; the user's data is exactly what it was and the app keeps working.
     *  - **Phase A failed, compensation failed** — `markRestoreUnfinished()`, which is a one-way door:
     *    this process refuses all business access and only a fresh start can repair the evidence.
     *
     * ### The return contract
     *
     * `Result<Unit>`, on **every** path. This is a `suspend` function called from a button handler, and
     * the previous revision could throw straight out of it: the caller did `result.fold(...)` and set
     * `busy = false` afterwards, so an exception left the spinner turning forever and the screen dead.
     * Every refusal above is therefore a `Result.failure` — which is also the honest shape, because
     * "another restore is already running" is an outcome of the operation, not a programming error.
     */
    internal suspend fun restore(
        context: Context,
        repo: WardrobeRepository,
        inputUri: Uri,
        lifeDatabase: LifeDatabase,
        hooks: RestoreHooks,
        fs: RestoreFs
    ): Result<Unit> {
        // ---- 1. No leftover marker, terminal or otherwise. -----------------------------------------
        // Read rather than inferred from the gate: a terminal marker is consistent *and* present, which
        // is exactly the combination the gate cannot express. A corrupt marker counts as present — it is
        // still durable state that a new restore must not clobber.
        //
        // Refusals are `Result.failure` and not throws, because this is a public suspend entry point
        // called from a button handler: an escaping exception leaves the caller's `busy` flag set and
        // the screen stuck. "Another restore is already running" is an outcome of the operation, not a
        // programming error.
        when (RestoreIntentStore.read(context)) {
            is AtomicJson.ReadResult.Missing -> Unit
            is AtomicJson.ReadResult.Corrupt -> return Result.failure(
                RestoreAlreadyPendingException(null, "恢复标记无法读取，不能开始新的恢复（标记已保留，下次启动将重试）")
            )
            is AtomicJson.ReadResult.Success -> return Result.failure(
                RestoreAlreadyPendingException(null, "上一次恢复尚未收尾完成，不能开始新的恢复")
            )
        }

        // ---- 2. Ownership. -------------------------------------------------------------------------
        // Atomic `READY -> RESTORING`. Two concurrent restores cannot both win: the loser never gets an
        // ownership token and must not touch the marker, so the winner's marker is never overwritten.
        if (!RestoreStartupGate.beginRestore()) {
            return Result.failure(
                RestoreAlreadyPendingException(
                    RestoreStartupGate.blockReason,
                    "已有恢复正在进行，不能同时开始第二个"
                )
            )
        }

        // ---- 2b. The recovery-owned republish capability. ------------------------------------------
        // Resolved *after* ownership is taken, so a failure here is released by the same `catch` below
        // rather than leaking ownership.
        //
        // The public signature takes a [WardrobeRepository] because that is what every caller holds (the
        // settings screen, the tests). The republish capability is deliberately a *separate* type — see
        // [WardrobeRecoveryPublisher] — so it cannot be reached from that interface. The consequence is
        // that this one place has to bridge the two, and the bridge must not be an unchecked cast: a
        // repository that genuinely cannot republish its own in-memory state from disk cannot be used for
        // a restore at all, because the post-commit reload would have nothing to do, and the gate would
        // reopen over a stale in-memory wardrobe.
        val publisher = repo as? WardrobeRecoveryPublisher
            ?: run {
                val error = UnsupportedOperationException(
                    "该 WardrobeRepository 实现不提供恢复用的重载能力（WardrobeRecoveryPublisher），" +
                        "无法在恢复提交后重建内存状态；拒绝执行恢复"
                )
                // Ownership was already taken above; release it before reporting, because nothing durable
                // has happened — this is a preflight refusal, not a half-run.
                RestoreStartupGate.endRestoreReady()
                return Result.failure(error)
            }

        // ---- 3. Everything else, with the gate closed. ---------------------------------------------
        return try {
            RestoreCoordinator(context, hooks, fs).restore(repo, publisher, inputUri, lifeDatabase)
        } catch (error: Throwable) {
            // ### An unexpected escape is fail-closed, not fail-open
            //
            // This catch exists for a throwable that got past the coordinator's own verdict handling —
            // which is by construction a case nobody designed for. The previous revision's response was
            // to call `endRestoreReady()` unconditionally, and that is an assumption dressed as cleanup:
            //
            // ```
            //   "anything that escaped the coordinator must have escaped before any durable mutation"
            // ```
            //
            // That is not a property the coordinator provides. An exception can in principle escape from
            // *after* the Closet swap, *after* the database was replayed, or *after* `COMMITTED` — from a
            // new code path, a background failure, an `Error`. In all of those the durable surfaces may
            // already be mid-restore, and reopening the gate there serves a half-restored data set to
            // every reader: exactly the failure the gate exists to prevent, produced by its own cleanup.
            //
            // So the default here is the conservative one: record the failure as an unfinished restore,
            // which is sticky for the rest of the process and preserves all the evidence for the next
            // start. The *expected* failures are all handled inside the coordinator, where it can prove
            // what state it reached — a refusal to start, a corrupt archive, a compensated Phase A — and
            // it owns the gate verdict for each of them. This catch is only for what it could not prove,
            // and the honest verdict for "could not prove" is "assume the data may be inconsistent".
            //
            // The cost of being wrong in this direction is that the user restarts. The cost of the other
            // direction is data loss.
            RestoreStartupGate.markRestoreUnfinished(error)
            android.util.Log.e(
                "BackupManager",
                "恢复抛出未预期的异常：按 fail-closed 处理，证据保留、本次进程拒绝业务访问，下次启动继续恢复",
                error
            )
            Result.failure(error)
        }
    }

    /** Exports the wardrobe as a CSV (UTF-8 with BOM) for viewing on a computer. */
    suspend fun exportCsv(context: Context, repo: WardrobeRepository, outputUri: Uri): Result<Unit> =
        BackupExporter.exportCsv(context, repo, outputUri)

    /**
     * Builds a **wardrobe-only v2 archive** — an archive with no `life/` section.
     *
     * Kept `internal` and off the production surface on purpose. v1 is a restore-compatibility format,
     * not something this build should ever *produce*, and the old public signature
     * (`lifeDatabase: LifeDatabase? = null`) let a caller create exactly this shape of incomplete
     * "complete backup" by accident. Tests that need to exercise the v1 / no-Life-OS restore path use
     * this deliberately and by name.
     */
    internal suspend fun exportWardrobeOnlyForTesting(
        context: Context,
        repo: WardrobeRepository,
        outputUri: Uri
    ): Result<String> = BackupExporter.exportWardrobeOnly(context, repo, outputUri)
}
