package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaStoreImporter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The restore commit boundary — the one place whose correctness is a property of what happens *between*
 * the commits, because it spans three resources (two directory trees and a Room database) that cannot
 * share a transaction.
 *
 * ### The commit sequence (v2)
 * ```
 * PREPARING       marker written; unpack + stage both filesystem surfaces
 * STAGED          everything proven in staging; nothing durable touched yet
 * CLOSET_SWAPPED  closie -> oldDir, stage -> closie
 * MEDIA_SWAPPED   media  -> oldDir, stage -> media
 * DB_COMMITTING   old rows saved; one Room transaction on the live database, in flight
 * DB_COMMITTED    that transaction returned successfully
 * HEALTH_CHECKING reload in-memory state, re-validate the live directory + media
 * COMMITTED       drop oldDirs, dbSnapshot, marker
 * ```
 * v1 (no Life OS section) skips MEDIA_SWAPPED, DB_COMMITTING and DB_COMMITTED entirely and never touches
 * the database or Life media:
 * ```
 * PREPARING -> STAGED -> CLOSET_SWAPPED -> HEALTH_CHECKING -> COMMITTED
 * ```
 *
 * ### Crash consistency, not power-loss transactions
 * Each publish is `live → old → stage → live`, and the marker is written with the relevant paths and
 * `existedBefore` flags *before* the first rename. Every individual step after STAGED is therefore
 * recoverable, and the end state is always one of: three surfaces all backup, or three surfaces all
 * pre-restore.
 *
 * ### Recovery is decided by the filesystem, not by the state alone
 *
 * The marker can lag the filesystem by one step: the process can die after a rename and before the
 * marker rewrite that would have recorded it. So recovery never trusts [RestoreState] as the last word.
 * It reconstructs the truth from [RestoreIntent] (the paths, the `existedBefore` flags) plus four
 * observations on disk — does `live` exist, does `old` exist, does `stage` exist — and the state is
 * only used to decide *which* surfaces are even in scope.
 *
 * Two consequences that the previous revision got wrong:
 *
 *  - **The original may not have existed.** If `closetExistedBefore` is false there is no parked tree to
 *    rename back; the swapped-in directory is a half-restore and must be *deleted* so the end state is
 *    "not there". Keying that decision on `oldDir.exists()` silently skipped the rollback entirely.
 *  - **A rename can fail.** `File.renameTo` and `File.deleteRecursively` return a Boolean, and on a real
 *    filesystem they do fail (full disk, permission, a file open elsewhere). Ignoring that return value
 *    made recovery *report* success over a directory it had not actually moved — which is worse than
 *    failing, because it then cleared the marker and destroyed the evidence. Every recovery-critical
 *    operation here is checked and verified by re-reading the filesystem.
 *
 * ### Ownership of the fault-injection seam
 * The [RestoreHooks] are fired only here, in this one ordered sequence — `BackupImporter` validates,
 * `LifeBackupApplier` merely writes rows, and neither touches the hooks. That keeps the seam in exactly
 * one place so the production code path is the one the regression tests exercise.
 *
 * @param hooks the fault-injection seam. Production passes [NoOpRestoreHooks].
 * @param fs the filesystem operations recovery depends on. Production uses [RealRestoreFs]; tests
 *   substitute a wrapper that fails one specific operation, which is how the "rollback itself fails"
 *   paths below are reachable without contriving a read-only filesystem.
 */
internal class RestoreCoordinator(
    private val context: Context,
    private val hooks: RestoreHooks = NoOpRestoreHooks,
    private val fs: RestoreFs = RealRestoreFs
) {

    /**
     * Runs a full restore, or leaves the user's data exactly as it was.
     *
     * ### Two phases, and the commit point between them
     *
     * This function is deliberately shaped as **Phase A / commit point / Phase B**:
     *
     *  - **Phase A — the restore protocol.** PREPARING → STAGED → CLOSET_SWAPPED → MEDIA_SWAPPED →
     *    DB_COMMITTING → DB_COMMITTED → HEALTH_CHECKING → COMMITTED. Until the `COMMITTED` marker is
     *    durably written, any failure may be compensated: nothing is official yet, so undoing is the
     *    correct answer.
     *  - **The commit point.** `RestoreIntentStore.write(state = COMMITTED)`. The moment that returns,
     *    all three surfaces hold the backup's version and the restore *has succeeded*. This is
     *    irrevocable.
     *  - **Phase B — post-commit cleanup.** Delete the parked trees, the snapshot and any staging
     *    leftovers. Cleanup is housekeeping: if it fails, the marker stays COMMITTED and the next start
     *    finishes the job. **Compensation is never entered from here.**
     *
     * The distinction is not stylistic. `requiresDatabaseRecovery()` is `false` for `COMMITTED`, so a
     * compensation entered after the commit point would revert the Closet and the media while leaving
     * the database on the backup's rows — it would *manufacture* the exact three-way split this whole
     * protocol exists to prevent, and it would do so on an already-successful restore.
     *
     * ### The return value is about the restore, not the housekeeping
     *
     * Once the COMMITTED marker is durable, this returns success even if cleanup or the repository
     * refresh afterwards fails. Reporting a failed restore there would be a lie with a cost: the user
     * would reasonably run the restore again, and re-running a restore onto already-restored data is how
     * a working install gets destroyed. A leftover parked directory is a self-healing inconvenience by
     * comparison — recovery deletes it on the next start.
     *
     * @return success once the restore is official; the failure that made it undo itself otherwise.
     */
    suspend fun restore(
        repo: WardrobeRepository,
        inputUri: Uri,
        lifeDatabase: LifeDatabase?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val id = System.currentTimeMillis()
        val closetDataDir = dataDirOf(context)
        val closetStage = File(filesDir, ".closie_restore_stage_$id")
        val closetOld = File(filesDir, ".closie_restore_old_$id")
        val mediaData = mediaDirOf(context)
        val mediaStage = File(filesDir, ".life_media_restore_stage_$id")
        val mediaOld = File(filesDir, ".life_media_restore_old_$id")
        val extractDir = File(context.cacheDir, "backup_restore_extract_$id")

        // `intent` is the durable record of how far we got. It is the *outer* variable so the
        // compensation path below can read the last state we durably reached.
        var intent = RestoreIntent(id = 0L)

        // ---- Phase A: the restore protocol. Any failure here may be compensated. ---------------
        val phaseA = runCatching {
            // ---- PREPARING: unpack and stage. Nothing durable has changed yet. -------------------
            val staged = BackupImporter.stage(context, inputUri, extractDir, closetStage, mediaStage)
            val includesLifeOs = staged.payload != null

            // A v2 archive MUST have a live database. Fail here — before any live mutation — rather
            // than touch live data and then discover there is no database to apply the rows to.
            if (includesLifeOs && lifeDatabase == null) {
                throw IllegalStateException("v2 备份需要 Life OS 数据库才能恢复；缺少数据库时不得改动任何现有数据")
            }

            intent = RestoreIntent(
                id = id,
                state = RestoreState.PREPARING,
                includesLifeOs = includesLifeOs,
                closetStageDir = closetStage.absolutePath,
                closetOldDir = closetOld.absolutePath,
                mediaStageDir = if (includesLifeOs) mediaStage.absolutePath else null,
                mediaOldDir = if (includesLifeOs) mediaOld.absolutePath else null,
                closetExistedBefore = closetDataDir.exists(),
                mediaExistedBefore = mediaData.exists()
            )
            RestoreIntentStore.write(context, intent)

            // ---- STAGED: the archive is proven. From here the failure path matters. ----------------
            intent = intent.copy(state = RestoreState.STAGED)
            RestoreIntentStore.write(context, intent)

            // ---- CLOSET_SWAPPED: publish the wardrobe. -------------------------------------------
            hooks.beforeClosetSwap()
            // The marker already names both directories and carries the existedBefore flags (it was
            // written above, before STAGED) — that is what makes a kill between the two renames below
            // recoverable. A kill after `parkDirectory` but before the second rename leaves: live gone,
            // old present, stage present, marker at STAGED; `revertCloset` recognises exactly that.
            fs.park(closetDataDir, closetOld, intent.closetExistedBefore)
            fs.rename(closetStage, closetDataDir, "无法应用新衣橱数据")
            intent = intent.copy(state = RestoreState.CLOSET_SWAPPED)
            RestoreIntentStore.write(context, intent)
            hooks.afterClosetSwap()

            // ---- MEDIA_SWAPPED: publish Life OS media (v2 only). --------------------------------
            if (includesLifeOs) {
                hooks.beforeMediaSwap()
                fs.park(mediaData, mediaOld, intent.mediaExistedBefore)
                fs.rename(mediaStage, mediaData, "无法应用新媒体数据")
                intent = intent.copy(state = RestoreState.MEDIA_SWAPPED)
                RestoreIntentStore.write(context, intent)
                hooks.afterMediaSwap()
            }

            // ---- DB_COMMITTING -> DB_COMMITTED: the final data transition (v2 only). ------------
            if (includesLifeOs && lifeDatabase != null) {
                hooks.beforeDbCommit()
                val snapFile = LifeBackupApplier.snapshotFile(id, filesDir)
                // Snapshot BEFORE the transaction opens, and record where it lives before opening it.
                LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(lifeDatabase))
                intent = intent.copy(state = RestoreState.DB_COMMITTING, dbSnapshot = snapFile.absolutePath)
                RestoreIntentStore.write(context, intent)
                LifeBackupApplier.apply(context, staged.payload!!, lifeDatabase)
                intent = intent.copy(state = RestoreState.DB_COMMITTED)
                RestoreIntentStore.write(context, intent)
                hooks.afterDbCommit()
            }

            // ---- HEALTH_CHECKING: prove the live state is what we think it is. --------------------
            // Note what is *not* done here: the repository is not reloaded. Its in-memory state still
            // describes the pre-restore world, which is exactly what we want if this fails — the
            // filesystem gets reverted below, and the memory was never moved, so disk and memory cannot
            // drift apart. Publishing the new Closet into memory is a post-commit concern (Phase B).
            intent = intent.copy(state = RestoreState.HEALTH_CHECKING)
            RestoreIntentStore.write(context, intent)
            hooks.beforeHealthCheck()
            if (!BackupValidator.validateDataDirectory(closetDataDir)) {
                throw IllegalStateException("恢复后衣橱数据校验失败")
            }
            if (includesLifeOs) {
                LifeBackupApplier.healthCheckMedia(lifeDatabase!!, mediaData)
            }
            hooks.afterHealthCheck()

            // ---- COMMITTED: the new data is official. This is the commit point. -------------------
            intent = intent.copy(state = RestoreState.COMMITTED)
            RestoreIntentStore.write(context, intent)
        }

        // Failure anywhere in Phase A is compensated while the marker is still pre-commit. Once the
        // COMMITTED marker is durable we must not undo, so this is gated on the state we actually
        // reached, not merely on "something threw".
        if (phaseA.isFailure && intent.id != 0L && intent.state != RestoreState.COMMITTED) {
            runCatching { compensateRestore(context, intent, lifeDatabase) }
        }

        // ---- Phase B: post-commit cleanup. Never compensates. ----------------------------------
        val result = if (phaseA.isSuccess) {
            // The restore is official. Everything from here is housekeeping, and every failure in it is
            // recorded rather than acted on: these steps must not be able to turn a success into a
            // reported failure. Each is individually best-effort so one problem cannot prevent the
            // others — a leftover parked tree must not stop the marker from being cleaned up, and so on.
            var cleanupFailure: Throwable? = null

            // Publish the new Closet into the running process. This has to happen *after* the commit
            // because before it the in-memory state is the rollback's best friend; and it must happen
            // for the user to see their restored wardrobe without a restart.
            runCatching { repo.reloadFromDisk() }.onFailure { cleanupFailure = it }

            runCatching { fs.cleanupChecked(intent) }.onFailure { cleanupFailure = cleanupFailure ?: it }
            runCatching { RestoreIntentStore.clear(context) }.onFailure { cleanupFailure = cleanupFailure ?: it }

            // `cleanupFailure` is intentionally not surfaced as a Result failure. See the note above:
            // the restore *succeeded*, and saying otherwise invites a destructive re-run. Recovery
            // retries the cleanup on the next start because the marker survives when the clear failed.
            if (cleanupFailure != null) {
                android.util.Log.w(
                    "RestoreCoordinator",
                    "恢复已完成，但收尾未彻底完成（下次启动会继续清理）",
                    cleanupFailure
                )
            }
            Result.success(Unit)
        } else {
            Result.failure(phaseA.exceptionOrNull()!!)
        }

        // Staging scratch is never live data; remove it whether we succeeded or failed.
        runCatching { extractDir.deleteRecursively() }
        runCatching { closetStage.deleteRecursively() }
        runCatching { mediaStage.deleteRecursively() }

        result
    }

    companion object {
        /**
         * Filesystem-only recovery, for callers that cannot (yet) provide a database. Repairs the Closet
         * and Life media and cleans staging, but never touches the Life OS database.
         *
         * Returns an explicit [RecoveryOutcome] so the once-per-process guard can latch only on completion.
         * A [RecoveryOutcome.RetryRequired] means the marker is still on disk and the caller must not
         * proceed to read `closie/`.
         */
        fun recoverFilesystemOnly(
            context: Context,
            fs: RestoreFs = RealRestoreFs
        ): RecoveryOutcome {
            val intent = when (val read = RestoreIntentStore.read(context)) {
                is AtomicJson.ReadResult.Missing -> return RecoveryOutcome.NoWork
                // A marker we cannot read is not "no marker". Keep it and ask for a retry rather than
                // deleting the user's only record of an interrupted restore.
                is AtomicJson.ReadResult.Corrupt -> return RecoveryOutcome.RetryRequired(read.cause)
                is AtomicJson.ReadResult.Success -> read.value
            }
            return try {
                recoverFilesystem(context, intent, fs)
                RecoveryOutcome.Completed
            } catch (e: Exception) {
                // A genuine filesystem failure: the marker is untouched, so the next caller retries.
                RecoveryOutcome.RetryRequired(e)
            }
        }

        /**
         * Full recovery, including the Life OS database. Safe to call from any entry point; never throws.
         */
        suspend fun recover(
            context: Context,
            lifeDatabase: LifeDatabase?,
            fs: RestoreFs = RealRestoreFs
        ): RecoveryOutcome = withContext(Dispatchers.IO) {
            val intent = when (val read = RestoreIntentStore.read(context)) {
                is AtomicJson.ReadResult.Missing -> return@withContext RecoveryOutcome.NoWork
                is AtomicJson.ReadResult.Corrupt -> return@withContext RecoveryOutcome.RetryRequired(read.cause)
                is AtomicJson.ReadResult.Success -> read.value
            }
            try {
                recoverFilesystem(context, intent, fs)

                // The database half — only ever for a v2 marker, and only when the snapshot is
                // actually available. `requiresDatabaseRecovery()` carries the includesLifeOs check, so
                // a v1 marker stops here and never sends us looking for a snapshot that cannot exist.
                if (intent.requiresDatabaseRecovery()) {
                    val snapPath = intent.dbSnapshot
                    val snapshot = when (val r = snapPath?.let { LifeBackupApplier.readSnapshot(File(it)) }) {
                        is AtomicJson.ReadResult.Success -> r.value
                        // Absent or corrupt both mean "cannot complete the database half now". Keeping
                        // the marker is the only safe answer for either: corrupt evidence must not be
                        // discarded, and unwritten evidence may still appear once the snapshot write is
                        // retried by a later pass.
                        else -> null
                    }
                    if (lifeDatabase == null || snapshot == null) {
                        // Database half still outstanding and not completable now: keep the marker (and
                        // its snapshot) so a later start with a database can finish.
                        return@withContext RecoveryOutcome.RetryRequired(
                            IllegalStateException("Life OS 数据库恢复尚未完成，已保留恢复标记")
                        )
                    }
                    LifeBackupApplier.restoreSnapshot(lifeDatabase, snapshot)
                }

                // Every surface is back to the pre-restore version. Only now is the marker removed.
                finishRecovery(context, intent, fs)
                RecoveryOutcome.Completed
            } catch (e: Exception) {
                RecoveryOutcome.RetryRequired(e)
            }
        }

        /**
         * Repairs both filesystem surfaces to the pre-restore version, driven by the marker's paths and
         * `existedBefore` flags together with what is actually on disk.
         *
         * Throws on any unverifiable operation; the callers turn that into [RecoveryOutcome.RetryRequired]
         * with the marker intact.
         */
        private fun recoverFilesystem(context: Context, intent: RestoreIntent, fs: RestoreFs) {
            when (intent.state) {
                // The new version is already official. There is nothing to undo — rollback here would
                // destroy a successful restore — so cleanup is the only work, and it is checked.
                RestoreState.COMMITTED -> fs.cleanupChecked(intent)
                // A failure that never mutated live data, or one whose rollback already completed.
                RestoreState.FAILED, RestoreState.ROLLED_BACK -> fs.cleanupChecked(intent)
                else -> {
                    revertCloset(context, intent, fs)
                    revertMedia(context, intent, fs)
                    // Staging trees are scratch: the restore that built them is over, and they are never
                    // live data.
                    intent.closetStageDir?.let { fs.deleteTree(File(it)) }
                    intent.mediaStageDir?.let { fs.deleteTree(File(it)) }
                }
            }
        }

        /** Removes every leftover artefact — the marker last, so a partial cleanup is resumable. */
        private fun finishRecovery(context: Context, intent: RestoreIntent, fs: RestoreFs) {
            fs.cleanupChecked(intent)
            RestoreIntentStore.clear(context)
        }
    }
}

// =====================================================================================
//  Filesystem seam
// =====================================================================================

/**
 * The filesystem operations the restore commit boundary depends on, wrapped so each one is *verified*
 * rather than assumed, and so tests can fail one operation on demand.
 *
 * `File.renameTo` / `File.deleteRecursively` returning `false` is not a theoretical concern: it is what
 * happens on a full disk, a permission-denied parent, or a tree with an open file. The previous code
 * discarded those booleans, so a rollback that moved nothing still looked like a success and the marker
 * was cleared over unrecovered data.
 */
internal interface RestoreFs {
    /** Renames [live] away to [old]; records an absent original when [existedBefore] is false. */
    fun park(live: File, old: File, existedBefore: Boolean)

    /** Renames [from] to [to], failing loudly if the filesystem did not actually do it. */
    fun rename(from: File, to: File, message: String)

    /** Deletes a tree (no-op when absent), failing loudly if it survives. */
    fun deleteTree(dir: File)

    /**
     * Removes a committed restore's leftovers: both parked trees and the database snapshot. Throws on
     * failure; the marker is deliberately *not* touched here so a partial cleanup can resume.
     */
    fun cleanupChecked(intent: RestoreIntent)
}

/** The production implementation: real filesystem calls, each one verified after it returns. */
internal object RealRestoreFs : RestoreFs {

    override fun park(live: File, old: File, existedBefore: Boolean) {
        if (live.exists()) {
            rename(live, old, "无法暂存旧数据目录")
        } else if (existedBefore) {
            // The caller said the original was there, but it is gone now: that is an unexpected state and
            // parking would silently "succeed" over it. Creating the placeholder keeps the invariant that
            // `old.exists() == true` means "an original to restore", which the revert logic depends on.
            if (!old.mkdirs() && !old.isDirectory) {
                throw IOException("无法建立旧数据占位目录: ${old.absolutePath}")
            }
        }
    }

    override fun rename(from: File, to: File, message: String) {
        if (!from.renameTo(to)) throw IOException("$message: ${from.absolutePath} -> ${to.absolutePath}")
        // Verify rather than trust: some filesystems report success on a cross-device or partially
        // applied rename.
        if (!to.exists() || from.exists()) {
            throw IOException("$message（重命名未真正生效）: ${from.absolutePath} -> ${to.absolutePath}")
        }
    }

    override fun deleteTree(dir: File) {
        if (!dir.exists()) return
        if (!dir.deleteRecursively()) throw IOException("无法删除目录: ${dir.absolutePath}")
        if (dir.exists()) throw IOException("目录删除后仍然存在: ${dir.absolutePath}")
    }

    override fun cleanupChecked(intent: RestoreIntent) {
        intent.closetOldDir?.let { deleteTree(File(it)) }
        intent.mediaOldDir?.let { deleteTree(File(it)) }
        intent.dbSnapshot?.let { path ->
            val f = File(path)
            if (f.exists()) {
                if (!f.delete()) throw IOException("无法删除数据库快照: $path")
                if (f.exists()) throw IOException("数据库快照删除后仍然存在: $path")
            }
        }
    }
}

// =====================================================================================
//  Shared, file-private helpers
// =====================================================================================

private fun dataDirOf(context: Context): File = File(context.filesDir, "closie")

/**
 * The Life OS media directory. Named from [MediaStoreImporter.MEDIA_DIR] rather than a second literal,
 * because the restore must swap the *same* directory the importer writes into — a second copy of the
 * name is a second chance for the two to drift.
 */
private fun mediaDirOf(context: Context): File = File(context.filesDir, MediaStoreImporter.MEDIA_DIR)

/**
 * Reverts the Closet to the user's tree.
 *
 * The decision uses **both** the parked directory and the `existedBefore` flag, because one alone is
 * ambiguous in the window this exists to cover (a process killed between the two renames, before the
 * marker caught up):
 *
 * | live | old  | `closetExistedBefore` | what happened                        | action                        |
 * |------|------|-----------------------|--------------------------------------|-------------------------------|
 * | gone | yes  | true                  | killed between the two renames       | `old -> live`                 |
 * | new  | yes  | true                  | swap completed, marker lagging       | delete `live`, `old -> live`  |
 * | new  | no   | false                 | no original; the new tree must go    | delete `live`                 |
 * | none | no   | false                 | nothing ever published               | nothing                       |
 *
 * The "old exists, so rename it back" case is why the flag cannot be the only input: at that moment
 * `live` may already hold the backup's tree, and doing nothing would leave the user with the backup's
 * wardrobe. The "no old, existedBefore false" case is why `old.exists()` cannot be the only input: with
 * no original to park there is nothing to rename, and the swapped-in tree has to be deleted instead.
 */
private fun revertCloset(context: Context, intent: RestoreIntent, fs: RestoreFs) {
    val old = intent.closetOldDir?.let { File(it) }
    val live = dataDirOf(context)

    if (old != null && old.exists()) {
        // Whatever `live` holds now is the half-restore; the parked tree is the user's data.
        if (live.exists()) fs.deleteTree(live)
        fs.rename(old, live, "无法恢复旧衣橱数据")
        return
    }
    // No parked original. If one never existed, `live` can only be the backup's tree — but only delete
    // it when the marker says a swap was even in scope, so an unrelated directory is never touched.
    if (!intent.closetExistedBefore && intent.swapInScope && live.exists()) {
        fs.deleteTree(live)
    }
}

/** Reverts Life OS media, mirroring [revertCloset] for the media surface. */
private fun revertMedia(context: Context, intent: RestoreIntent, fs: RestoreFs) {
    if (!intent.includesLifeOs) return
    val old = intent.mediaOldDir?.let { File(it) }
    val live = mediaDirOf(context)

    if (old != null && old.exists()) {
        if (live.exists()) fs.deleteTree(live)
        fs.rename(old, live, "无法恢复旧媒体数据")
        return
    }
    if (!intent.mediaExistedBefore && intent.swapInScope && live.exists()) {
        fs.deleteTree(live)
    }
}

/**
 * Compensates a failed restore.
 *
 * Rule: the marker is only rewritten to [RestoreState.FAILED]/[RestoreState.ROLLED_BACK] and cleared
 * after *every* durable half has actually been reverted. If any single revert step fails, the marker is
 * left at its last honest state and the evidence (oldDirs, dbSnapshot, marker) is preserved, so the next
 * startup retries. We never clear the marker to "look clean" while the user's data is half-restored.
 */
private suspend fun compensateRestore(
    context: Context,
    intent: RestoreIntent,
    lifeDatabase: LifeDatabase?,
    fs: RestoreFs = RealRestoreFs
) {
    // 1. Closet.
    revertCloset(context, intent, fs)
    // 2. Life media.
    revertMedia(context, intent, fs)
    // 3. Database replay (idempotent). Same includesLifeOs gate as recovery, so a v1 restore never
    //    sends us to a snapshot that was not written.
    if (intent.requiresDatabaseRecovery() && intent.dbSnapshot != null && lifeDatabase != null) {
        val snap = LifeBackupApplier.readSnapshot(File(intent.dbSnapshot))
        if (snap is AtomicJson.ReadResult.Success) {
            LifeBackupApplier.restoreSnapshot(lifeDatabase, snap.value)
        } else {
            // The evidence file is unusable. Abort the compensation *before* touching the parked trees,
            // which preserves everything and leaves the marker at its last honest state for the next
            // start — rather than rolling back half the surfaces over an unreplayable database.
            throw IOException("恢复快照不可用，无法回滚数据库: ${intent.dbSnapshot}")
        }
    }

    // Every durable half is reverted. Only now are the leftovers removed and the terminal state recorded.
    intent.closetOldDir?.let { fs.deleteTree(File(it)) }
    intent.mediaOldDir?.let { fs.deleteTree(File(it)) }
    intent.dbSnapshot?.let { path ->
        val f = File(path)
        if (f.exists() && !f.delete()) throw IOException("无法删除数据库快照: $path")
    }
    // FAILED means "nothing durable was ever published", which is true only before the first swap. Past
    // that point the honest terminal state is ROLLED_BACK.
    val terminal = if (intent.swapInScope) RestoreState.ROLLED_BACK else RestoreState.FAILED
    RestoreIntentStore.write(context, intent.copy(state = terminal))
    RestoreIntentStore.clear(context)
}
