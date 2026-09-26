package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.data.repository.WardrobeRecoveryPublisher
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
     * @param repo the wardrobe repository the rest of the app holds. Used only so the coordinator's
     *   signature stays honest about which object's in-memory state is being replaced; it is *not* the
     *   channel through which the reload happens.
     * @param publisher the recovery-owned republish capability. A distinct parameter rather than a method
     *   on [WardrobeRepository], so that it is not handed out on the interface every ViewModel already
     *   holds — see [WardrobeRecoveryPublisher] for why the seam must be a separate type.
     * @return success once the restore is official; the failure that made it undo itself otherwise.
     */
    suspend fun restore(
        repo: WardrobeRepository,
        publisher: WardrobeRecoveryPublisher,
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

        // ---- Preflight: the parking slots must be free before anything durable is written. --------
        //
        // ### Why this cannot be left to `fs.park`
        //
        // `RealRestoreFs.park` already refuses when the parking slot it was handed is occupied, and that
        // check stays — but it is a *second* line of defence, not the first, because by the time it runs
        // the marker has been written and it is the marker that makes the danger permanent:
        //
        // ```
        //   STAGED marker written, naming closetOld = .closie_restore_old_<id>
        //   -> fs.park(...) finds that path already occupied (a stale generation from an earlier run)
        //   -> park throws, Phase A fails
        //   -> compensateOrFailClosed -> compensateRestore -> revertCloset
        //   -> revertCloset sees `old.exists()` and concludes "this is the user's parked wardrobe"
        //   -> it deletes the real live tree and renames the stale one into place
        // ```
        //
        // The stale directory is *treated as the user's data* by every revert path, because "an existing
        // parked tree holds the user's previous version" is the invariant the whole protocol is built on.
        // That is what makes a stale slot dangerous rather than merely untidy: refusing to park is not
        // enough if the refusal happens after the marker that legitimises the rename.
        //
        // So the slots are verified here, before `intent` is constructed and before any write that names
        // them. Nothing has been mutated at this point, so refusing costs nothing — the live data is
        // untouched, the stale tree is untouched, and no marker exists to make either look otherwise.
        //
        // The `id` is wall-clock milliseconds, so a collision also means "a previous restore used this
        // id", which is exactly the generation collision worth refusing on: reusing the slot would merge
        // two generations of user data.
        //
        // ### A pre-durable refusal must not be escalated as an unexpected escape
        //
        // The check *cannot* throw out of this function. `beginRestore()` already moved the gate to
        // `RESTORING`, and the only handler above this frame is `BackupManager`'s outer `catch`, whose
        // verdict is [RestoreStartupGate.markRestoreUnfinished] — the sticky, restart-required block. That
        // verdict is correct for "a throwable escaped from somewhere I cannot reason about", and it is
        // badly wrong here: at this instant *nothing durable has happened at all*. The user's Closet, media
        // and database are untouched, no marker exists, and the stale slot is still exactly where it was.
        // Sticky-blocking the whole process over a stale leftover in `cacheDir`, with no marker for the
        // next start to clean up, would turn a recoverable refusal into "reinstall the app".
        //
        // So the refusal is absorbed here, where it can be *proven* pre-durable, and reported as an
        // ordinary `Result.failure` with the gate handed straight back:
        //
        // ```
        //   stale slot -> log, remove this run's scratch, endRestoreReady(), Result.failure
        //                 marker: Missing   Closet/media/DB: unchanged   stale slot: preserved
        // ```
        //
        // The scratch removal matters even though nothing was staged: the four paths below were *derived*
        // from a wall-clock id, and a second attempt a millisecond later derives different ones — but a
        // first attempt that got as far as creating the stage directory would otherwise leave it behind to
        // be found, and reported, as a "stale slot" by the retry.
        val occupiedSlot = listOf(
            "暂存目录" to closetStage,
            "旧衣橱暂存目录" to closetOld,
            "媒体暂存目录" to mediaStage,
            "旧媒体暂存目录" to mediaOld
        ).firstOrNull { (_, slot) -> fs.isSlotOccupied(slot) }

        if (occupiedSlot != null) {
            val (label, slot) = occupiedSlot
            val refusal = IOException(
                "恢复${label}已存在，拒绝在新的恢复中复用（可能是上一次恢复的残留）: ${slot.absolutePath}"
            )
            android.util.Log.w(
                "RestoreCoordinator",
                "恢复在写下任何标记之前被拒绝（${label}被占用）；未改动任何数据，已释放恢复所有权",
                refusal
            )
            // Only this run's own scratch, and only leftovers that cannot be anything else: the stage
            // paths are named after `id`, so nothing of the user's lives there. The *parking* slots are
            // deliberately untouched — the occupied one is the evidence itself, and deleting a
            // `.closie_restore_old_*` tree here would destroy the user's previous wardrobe, which is
            // exactly what this preflight exists to avoid.
            runCatching { extractDir.deleteRecursively() }
            runCatching { closetStage.deleteRecursively() }
            runCatching { mediaStage.deleteRecursively() }
            RestoreStartupGate.endRestoreReady()
            return@withContext Result.failure(refusal)
        }

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
        //
        // ### The `intent.id == 0L` case: a failure *before* the marker exists
        //
        // `intent` stays `RestoreIntent(id = 0L)` until the PREPARING marker is written, so a failure with
        // `id == 0L` means the protocol never recorded anything: `BackupImporter.stage` rejected a
        // corrupt ZIP, a bad manifest, a failed stage validator, or an unreadable input stream. Nothing
        // durable was published, so there is nothing to compensate — and returning a failure here is
        // correct.
        //
        // What the previous revision got wrong is what happens to the *gate*. `beginRestore()` already
        // moved it to `RESTORING`, and this path returned a `Result.failure` without anyone ending that
        // ownership — the coordinator did not call `endRestoreReady`, and `BackupManager`'s `catch` only
        // sees thrown exceptions, not a returned failure. So the process stayed `RESTORING` **forever**:
        // every business operation refused, every screen was dead, and only a restart cleared it. A
        // corrupt backup file could lock the app.
        //
        // So this is an explicit, first-class outcome: nothing durable happened, so reopen the gate, then
        // report the failure. The scratch/stage cleanup at the bottom of this function already runs on
        // this path, and the pre-marker state means there is no marker to preserve.
        if (phaseA.isFailure && intent.id == 0L) {
            android.util.Log.w(
                "RestoreCoordinator",
                "恢复在写入任何标记之前失败（归档不可用）；未改动任何数据，已释放恢复所有权",
                phaseA.exceptionOrNull()
            )
            RestoreStartupGate.endRestoreReady()
        } else if (phaseA.isFailure && intent.state != RestoreState.COMMITTED) {
            compensateOrFailClosed(context, intent, lifeDatabase, fs)
        }

        // ---- Phase B: post-commit cleanup. Never compensates. ----------------------------------
        val result = if (phaseA.isSuccess) {
            // The restore is official. Everything from here is housekeeping, and every failure in it is
            // recorded rather than acted on: these steps must not be able to turn a success into a
            // reported failure.
            var cleanupFailure: Throwable? = null

            // ### Publish the new Closet into the running process — *before* the gate reopens
            //
            // This has to happen after the commit, because before it the in-memory state is the
            // rollback's best friend. It also has to happen **before** `endRestoreReady`, and that
            // ordering is not a detail:
            //
            // ```
            //   gate READY first, then reload   ->  a window in which the UI reads the *pre-restore*
            //                                       wardrobe from memory while disk holds the restored
            //                                       one. The user sees their old wardrobe, edits an
            //                                       item, and the edit is written over the restore.
            //   reload first, then gate READY   ->  the first read any business code can perform
            //                                       already sees the restored wardrobe.
            // ```
            //
            // The reload itself cannot use the business path: `reloadFromDisk()` takes a business lease,
            // which requires a READY gate, and the gate is closed (that is the point). Hence the
            // capability-typed recovery seam. It is an `internal` interface the UI layer cannot obtain, so
            // this is a narrow seam rather than a bypass.
            //
            // ### Why a failed reload must *not* reopen the gate — this is the P0
            //
            // The previous revision wrapped this in `runCatching`, recorded the failure as a *cleanup*
            // failure, and then called `endRestoreReady()` unconditionally. That is right for a stale
            // in-memory view and catastrophic for a *failed* one, and the two are indistinguishable from a
            // `runCatching` alone:
            //
            // ```
            //   durable state: restored (COMMITTED — official, and it must stay official)
            //   reload:        threw, so memory still holds the PRE-restore wardrobe
            //   gate:          READY
            //   -> the UI serves the old wardrobe while disk holds the new one, and the user's next edit
            //      is written over the restore. Nothing in the process can tell that memory is wrong.
            // ```
            //
            // So the outcomes are separated by *what the reload actually is*. It only fails when a durable
            // surface could not be read back, which means memory and disk genuinely disagree and this
            // process cannot fix that by retrying (the data is fine; the read is not). The correct
            // response is therefore:
            //
            //  - the restore is still reported as **success** — `COMMITTED` is irreversible, and saying
            //    otherwise would invite the user to re-run a restore over restored data;
            //  - the marker is **left as `COMMITTED`** and cleanup is skipped, because deleting the marker
            //    would discard the only record that a finished restore still has leftovers;
            //  - the gate goes **sticky BLOCKED** (`markRestoreUnfinished`), because this process can no
            //    longer honestly claim its in-memory state matches the durable one;
            //  - the next process start reads the `COMMITTED` marker, cleans up, and builds *fresh*
            //    repositories from disk — which is a correct memory, so it can then be READY.
            val reloadOutcome = runCatching { publisher.reloadFromDiskForRecoveryChecked() }
            val reloadFailure = reloadOutcome.exceptionOrNull()

            if (reloadFailure == null) {
                // Memory and disk agree: business readers may be let back in. Done here rather than after
                // the cleanup because cleanup cannot change the data — and because a cleanup failure must
                // not leave the app refusing to start.
                RestoreStartupGate.endRestoreReady()
            } else {
                android.util.Log.e(
                    "RestoreCoordinator",
                    "恢复已提交，但重载内存衣橱失败：数据本身已恢复，本次进程将拒绝业务访问以防内存与磁盘不一致",
                    reloadFailure
                )
                // Sticky: this is not the reopenable startup-barrier block, it is "this process's view is
                // no longer trustworthy". Deliberately does *not* touch the marker.
                RestoreStartupGate.markRestoreUnfinished(reloadFailure)
            }

            // ### Cleanup and the marker are one decision, not two best-effort steps
            //
            // The marker is the only durable record that leftovers exist. Clearing it after a *failed*
            // cleanup does not merely leave a stray directory — it destroys the pointer to it. The
            // snapshot path lives in the marker, so the file recovery would have resumed from is gone,
            // and a `HEALTH_CHECKING` marker's snapshot is unrecoverable by construction. That is the
            // difference between "the next start finishes the cleanup" and "the leftover is permanent,
            // unattributed litter holding the user's previous wardrobe".
            //
            // So the clear is conditional: it runs only when the cleanup actually succeeded. The
            // previous revision ran both unconditionally and merely OR-ed their failures together,
            // which reported a finished cleanup while the marker's own delete had already hidden the
            // evidence. Note this cannot break the "restore succeeded" verdict — Phase B never turns
            // success into failure, and the *data* is committed either way.
            //
            // Skipped entirely when the reload failed: the marker must survive as the `COMMITTED` record
            // the next start cleans up from, and deleting the parked trees now would remove the very
            // leftovers that start is meant to find.
            if (reloadFailure == null) {
                val cleanupSucceeded = runCatching { fs.cleanupChecked(intent) }
                    .onFailure { cleanupFailure = cleanupFailure ?: it }
                    .isSuccess

                if (cleanupSucceeded) {
                    // Checked, so a silent failed delete is reported rather than assumed away: a surviving
                    // marker means the next start redoes the cleanup, which is merely slow.
                    runCatching { RestoreIntentStore.clearChecked(context) }
                        .onFailure { cleanupFailure = cleanupFailure ?: it }
                } else {
                    android.util.Log.w(
                        "RestoreCoordinator",
                        "恢复已完成，但收尾失败；已保留恢复标记，下次启动将继续清理"
                    )
                }
            } else {
                android.util.Log.w(
                    "RestoreCoordinator",
                    "恢复已提交但内存重载失败：已保留 COMMITTED 标记与残留，本次进程不再清理"
                )
            }

            // `cleanupFailure` is intentionally not surfaced as a Result failure. See the note above:
            // the restore *succeeded*, and saying otherwise invites a destructive re-run.
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
         * Filesystem-only recovery, for callers that cannot (yet) provide a database.
         *
         * ### What "Completed" means here, and what it does not
         *
         * This function repairs the Closet and Life media, then removes the leftovers **only when no
         * database work is outstanding**, and reports:
         *
         *  - [RecoveryOutcome.NoWork] — there was no marker. Nothing was read, nothing was touched.
         *  - [RecoveryOutcome.Completed] — the whole protocol is finished: both filesystem surfaces are
         *    back on the pre-restore version, the database needs nothing (the marker was pre-transaction,
         *    already resolved, or v1), the parked trees and the snapshot are gone, and the marker itself
         *    is verifiably deleted. `closie/` may be read.
         *  - [RecoveryOutcome.RetryRequired] — something durable is **still outstanding**. The marker is
         *    preserved, together with whatever evidence it points at, so a later pass finishes the job.
         *
         * The important correction is the second half of `Completed`: it used to be returned
         * unconditionally as soon as the filesystem repair did not throw, and the marker was never
         * cleared at all. Both halves of that were wrong. A `DB_COMMITTING` marker means a transaction may
         * already hold the backup's rows; reporting success — and letting the caller conclude `closie/` is
         * trustworthy — while that is still true is exactly the three-way split the protocol exists to
         * prevent. And leaving the marker behind made a *finished* recovery look permanently pending, so
         * the gate it feeds could never open.
         *
         * ### Why the caller must not read [RecoveryOutcome.RetryRequired] as "the Closet is broken"
         *
         * It means "the protocol has outstanding durable work", nothing more. The filesystem half has
         * *already* been repaired by the time this returns — non-atomically, yes, and without a
         * database this pass genuinely cannot do better — so a caller that sees `RetryRequired` still
         * finds the user's own wardrobe on disk. The distinction that matters is the opposite one:
         * `Completed` is a claim strong enough to open a gate, and it is only made when there is nothing
         * left to be inconsistent with.
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
                // Before anything is read into memory, renamed or deleted: is this marker actually
                // actionable? See `validateRecoveryEvidence` for why this must precede every mutation.
                validateRecoveryEvidence(intent)

                recoverFilesystem(context, intent, fs)

                // The database half is outstanding and this pass has no database to run it with. Keeping
                // the marker is not tidiness, it is the whole point: the snapshot it names is the only
                // record of the rows to replay, and `cleanupChecked` would delete that snapshot.
                if (intent.requiresDatabaseRecovery()) {
                    return RecoveryOutcome.RetryRequired(
                        IllegalStateException("Life OS 数据库恢复尚未完成，已保留恢复标记")
                    )
                }

                // Nothing durable is outstanding, so this pass can actually conclude the recovery rather
                // than leaving a marker that would make every later start redo it.
                finishRecovery(context, intent, fs)
                RecoveryOutcome.Completed
            } catch (e: Exception) {
                // A genuine filesystem failure: the marker is untouched, so the next caller retries.
                RecoveryOutcome.RetryRequired(e)
            }
        }

        /**
         * Full recovery, including the Life OS database. Safe to call from any entry point; never throws.
         *
         * ### Ordering: validate the snapshot, replay the database, *then* consume the parked trees
         *
         * The three destructive steps run in one strict order:
         *
         * ```
         *   1. read + validate the snapshot        (no mutation)
         *   2. replay the snapshot into the database
         *   3. revert the Closet and the media      (consumes the parked trees)
         * ```
         *
         * This is the same reason the restore snapshots *before* it opens its transaction: the snapshot is
         * the only copy of the rows that must come back, and every later step destroys evidence.
         * [revertCloset] deletes the live tree and renames the parked one into place; once it has run, the
         * "before" state is gone. If the snapshot turned out to be absent, corrupt, or unreplayable *after*
         * that point, the outcome would be a Closet rolled back to the user's version with no way to bring
         * the database with it — a split manufactured by *recovery*, on the exact marker that told it not
         * to.
         *
         * Doing the replay first costs one small JSON parse and one transaction, and makes the failure mode
         * safe: an unusable snapshot, or a replay that throws, aborts with **every parked tree still
         * intact**, so a later start retries from a known state instead of from a half-consumed one.
         *
         * The one thing that is deliberately *not* part of this order is a missing database. When
         * `lifeDatabase == null` there is nothing to replay into, and the filesystem repair is still safe
         * and still worth performing, so it runs and the marker is kept for a later start.
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
                // ---- Step 0: is the marker itself actionable? ---------------------------------------
                //
                // Must precede *everything*, including the snapshot read and the replay. See
                // `validateRecoveryEvidence`.
                validateRecoveryEvidence(intent)

                // ---- Database half, decided, read *and replayed* before anything is reverted. --------
                //
                // The snapshot is materialised first and — when a database is available — the replay
                // happens immediately, before `revertCloset`/`revertMedia` run. That order is the whole
                // point of this function's shape, not an incidental leftover:
                //
                //  - `revertCloset` deletes the live tree and renames the parked one into place, so once
                //    it has run the "before" state is gone. A snapshot found unusable *after* that would
                //    leave the Closet on the user's version and the database on the backup's — a split
                //    manufactured by recovery itself, on the very marker that told it not to.
                //  - A database replay that *fails* (a bad row, a constraint, a full disk) must likewise
                //    not have already consumed the parked trees: the evidence has to stay complete so the
                //    next start can try again from a known state.
                //
                // So an unusable snapshot or a failed replay aborts with every filesystem surface exactly
                // where the marker says it is, and the marker and parked trees still on disk.
                //
                // Only ever for a marker whose database genuinely moved, and only when the snapshot is
                // actually usable. `requiresDatabaseRecovery()` carries the v1 check for the one state
                // both formats share, so a v1 marker stops here and never sends us looking for a snapshot
                // that cannot exist.
                val snapshot = if (intent.requiresDatabaseRecovery()) {
                    val snapPath = intent.dbSnapshot
                    when (val r = snapPath?.let { LifeBackupApplier.readSnapshot(File(it)) }) {
                        // Included so the abort below reports *which* evidence was unusable.
                        is AtomicJson.ReadResult.Success -> r.value
                        // Absent or corrupt both mean "cannot complete the database half now". Keeping
                        // the marker is the only safe answer for either: corrupt evidence must not be
                        // discarded, and unwritten evidence may still appear once the snapshot write is
                        // retried by a later pass. Nothing has been reverted yet, so aborting here is
                        // free — the filesystem surfaces are still exactly where the marker says.
                        else -> {
                            return@withContext RecoveryOutcome.RetryRequired(
                                IOException("恢复快照不可用，无法安全回滚数据库: $snapPath")
                            )
                        }
                    }
                } else {
                    null
                }

                // Replay while the filesystem evidence is still intact — see the ordering note above.
                // Reached with a null database only when the caller has none; that case is handled below,
                // deliberately *after* the filesystem half, because the filesystem repair is safe to
                // perform without a database and deferring it would only give the next start more to do.
                if (snapshot != null && lifeDatabase != null) {
                    LifeBackupApplier.restoreSnapshot(lifeDatabase, snapshot)
                }

                // ---- Filesystem half. ---------------------------------------------------------------
                //
                // Reached even when the database is unavailable: the two halves are independent repairs
                // and this one needs no database. Skipping it because the *database* cannot be opened
                // would defer a repair that was safe to perform. When the database half *was* completed
                // above, this is the second stage of the same rollback; when it was not, this is the only
                // repair this pass can make, and the marker below keeps the rest for a later start.
                recoverFilesystem(context, intent, fs)

                // The database half could not be run at all (no database). The filesystem half is done;
                // keep the marker and its snapshot so a later start with a database can finish the job.
                if (intent.requiresDatabaseRecovery() && lifeDatabase == null) {
                    return@withContext RecoveryOutcome.RetryRequired(
                        IllegalStateException("Life OS 数据库恢复尚未完成，已保留恢复标记")
                    )
                }

                // Every surface is back to the pre-restore version. `finishRecovery` records that
                // durably — before it deletes the snapshot the marker may still point at — and only then
                // removes the marker.
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
         *
         * Terminal states are a **no-op** here, not a cleanup: [finishRecovery] owns cleanup (see the
         * note there), and doing it in both places would mean every terminal pass deleted the leftovers
         * twice — with the second pass running against trees the first had already removed.
         */
        private fun recoverFilesystem(context: Context, intent: RestoreIntent, fs: RestoreFs) {
            when (intent.state) {
                // The new version is already official. There is nothing to undo — rollback here would
                // destroy a successful restore — so only cleanup remains, and that is finishRecovery's job.
                RestoreState.COMMITTED -> Unit
                // A failure that never mutated live data, or one whose rollback already completed.
                RestoreState.FAILED, RestoreState.ROLLED_BACK -> Unit
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

        /**
         * Concludes a recovery whose rollback is **already complete**: records the terminal state, then
         * removes the leftovers, then verifiably clears the marker.
         *
         * ### Why the terminal state is durable *before* the cleanup
         *
         * This is the single most load-bearing ordering in the protocol, and it is not a detail of
         * tidiness. Consider what the three steps are, and what a crash between them would leave behind:
         *
         * ```
         *   1. write terminal marker   -> marker says "everything is rolled back; only cleanup remains"
         *   2. cleanupChecked          -> deletes the parked trees AND the dbSnapshot
         *   3. clearChecked            -> verifiably removes the marker
         * ```
         *
         * Now consider the previous order — cleanup, then clear, with no terminal write at all — on a
         * marker that entered recovery as `DB_COMMITTED`:
         *
         * ```
         *   marker = DB_COMMITTED
         *   -> snapshot replayed        (database is the user's again)
         *   -> closet/media reverted    (filesystems are the user's again)
         *   -> cleanupChecked deletes dbSnapshot
         *   -> clearChecked FAILS       (marker still says DB_COMMITTED)
         *   next start: DB_COMMITTED -> requiresDatabaseRecovery() = true
         *            -> wants to replay a snapshot that cleanup already deleted
         *            -> RetryRequired, forever. The user's data is fine and the app is bricked.
         * ```
         *
         * That is a permanent deadlock produced entirely by the *order*, and it is exactly what the
         * terminal states exist to prevent. Writing `ROLLED_BACK` first makes step 2 safe: if the run dies
         * anywhere after it, the next start reads a state that means "the rollback is done, do not replay
         * anything, just finish the cleanup" — so a deleted snapshot is expected rather than missed.
         *
         * The same argument applies to a `COMMITTED` marker (whose terminal state is itself) and to a
         * marker that never durably published anything (terminal `FAILED`). [persistTerminalAfterRollback]
         * is the one place that decides which.
         *
         * The marker is cleared last, and *checked*: [RestoreIntentStore.clearChecked] re-reads through the
         * same `AtomicJson` semantics and throws if the file survived, because every caller reports
         * "recovery finished" the moment this returns. A silent failed delete would report a completed
         * recovery over a marker that is still there — and, worse, over a Closet whose next start would
         * then be decided from that stale marker.
         */
        private fun finishRecovery(context: Context, intent: RestoreIntent, fs: RestoreFs) {
            persistTerminalAfterRollback(context, intent)
            fs.cleanupChecked(intent.copy(state = terminalStateAfterRollback(intent)))
            RestoreIntentStore.clearChecked(context)
        }
    }
}

/**
 * The state a marker should durably hold once its rollback is complete but before cleanup has run.
 *
 * An **already terminal** marker keeps its own state — see the `when` below. Beyond that, two cases, and
 * the distinction is the whole point:
 *
 *  - **A durable publish happened** (any state from [RestoreState.STAGED] onwards, which is what
 *    [RestoreIntent.swapInScope] tests): the live surfaces were replaced and have now been put back, so
 *    the honest terminal state is [RestoreState.ROLLED_BACK].
 *  - **Nothing was ever published** ([RestoreState.PREPARING]): there was nothing to roll back, so the
 *    honest terminal state is [RestoreState.FAILED] — "this restore never got anywhere".
 *
 * `FAILED` claiming "nothing durable was ever published" is only true before the first swap, which is
 * precisely what `swapInScope` distinguishes. Using it after a swap would understate what happened and
 * would be read by a later pass as "there is nothing on disk to undo".
 *
 * [RestoreState.COMMITTED] is deliberately *not* a case here: a committed restore's data is official and
 * must not be relabelled as rolled back. Callers on that path pass the marker through unchanged (see
 * [persistTerminalAfterRollback]).
 */
private fun terminalStateAfterRollback(intent: RestoreIntent): RestoreState = when (intent.state) {
    // Already terminal: keep it. A marker that has reached a terminal state has *already* recorded what
    // happened, and rewriting it would be a second, unnecessary write that could only lose information —
    // most visibly by relabelling a `FAILED` marker (nothing was ever published) as `ROLLED_BACK`
    // (something was published and put back), which is a claim about the user's data that is simply false.
    RestoreState.COMMITTED, RestoreState.ROLLED_BACK, RestoreState.FAILED -> intent.state
    // A durable publish happened, so the honest terminal state is ROLLED_BACK …
    else -> if (intent.swapInScope) RestoreState.ROLLED_BACK else RestoreState.FAILED
}

/**
 * Durably records the terminal state for a marker whose durable halves are all back on the pre-restore
 * version, **before** any leftover is deleted.
 *
 * ### Why this is one shared function and not a line in each caller
 *
 * Both the startup recovery path ([RestoreCoordinator.recover]) and the in-flight compensation path
 * ([compensateRestore]) reach the same moment — "every durable surface is restored, only cleanup
 * remains" — and both need the identical record written before the identical deletion. Two copies of that
 * step would be two chances for one of them to keep the old, deadlocking order; the previous revision had
 * it in neither. Keeping it here means the invariant is stated once and cannot drift between the paths.
 *
 * A [RestoreState.COMMITTED] marker is left exactly as it is. Its data is official, its meaning is already
 * terminal, and rewriting it to `ROLLED_BACK` would turn a successful restore into a rolled-back one in
 * the durable record — the one relabelling that would be actively harmful.
 *
 * This is **not** a second recovery state machine: it is a single write of an existing state value, using
 * the existing [RestoreIntentStore]. No new states, no new transitions.
 */
private fun persistTerminalAfterRollback(context: Context, intent: RestoreIntent) {
    if (intent.state == RestoreState.COMMITTED) return
    val terminal = terminalStateAfterRollback(intent)
    if (intent.state == terminal) return
    RestoreIntentStore.write(context, intent.copy(state = terminal))
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
    /**
     * Moves [live] out of the way to [old], so the staged tree can take its place.
     *
     * @param existedBefore whether [live] was present when the restore began. It is what makes a kill
     *   between the two renames decidable: the marker already carries it, so recovery never has to guess
     *   from whether [old] happens to be on disk.
     */
    fun park(live: File, old: File, existedBefore: Boolean)

    /** Renames [from] to [to], failing loudly if the filesystem did not actually do it. */
    fun rename(from: File, to: File, message: String)

    /** Deletes a tree (no-op when absent), failing loudly if it survives. */
    fun deleteTree(dir: File)

    /**
     * The preflight's one filesystem question: **is this candidate scratch/parking path already taken?**
     *
     * ### Why this is on the seam rather than inline in `restore()`
     *
     * The check itself is trivial — `File.exists()` — but the *behaviour around it* is not, and the
     * behaviour is what regressed. It runs before any marker exists, so a refusal at this point is a
     * pre-durable refusal that must reopen the gate rather than reach [RestoreStartupGate]'s sticky
     * `markRestoreUnfinished` path. That is a property worth a test, and a test cannot reach it honestly
     * without being able to *make* a slot occupied.
     *
     * Driving it from the outside is awkward for a real reason: the paths are derived from a wall-clock
     * id, so a test cannot predict what the next run will derive, and pre-creating a directory with the
     * right name is a race against the clock. The alternatives are worse in exactly the way this codebase
     * keeps running into — polling for a new directory to appear (races the check it is trying to observe)
     * or asserting the preflight predicate directly on a directory the test made up (tests the predicate,
     * not the refusal path). Both would leave the *behaviour* untested while looking green.
     *
     * So the question is delegated to the seam, and the seam is injectable. Production answers with
     * [File.exists] and nothing else; a test can answer "yes" for one path and reach the real refusal
     * branch through the real `restore()`.
     *
     * This deliberately replaces `File.exists()` rather than adding a separate hook, because a
     * `Boolean` return *is* the whole preflight — there is no second condition for a hook to drift away
     * from. [RealRestoreFs] answers `slot.exists()`, so production semantics are unchanged by
     * construction.
     *
     * @return `true` when [slot] may **not** be reused — i.e. the path is already occupied.
     */
    fun isSlotOccupied(slot: File): Boolean

    /**
     * Removes a finished restore's leftovers: both parked trees (`closetOldDir`/`mediaOldDir`), both
     * stage trees (`closetStageDir`/`mediaStageDir`), and the database snapshot. Throws on failure; the
     * marker is deliberately *not* touched here so a partial cleanup can resume.
     *
     * The stage trees are included because a failure between writing the marker and performing the swap
     * leaves them populated and nothing else owns them — see [RealRestoreFs.cleanupChecked]. Paths come
     * from the marker only; this must never glob or scan for candidate directories.
     *
     * Note the ordering guarantee this depends on: on the recovery path, evidence validation
     * ([missingRecoveryEvidence]) runs **before** any cleanup, because `mediaStageDir` is itself required
     * evidence for a media rollback. Deleting it here is only correct once nothing still needs to read it.
     */
    fun cleanupChecked(intent: RestoreIntent)
}

/**
 * The production implementation: real filesystem calls, each one verified after it returns.
 *
 * ### [park] is the one operation with no "nothing to do" branch, and that is the point
 *
 * `park` runs at exactly one moment — inside a **fresh** restore, immediately before the staged tree is
 * renamed into the live slot. At that instant the state of [live] is not an input to be discovered, it is
 * an invariant that the caller has already established: the restore read `live.exists()` when it built
 * the marker, and nothing but this call may have touched it since. So the *live* side has exactly two
 * legitimate cases, and both are decided by `existedBefore`.
 *
 * The `old` side is different, and is checked unconditionally: a free parking slot is a precondition of
 * the operation, not a consequence of the flag. [old] is the directory the revert path will later treat as
 * "the user's data", so an occupied slot — from a stale generation, or from anything else — must never be
 * reused or renamed into.
 *
 * The previous revision had a third branch. On `existedBefore == true` with [live] absent it *created* a
 * placeholder directory at [old] and returned, on the theory that this kept `old.exists()` meaningful for
 * the revert logic. It did the opposite. The placeholder is an **empty directory that is not the user's
 * data**, and the revert path treats `old.exists()` as "the parked original" — so recovery would have
 * dutifully renamed that empty placeholder over the live Closet and reported the user's wardrobe
 * restored. Fabricating safety evidence is worse than failing: a failed restore leaves the marker and the
 * real parked tree in place, while a fabricated one completes and deletes the evidence.
 *
 * A caller in that state is not something to paper over. Either [live] was removed under us (an external
 * process, a user clearing storage) or the restore's own bookkeeping is wrong; both mean the rename that
 * follows cannot produce a correct result, and the honest answer is to abort while the marker still
 * points at untouched data.
 */
internal object RealRestoreFs : RestoreFs {

    /**
     * The production answer: a path is occupied exactly when it exists. No globbing, no prefix scan —
     * the four paths are derived from an id and each one is asked about by name, because a scan would
     * turn "this generation's slot is free" into "no leftovers anywhere", which is a different and much
     * weaker statement.
     */
    override fun isSlotOccupied(slot: File): Boolean = slot.exists()

    override fun park(live: File, old: File, existedBefore: Boolean) {
        // ---- The parking slot must be free, whatever `existedBefore` says. -------------------------
        //
        // This is checked first and unconditionally, because `old.exists()` is *the* signal the revert
        // path trusts: `revertCloset` and `revertMedia` both open with "if the parked tree exists, it is
        // the user's data — rename it back". A stale `old` from an earlier generation is therefore not an
        // inert leftover; it is a directory that will be treated as the user's wardrobe the moment
        // anything looks at it.
        //
        // Only checking this on the `existedBefore == true` branch left a real resurrection window:
        //
        // ```
        //   existedBefore = false        (no live tree at restore start)
        //   old/ still present           (a stale directory from an earlier, finished restore)
        //   -> park is a no-op
        //   -> the staged tree is published as live
        //   -> a later rollback sees old/.exists() and renames the STALE generation over live/
        // ```
        //
        // The user would end up with data from two restores ago, and the marker would say the rollback
        // succeeded. A rename onto an existing directory would be equally wrong in the other direction —
        // `File.renameTo` moves the source *inside* the target rather than replacing it — so the only
        // safe answer is to refuse and leave the slot exactly as it was.
        if (old.exists()) {
            throw IOException("暂存目标已存在，拒绝合并两代数据: ${old.absolutePath}")
        }

        if (existedBefore) {
            // The caller's contract: `live` is the user's tree and must be moved aside intact. If it is
            // gone we cannot honour that, and inventing an empty stand-in would be recorded as the
            // user's data by the very revert logic that reads `old`.
            if (!live.exists()) {
                throw IOException(
                    "旧数据目录在暂存前已消失，无法安全暂存（不会伪造占位目录）: ${live.absolutePath}"
                )
            }
            rename(live, old, "无法暂存旧数据目录")
            return
        }
        // Nothing was there before, so there is nothing to preserve and nothing to rename. The marker
        // records `existedBefore = false`, and that flag — not an empty directory — is what tells the
        // revert path to delete the swapped-in tree instead of renaming something back.
        if (live.exists()) {
            throw IOException(
                "标记声明旧数据不存在，但目标目录仍然存在: ${live.absolutePath}"
            )
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

    /**
     * Removes a committed restore's leftovers: both parked trees, both stage trees, the database
     * snapshot, and the extraction scratch. Throws on failure; the marker is deliberately *not* touched
     * here so a partial cleanup can resume.
     *
     * ### Why the stage trees are part of the cleanup, and why they must be taken from the marker
     *
     * The previous revision deleted only `closetOldDir`, `mediaOldDir` and the snapshot. That left the
     * two staging trees behind in a specific, reachable case: **a failure after the marker was written
     * but before the swap consumed the stage tree**.
     *
     * ```
     *   PREPARING marker written, naming closetStageDir = .closie_restore_stage_<id>
     *   BackupImporter.stage() has already unpacked into that directory
     *   -> the archive is rejected by a later validator, or the process dies here
     *   -> `restore` fails, compensation reverts nothing (nothing was swapped)
     *   -> cleanupChecked deletes old/ (absent) and the snapshot (absent)
     *   -> .closie_restore_stage_<id>/ survives forever, holding a full copy of the user's wardrobe
     * ```
     *
     * The `restore` path's own `runCatching { closetStage.deleteRecursively() }` at the bottom does
     * remove it in the common case — but that is the *in-flight* path, best-effort, and it never runs at
     * all for a cleanup performed by a **later process start** from a `COMMITTED`/`ROLLED_BACK` marker.
     * A crash is precisely the situation the backup directory has to survive, and a crash is exactly what
     * skips that line. So cleanup owns them durably, here, alongside the trees it already owned.
     *
     * ### Where the paths come from
     *
     * From the marker, field by field — never by globbing `.closie_restore_stage_*` or scanning
     * `filesDir`. A glob would be a second, independent idea of "what belongs to a restore", and the
     * danger of a wrong guess here is not a stray file but deleting a directory a *live* restore is
     * using: a stage tree of the restore that is currently running is indistinguishable from an
     * abandoned one by name alone. The marker is the only authority on which paths this restore created,
     * and it is the same authority the revert paths already trust.
     *
     * Deleting a stage tree is always safe once the marker says the state is terminal-with-cleanup or
     * the restore has failed: `closetStageDir`/`mediaStageDir` are never the live trees. The live Closet
     * is `filesDir/closie` and the live media is `filesDir/media` — the stage directories are the
     * `.closie_restore_stage_*` / `.life_media_restore_stage_*` names, which the swap *moves out of*
     * before either becomes live.
     */
    override fun cleanupChecked(intent: RestoreIntent) {
        intent.closetOldDir?.let { deleteTree(File(it)) }
        intent.mediaOldDir?.let { deleteTree(File(it)) }
        // Marker-named stage trees. Present when the run failed before the swap consumed them, or when
        // the in-flight best-effort delete did not run (process death). Absent otherwise — `deleteTree`
        // is a no-op for an absent directory, which is what makes this safe to run unconditionally.
        intent.closetStageDir?.let { deleteTree(File(it)) }
        intent.mediaStageDir?.let { deleteTree(File(it)) }
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
 * The single recovery evidence check: refuses a marker that does not carry the paths the rollback is
 * about to use, *before* any surface is mutated.
 *
 * ### Why it has to run before everything else
 *
 * An earlier revision performed this check inside [recoverFilesystem], which meant the full recovery had
 * already read the snapshot and replayed it into the database by the time it fired. That is not
 * "fail closed before mutation" — the database had been mutated. The result was the exact half-restore
 * the check exists to prevent:
 *
 * ```
 *   DB      -> replayed to the user's rows
 *   Closet  -> still the backup's  (nothing reverted, because the check then aborted)
 *   media   -> unknown
 * ```
 *
 * with the marker still `DB_COMMITTING` so the next start would try again against a database that had
 * already been rolled back. So the check is now the **first** thing every recovery entry point does,
 * before the snapshot is read, before the replay, and before any rename or delete. When it fires:
 *
 *  - the database is untouched;
 *  - the Closet, the media and both parked trees are untouched;
 *  - the snapshot is untouched;
 *  - the marker is untouched.
 *
 * ### Why one function and not a check at each call site
 *
 * `recover`, `recoverFilesystemOnly` and `compensateRestore` all need the identical refusal with the
 * identical message. Three copies would be three places for the condition to be weakened — and the
 * failure mode of weakening it is silent data loss, which is not a bug that announces itself. Throwing
 * (rather than returning a verdict) is what lets all three callers inherit it without any of them having
 * to remember.
 *
 * @throws IOException naming the missing field, so the log says what to look for on disk.
 */
private fun validateRecoveryEvidence(intent: RestoreIntent) {
    val missing = intent.missingRecoveryEvidence() ?: return
    throw IOException(
        "恢复标记缺少恢复所需的证据字段（$missing），拒绝在证据不完整时改动任何数据: " +
            "state=${intent.state}, includesLifeOs=${intent.includesLifeOs}"
    )
}

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

/**
 * Reverts Life OS media, mirroring [revertCloset] for the media surface.
 *
 * The gate is [RestoreIntent.requiresLifeMediaRecovery] rather than `includesLifeOs`, because the flag
 * alone is wrong for a contradictory marker — see that predicate. Using the flag here while
 * [RestoreIntent.requiresDatabaseRecovery] ignored it would repair the database and the Closet and then
 * leave the media on the backup's tree, with the evidence deleted and the marker cleared.
 */
private fun revertMedia(context: Context, intent: RestoreIntent, fs: RestoreFs) {
    if (!intent.requiresLifeMediaRecovery()) return
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
 * Runs [compensateRestore] and, when it cannot finish, puts the whole process into the fail-closed
 * state — [RestoreStartupGate.markRestoreUnfinished] — instead of swallowing the failure.
 *
 * ### Why "the restore returned a failed Result" was never enough
 *
 * The previous revision wrote `runCatching { compensateRestore(...) }`. The `Result` was discarded, so
 * the only durable trace of a failed compensation was a log line, and the process carried on exactly as
 * if the restore had never happened. Concretely:
 *
 * ```
 *   Phase A swaps the Closet and the media, then fails before COMMITTED
 *   -> compensateRestore throws (a rename fails, the snapshot is unreadable, the DB is gone)
 *   -> runCatching eats it
 *   -> BackupManager.restore returns Result.failure(...)          -- the caller sees a failure
 *   -> ...and the app keeps running: ViewModels hold their repositories, the gate is still READY,
 *      and every one of those repositories reads and writes a Closet that is half-restored
 * ```
 *
 * The failure was reported to the person who pressed the button and to nobody else. Nothing stopped the
 * writes — which are the dangerous part, because a later successful recovery replays the pre-restore
 * snapshot and would silently erase whatever was written in the meantime.
 *
 * So the outcome now goes into the gate rather than into a discarded `Result`. See
 * [RestoreStartupGate.markRestoreUnfinished] for what that commits to; in short:
 *
 *  1. **The evidence stays.** A failing compensation has deleted nothing — it validates the marker
 *     before touching anything and writes its terminal state before cleaning up — so the marker, the
 *     snapshot and the parked trees are all still on disk for the next start.
 *  2. **This process stops doing business.** Including through repository instances that were
 *     constructed *before* the failure, which is why the repositories consult the gate per call
 *     ([RestoreStartupGate.gated]) rather than only in a constructor.
 *  3. **The next start repairs it.** [RestoreRecoveryManager.recoverOnStartup] finds the surviving
 *     marker and finishes.
 *
 * ### Why no new orchestration layer
 *
 * The obvious-looking fix is a `RestoreService`/`RestoreEngine` that "owns" restore state and that every
 * caller must ask. That would be a second owner of one protocol: the startup barrier and the in-process
 * restore would each have to agree with it, and any disagreement becomes a third behaviour. The gate
 * already answers exactly one question that *every* data path needs answered — "may I touch data?" — so
 * the minimal correct change is to route the failure into that existing question instead of inventing a
 * new one.
 *
 * The caller still receives a failed `Result`: this function does not turn a failed restore into a
 * successful one, it only stops the failure from being cosmetic.
 */
private suspend fun compensateOrFailClosed(
    context: Context,
    intent: RestoreIntent,
    lifeDatabase: LifeDatabase?,
    fs: RestoreFs
) {
    // `fs` is threaded through rather than defaulting to [RealRestoreFs] inside `compensateRestore`.
    // It is the same filesystem the restore itself just used, so a restore that failed *because* a
    // filesystem operation fails does not compensate through a different, working one — which would
    // silently "succeed" the very step that failed, clear the marker and leave the surfaces disagreeing.
    val compensated = runCatching { compensateRestore(context, intent, lifeDatabase, fs) }
    if (compensated.isSuccess) {
        // The rollback finished: every durable surface is verifiably back on the pre-restore version, so
        // the process is as safe to use as it was before the user pressed the button. The gate is
        // reopened here rather than by [BackupManager]'s `finally`, because only this function knows that
        // the *data* was actually put back — the coordinator owns that verdict, exactly as it does on the
        // success path.
        RestoreStartupGate.endRestoreReady()
        return
    }

    val error = compensated.exceptionOrNull()!!
    // The compensation is the *only* thing standing between this process and a half-restored data set,
    // and it did not finish. Everything below is therefore on the evidence-preserving path: nothing here
    // deletes the marker, the snapshot or the parked trees, because they are what the next start needs.
    //
    // Note what is deliberately *not* attempted: closing the gate is not enough on its own (repositories
    // already constructed would keep going), which is why the gate's refusal is consulted per call — see
    // [RestoreStartupGate.gated] and [RestoreStartupGate.gateAwareFlow]. And reopening later is not
    // possible: the gate records this as a one-way door, so no stray `markReady` can undo it.
    android.util.Log.e(
        "RestoreCoordinator",
        "恢复补偿未完成：本次进程将拒绝一切业务读写，证据（标记/快照/暂存目录）全部保留，下次启动继续恢复",
        error
    )
    RestoreStartupGate.markRestoreUnfinished(error)
}

/**
 * Compensates a failed restore.
 *
 * ### The invariant, stated once and shared with recovery
 *
 * `compensateRestore` is not a second, looser version of [recoverFilesystem] — it is the same protocol
 * run from the other direction, and it must agree with it on every question that decides whether the
 * user's data survives. The rule that both obey is:
 *
 * > **The marker is only advanced and cleared once every durable half is verifiably back on the
 * > pre-restore version.** Until then the marker keeps its last *honest* state, and the evidence it
 * > points at — the parked trees and the snapshot — is never deleted.
 *
 * It also obeys the **same ordering** as the startup path: validate evidence → replay the database →
 * revert the filesystem → write the terminal state → clean up → clear. Two entry points into one
 * protocol, not one protocol plus a looser variant; a compensation path that reverted the filesystem
 * before replaying the database would leave the surfaces on different versions with the parked evidence
 * already consumed, and the next start would have nothing left to repair from.
 *
 * Four specific ways the previous revision broke that rule, all of them silent:
 *
 *  1. **The database half was skipped, not failed.** The guard was
 *     `requiresDatabaseRecovery() && dbSnapshot != null && lifeDatabase != null`, so a marker that
 *     *needed* a replay but named no snapshot fell straight through to the cleanup below. The cleanup
 *     deletes the parked trees and the marker — over a database that may still hold the backup's rows.
 *     A missing snapshot on a marker that requires one is a contradiction, not a licence to proceed, and
 *     it is now a hard failure that preserves everything.
 *  2. **The terminal state was persisted after the cleanup.** Writing `ROLLED_BACK`/`FAILED` and then
 *     clearing the marker means a crash in between leaves the *evidence already deleted* with a marker
 *     claiming the rollback succeeded. The state must be durable **once the rollback is complete and
 *     before anything is deleted** — that is what makes a resume safe, because a resumed pass reads
 *     `ROLLED_BACK`/`FAILED` and knows the rollback is done, so all that is left is cleanup.
 *  3. **The cleanup hand-rolled its own deletes** while [RestoreFs.cleanupChecked] already existed and is
 *     verified. Two implementations of "delete the leftovers" is how one of them ends up unchecked.
 *  4. **The database half ran last.** The order was `revertCloset` → `revertMedia` → `restoreSnapshot`,
 *     the exact inverse of [RestoreCoordinator.recover]. A failed or impossible replay therefore landed
 *     *after* the parked trees had been consumed, leaving Closet/media rolled back and the database on
 *     the backup's rows with no evidence left to finish the job.
 *
 * ### Why ROLLED_BACK / FAILED are written before the cleanup, and what each means
 *
 * [RestoreState.FAILED] claims "nothing durable was ever published", which is true only before the first
 * swap — that is exactly what [RestoreIntent.swapInScope] tests. Past that point the honest terminal state
 * is [RestoreState.ROLLED_BACK], because the Closet (and possibly the media) *were* published and have now
 * been put back. Both states are read by [recoverFilesystem] as "rollback already done, only cleanup
 * remains", which is only a truthful reading if the rollback really did finish first — hence the order.
 *
 * @throws Throwable from any revert or cleanup step. The caller swallows it; the point is that the marker
 *   and the evidence are intact when this returns abnormally, so the next start retries.
 */
private suspend fun compensateRestore(
    context: Context,
    intent: RestoreIntent,
    lifeDatabase: LifeDatabase?,
    fs: RestoreFs = RealRestoreFs
) {
    // ---- 0. Evidence first: is this marker actionable at all? --------------------------------------
    //
    // Same shared check, same position as in [RestoreCoordinator.recover] — before the snapshot is read,
    // before the replay, before any rename. See `validateRecoveryEvidence`.
    validateRecoveryEvidence(intent)

    // ---- 1. Materialise the database evidence, then replay it — BEFORE the filesystem revert. -------
    //
    // The ordering here is deliberately identical to [RestoreCoordinator.recover], not merely similar.
    // The previous revision ran `revertCloset`/`revertMedia` first and only then replayed the snapshot,
    // which is the same defect the full recovery had and it produces the same half-restore:
    //
    // ```
    //   revert Closet + media   -> parked trees CONSUMED, surfaces on the user's version
    //   replay snapshot         -> throws (or the database is unavailable)
    //   result: Closet/media = old, DB = backup, and no parked evidence left to finish with
    // ```
    //
    // Replaying first is safe because it is **idempotent**: `LifeBackupApplier.restoreSnapshot` deletes
    // and re-inserts inside one transaction, so if the filesystem revert that follows fails, the next
    // pass simply replays the same snapshot again. The reverse is not true — a consumed parked tree
    // cannot be re-parked — which is why the reversible step goes first.
    val snapshot = if (intent.requiresDatabaseRecovery()) {
        val snapPath = intent.dbSnapshot
            ?: throw IOException("标记要求回滚数据库，但没有记录快照路径，拒绝在无证据的情况下继续")
        when (val r = LifeBackupApplier.readSnapshot(File(snapPath))) {
            is AtomicJson.ReadResult.Success -> r.value
            // Corrupt or absent: abort with every surface untouched, rather than half-reverting over a
            // database that cannot be replayed.
            else -> throw IOException("恢复快照不可用，无法回滚数据库: $snapPath")
        }
    } else {
        null
    }

    if (snapshot != null && lifeDatabase != null) {
        LifeBackupApplier.restoreSnapshot(lifeDatabase, snapshot)
    } else if (snapshot != null) {
        // The evidence exists and is readable, but there is no database to apply it to. This is the one
        // case where the protocol genuinely cannot finish, and it must not delete the snapshot: the next
        // start — with a database — is what completes it. Throwing here keeps the marker at its last
        // honest state and the parked trees in place.
        throw IOException("数据库不可用，无法回滚 Life OS 数据；已保留标记与快照")
    }

    // ---- 2. Revert both filesystem surfaces. -------------------------------------------------------
    revertCloset(context, intent, fs)
    revertMedia(context, intent, fs)

    // ---- 4. Persist the terminal state: the rollback is complete, the cleanup is not yet done. -----
    //
    // Shared with the startup recovery path via `persistTerminalAfterRollback`, so the two cannot drift
    // into different orders. FAILED means "nothing durable was ever published", which is true only before
    // the first swap; past that point the honest terminal state is ROLLED_BACK. Writing it *before* the
    // cleanup is what stops a crash here from leaving a `DB_COMMITTED` marker over a snapshot the cleanup
    // already deleted — the permanent deadlock that terminal states exist to prevent.
    persistTerminalAfterRollback(context, intent)
    val terminal = terminalStateAfterRollback(intent)

    // ---- 5. Cleanup last, and checked. -------------------------------------------------------------
    //
    // A crash anywhere above leaves a marker that still names the evidence; a crash here leaves a marker
    // saying `ROLLED_BACK`, which recovery reads as "just finish the cleanup". Only after both are done
    // is the marker removed — and `clearChecked` proves it, so the caller's "compensation finished"
    // verdict cannot be reported over a marker that is still on disk.
    fs.cleanupChecked(intent.copy(state = terminal))
    RestoreIntentStore.clearChecked(context)
}
