package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
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
     * Never throws — it reports. Callers **must** inspect the returned [RecoveryOutcome]: a
     * [RecoveryOutcome.RetryRequired] means `closie/` was not repaired and may be a half-restore, so
     * reading it would expose the user to the backup's wardrobe over their own data.
     *
     * This overload cannot see the Life OS database, so it repairs the filesystem halves and leaves the
     * marker in place if the database half also needs work — call [recoverInterruptedRestore] with a
     * database to finish that. Doing the wardrobe half here is the right split: the repository
     * constructor is the earliest point at which `closie/` must already be correct.
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
     */
    internal suspend fun restore(
        context: Context,
        repo: WardrobeRepository,
        inputUri: Uri,
        lifeDatabase: LifeDatabase,
        hooks: RestoreHooks,
        fs: RestoreFs
    ): Result<Unit> = RestoreCoordinator(context, hooks, fs).restore(repo, inputUri, lifeDatabase)

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
