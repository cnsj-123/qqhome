package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaStoreImporter
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.repository.ReferenceRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The restore commit boundary, tested at the points where it used to break.
 *
 * ### What went wrong before
 *
 * Restore ran `parse → validate → applyLifePayload() → Room commit → Closet swap → health check`. The
 * Room commit and the directory swap are two commits over two resources with no shared transaction, so
 * every failure between them produced the same end state:
 *
 * ```
 * Life DB = backup version
 * Closet  = old version
 * ```
 *
 * and the old `LifeRestoreSnapshot.rollback()` could not fix it, because it only knew how to put back
 * *managed media files*. It had nothing to say about rows that had already committed.
 *
 * ### What these tests hold the code to
 *
 * Each test fails a specific stage, then asserts on **all three** surfaces at once — the Closet JSON,
 * the Life OS database, and the Life OS media directory. Asserting on one surface is what let the
 * original bug through: the restore reported success, so a test that only checked the return value
 * passed while the user's data was split across two versions.
 *
 * The failures are injected through [RestoreHooks], not by contriving filesystem errors. A hook makes
 * the failure land at *exactly* the stage under test and keeps the shipping code path unchanged —
 * there is no `testMode` branch anywhere in the production code for these tests to accidentally take.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var media: MediaRepository
    private lateinit var captureRepo: CaptureRepository
    private lateinit var referenceRepo: ReferenceRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var wardrobe: LocalWardrobeRepository

    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        // `setUp` constructs a `LocalWardrobeRepository`, and constructing one is gated: the repository
        // refuses to open while [RestoreStartupGate] is blocked. Robolectric also runs
        // `ClosieApplication.onCreate`, which resolves the gate for real (no marker ⇒ READY). Marking
        // the gate ready here keeps the tests in this class about restore mechanics rather than about
        // the startup barrier, which has its own dedicated test classes.
        RestoreStartupGate.markReady()

        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        media = MediaRepository(db)
        captureRepo = CaptureRepository(db)
        referenceRepo = ReferenceRepository(db, life, media, captureRepo)
        planRepo = PlanRepository(db, life)
        wardrobe = LocalWardrobeRepository(context)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        createdUris.forEach { runCatching { File(it.path!!).delete() } }
        // Reset on the way out so the *next* class starts from a fresh process too: the gate is
        // process-wide state that would otherwise leak into whichever test class Robolectric loads next.
        RestoreStartupGate.resetForTesting()
    }

    // ------------------------------------------------------------------
    //  Failure injection
    // ------------------------------------------------------------------

    /**
     * Fails in the window the redesign exists to close: the database has committed, and a filesystem
     * step afterwards fails. Before the intent log this left `Life DB = backup`, `Closet = old` with no
     * way back, because the old undo record only knew about media files.
     */
    private fun failAfterDbCommit(): RestoreHooks = object : RestoreHooks {
        override fun afterDbCommit() = throw IllegalStateException("injected: filesystem step after db commit")
    }

    /** Fails the post-swap health check by corrupting the live `closie/` so validation fails. */
    private fun failHealthCheck(): RestoreHooks = object : RestoreHooks {
        override fun beforeHealthCheck() {
            // Corrupt the swapped-in directory so validateDataDirectory() returns false. This is the
            // "swap succeeded, health check fails" scenario: the Closet is already the backup's version.
            File(File(context.filesDir, "closie"), "items.json").writeText("{ not json ]")
        }
    }

    // NOTE: media is now staged into a private directory and fully validated *before* any durable
    // mutation, so there is no mid-copy failure surface to inject a hook into. The corresponding
    // regression (a backup missing its media file) is covered by
    // [missingMediaFile_failsBeforeAnyDurableMutation], which fails during staging and therefore
    // before the Closet swap, the database transaction, or any byte of live data is touched.

    // ------------------------------------------------------------------
    //  Regression 1: filesystem failure after the database commit
    // ------------------------------------------------------------------

    @Test
    fun dbCommitThenFilesystemFailure_leavesAllThreeSurfacesOnTheOldVersion() = runTest {
        val (backup, oldMedia) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-1.png"),
            oldBytes = listOf(byteArrayOf(1, 2, 3))
        )

        val result = BackupManager.restore(context, wardrobe, backup, db, failAfterDbCommit())
        assertThat(result.isSuccess).isFalse()

        // The three surfaces must agree — and they must agree on the *old* version.
        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = oldMedia
        )
    }

    // ------------------------------------------------------------------
    //  Regression 2: health check failure after the swap
    // ------------------------------------------------------------------

    @Test
    fun healthCheckFailureAfterSwap_revertsClosetAndDatabaseTogether() = runTest {
        val (backup, oldMedia) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-2.png"),
            oldBytes = listOf(byteArrayOf(7, 7, 7))
        )

        val result = BackupManager.restore(context, wardrobe, backup, db, failHealthCheck())
        assertThat(result.isSuccess).isFalse()

        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = oldMedia
        )
        // No parked tree may survive: the revert is only complete once `oldDir` is consumed.
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  Regression 2a-bis: a *pre-durable* refusal must not sticky-block
    // ------------------------------------------------------------------

    /**
     * A stale parking slot refuses the restore **as a `Result.failure`**, leaving the gate `READY` and
     * every surface untouched.
     *
     * ### Why this needed its own test
     *
     * The preflight that rejects a stale `.closie_restore_old_*` slot is deliberately the *first* thing
     * `restore()` does — before `intent` exists and before any marker is written — because the danger it
     * prevents is a *later* check: once a marker names that path, `revertCloset` treats an existing parked
     * tree as the user's data and renames it over the live Closet.
     *
     * Being first is also what made it easy to get the *error handling* wrong. Because it sits before
     * `val phaseA = runCatching { … }`, a throw there does not reach the coordinator's own verdict logic
     * at all — it escapes to [BackupManager]'s outer `catch`, whose single verdict is
     * `markRestoreUnfinished`. That verdict is right for "something threw that I cannot reason about" and
     * exactly wrong here, where the code can *prove* nothing durable happened:
     *
     * ```
     *   stale slot -> outer catch -> markRestoreUnfinished (sticky, restart-required)
     *                              and no marker exists for the next start to clean up
     *   -> the app refuses every read and write, forever, because of a leftover directory in cacheDir
     * ```
     *
     * The whole point of the three-surface protocol is that a refusal costing nothing must cost nothing.
     * So the assertions here are the complete "nothing happened" ledger, not just the return value:
     *
     * | surface | expected |
     * |---|---|
     * | return | `Result.failure`, not a thrown exception |
     * | marker | `Missing` — the refusal is pre-marker, so there is no evidence to keep |
     * | Closet | the user's own `local-item-only`, in memory *and* on disk |
     * | media | the user's own media bytes, and the stale slot's own file |
     * | database | the user's own capture row, unchanged |
     * | stale slot | **preserved** — it is the evidence; only this run's scratch may be deleted |
     * | gate | `READY`, and business code genuinely works again |
     */
    @Test
    fun staleParkingSlot_refusesAsFailureWithoutStickyBlockingOrTouchingAnything() = runTest {
        val (backup, oldMedia) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-stale-slot.png"),
            oldBytes = listOf(byteArrayOf(5, 5, 5))
        )

        val filesDir = context.filesDir

        // A leftover parked tree, holding the *previous* generation's wardrobe. It is placed on a real
        // `.closie_restore_old_*` path because that is what a genuinely interrupted earlier run leaves
        // behind, and it is asserted to survive below — deleting it would destroy the user's old wardrobe.
        val staleClosetOld = File(filesDir, ".closie_restore_old_stale-generation")
        staleClosetOld.deleteRecursively()
        writeWardrobeFiles(
            staleClosetOld,
            itemsJson = """[{"id":"stale-generation-item","name":"上一次恢复的残留"}]"""
        )

        // ### Reaching the real refusal branch, honestly
        //
        // The preflight derives its four candidate paths from a wall-clock id, so a test cannot
        // pre-create the exact directory a run will look for. Rather than racing the clock, the seam
        // answers the *question* — "is this slot taken?" — and this override answers it for the parking
        // slot while delegating everything else to production. The refusal therefore runs through the
        // real `restore()`: same branch, same cleanup, same gate handling.
        val preflightRefusalFs = object : DelegatingRestoreFs() {
            override fun isSlotOccupied(slot: File): Boolean =
                slot.name.startsWith(".closie_restore_old_") || RealRestoreFs.isSlotOccupied(slot)
        }

        // --- The complete "nothing happened" ledger, captured before the attempt. ---
        val closetFile = File(filesDir, "closie/items.json")
        val closetBefore = closetFile.readText()
        val mediaBefore = oldMedia.map { path -> File(path).readBytes().toList() }
        val captureCountBefore = db.captureDao().count()
        val staleItemsBefore = File(staleClosetOld, "items.json").readText()
        assertThat(mediaBefore).isNotEmpty()

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = preflightRefusalFs
        )

        // 1. A `Result.failure`, **not** an escaping exception. The distinction is the whole point: a
        //    throw out of the preflight reaches `BackupManager`'s outer `catch`, whose only verdict is the
        //    sticky restart-required block — for a leftover directory that the next start would have
        //    cleaned up anyway.
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)

        // 2. No marker: the refusal happened before `RestoreIntent` existed, so there is no evidence to
        //    preserve and nothing for a later start to resume.
        assertThat(markerMissing()).isTrue()

        // 3. The Closet is the user's, in memory and on disk.
        assertThat(wardrobe.items.value.map { it.id }).contains(OLD_ITEM_ID)
        assertThat(wardrobe.items.value.map { it.id }).doesNotContain(NEW_ITEM_ID)
        assertThat(closetFile.readText()).isEqualTo(closetBefore)

        // 4. The media tree is untouched, byte for byte.
        oldMedia.forEachIndexed { i, path ->
            assertThat(File(path).readBytes().toList()).isEqualTo(mediaBefore[i])
        }

        // 5. The database is untouched.
        assertThat(db.captureDao().count()).isEqualTo(captureCountBefore)

        // 6. The stale parking slot is **preserved**. This is what separates "refused safely" from
        //    "refused and deleted the evidence": the directory holds the user's previous wardrobe, and a
        //    refusal that cleaned it up would be the very data loss the preflight exists to prevent.
        assertThat(staleClosetOld.exists()).isTrue()
        assertThat(File(staleClosetOld, "items.json").readText()).isEqualTo(staleItemsBefore)

        // 7. The gate is genuinely READY — not sticky BLOCKED. Both halves are asserted, because a gate
        //    that claimed READY while still refusing leases would be the same broken app wearing a
        //    different label.
        assertThat(RestoreStartupGate.isRestoreUnfinished).isFalse()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        RestoreStartupGate.endRestoreReady()

        // 8. Business code genuinely works again — the positive control that makes 7 meaningful.
        assertThat(wardrobe.listItems().map { it.id }).contains(OLD_ITEM_ID)
    }

    // ------------------------------------------------------------------
    //  Regression 2b: cleanup failure *after* the commit point
    // ------------------------------------------------------------------

    /**
     * A cleanup failure after `COMMITTED` must **not** roll anything back.
     *
     * This is the single most destructive shape of failure the old code could produce, and it is
     * subtle because it only appears once the commit point is understood as a boundary.
     *
     * `requiresDatabaseRecovery()` is false for [RestoreState.COMMITTED] — by then the database
     * transaction has succeeded and there is nothing left to replay. So if a post-commit cleanup
     * failure were routed into `compensateRestore`, compensation would revert the Closet *and* the
     * media while leaving the database exactly as the backup left it. The result is a database from
     * one version with directories from another: strictly worse than either, and — because the marker
     * says `COMMITTED` — a state that recovery would afterwards treat as finished and clean up, making
     * the disagreement permanent.
     *
     * The contract asserted here: once `COMMITTED` is durable, the restore has *succeeded*, the three
     * surfaces all hold the **backup's** version, and cleanup failure is merely untidy.
     *
     * The failure is injected on the *parked Closet tree*, which only ever gets deleted from Phase B:
     * by the `COMMITTED` stage the swap has long consumed the stage directory, so the only tree named
     * `closie/` is the live one, and the only suffix-matched tree is the parked one.
     */
    @Test
    fun committedClosetCleanupFailure_neverRollsBackAndKeepsTheCommit() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-committed-1.png"),
            oldBytes = listOf(byteArrayOf(3, 1, 4))
        )

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = FailingRestoreFs(failDeleteContaining = "closie_restore_old_")
        )

        // Success: committed work is not undone by a failure to tidy up.
        assertThat(result.isSuccess).isTrue()

        // The commit stands: all three surfaces are the *backup's* version, not the old one.
        assertCommittedConsistent()
        assertThat(wardrobe.items.value.map { it.id }).contains(NEW_ITEM_ID)
        assertThat(wardrobe.items.value.map { it.id }).doesNotContain(OLD_ITEM_ID)
    }

    /**
     * The same contract for the *media* cleanup step.
     *
     * Split out from the Closet case deliberately: the two cleanups are separate filesystem trees and
     * a fix that only guards one of them would pass the test above. Both must be non-compensating.
     */
    @Test
    fun committedMediaCleanupFailure_neverRollsBackAndKeepsTheCommit() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-committed-2.png"),
            oldBytes = listOf(byteArrayOf(2, 7, 1))
        )

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = FailingRestoreFs(failDeleteContaining = "media_restore_old_")
        )

        assertThat(result.isSuccess).isTrue()
        assertCommittedConsistent()
        assertThat(wardrobe.items.value.map { it.id }).contains(NEW_ITEM_ID)
    }

    /**
     * A cleanup failure that also prevents the marker from being cleared must leave the next start with
     * only cleanup to do — never a restore to repeat.
     *
     * This pins the "resumable, not repeatable" property. If a post-commit failure cleared the marker
     * or rewrote it to an earlier state, a later start would either redo a committed restore (wasted
     * work at best) or, worse, treat the state as pre-commit and roll it back. The snapshot deletion is
     * the one cleanup step whose failure leaves the marker readable as `COMMITTED`, so it is the natural
     * probe: the next start must observe a committed state and simply finish the housekeeping.
     */
    @Test
    fun committedCleanupFailure_markerStaysAtOrPastTheCommitPoint_soTheNextStartOnlyCleansUp() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-committed-3.png"),
            oldBytes = listOf(byteArrayOf(9, 9))
        )

        BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = FailingRestoreFs(failDeleteContaining = "closie_restore_old_")
        )

        // Whatever the marker says now, it must never describe a *pre-commit* state, because the
        // database has provably already taken the backup's rows.
        val marker = RestoreIntentStore.read(context)
        if (marker is AtomicJson.ReadResult.Success) {
            assertThat(marker.value.state).isAnyOf(
                RestoreState.COMMITTED,
                RestoreState.HEALTH_CHECKING,
                RestoreState.DB_COMMITTED
            )
        }

        // A later start, with cleanup working again, finishes the job and converges to one version.
        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { db })
        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertCommittedConsistent()
        // Never rolled back to the user's pre-restore state.
        assertThat(wardrobe.items.value.map { it.id }).contains(NEW_ITEM_ID)
        assertThat(RestoreStartupGate.isReady).isTrue()
    }

    // ------------------------------------------------------------------
    //  Regression 3: media staging copy interruption
    // ------------------------------------------------------------------

    /**
     * A v2 archive whose media file is missing from the package must fail *during staging* — before the
     * Closet swap, before the database transaction, before any byte of live data is touched.
     *
     * The new design stages media into a private directory and validates every file's presence (and, when
     * recorded, its size/sha256) before publishing, so a corrupt or truncated backup can never half-restore.
     * The old capture, the old Closet item, and the live media file must all survive byte-for-byte.
     */
    @Test
    fun missingMediaFile_failsBeforeAnyDurableMutation() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        wardrobe.createItem(
            com.qq.closie.data.model.ClothingItem(
                id = OLD_ITEM_ID,
                name = "用户原有的衣服",
                category = "下装",
                status = com.qq.closie.data.model.ItemStatus.OWNED
            )
        )
        val liveMedia = writeManagedMedia("live-shot.png", byteArrayOf(1, 2, 3))

        // Build a v2 payload that references a media file, then write the archive WITHOUT that file.
        val snapshot = LifeBackupApplier.snapshot(db)
        val payload = snapshot.copy(
            mediaResources = snapshot.mediaResources + MediaResourceRecord(
                row = MediaResourceEntity(
                    id = "bk-res",
                    mediaAssetId = "bk-asset",
                    role = MediaResourceRole.ORIGINAL,
                    mimeType = "image/png",
                    createdAt = 1L
                ),
                archiveFileName = "bk-shot.png"
            )
        )
        val backup = outputUri("missing-media")
        writeV2Archive(backup, payload, mediaFiles = emptyMap())

        val result = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(result.isSuccess).isFalse()

        // Nothing durable moved: the old capture and Closet survive, and the live media file is
        // byte-identical — the staging failure never reached the publish step.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains(OLD_CAPTURE_TEXT)
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(File(liveMedia).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * A backup whose media entry names a path *outside* the media directory must be refused during
     * staging, before anything is written.
     *
     * `archiveFileName` comes straight out of an untrusted ZIP, and the naive
     * `File(mediaStageDir, archiveFileName)` resolves `../` segments — so an archive could direct the
     * write anywhere `filesDir` reaches, including the live database directory. Every case below is a
     * real escape shape rather than a fuzzed string, and each must fail without creating a file.
     */
    @Test
    fun maliciousArchiveFileName_isRefusedDuringStaging() = runTest {
        val escapes = listOf(
            "../evil.jpg",           // one level up: would land in filesDir
            "../../closie/items.json", // into the live wardrobe itself
            "/foo.jpg",              // absolute: would discard the stage dir entirely
            "a/b.jpg",               // nested, so the write target is not the stage dir
            "a\\b.jpg",              // backslash variant of the same
            ".."                     // the parent directory itself
        )

        escapes.forEach { name ->
            val snapshot = LifeBackupApplier.snapshot(db)
            val payload = snapshot.copy(
                mediaResources = listOf(
                    MediaResourceRecord(
                        row = MediaResourceEntity(
                            id = "esc-res",
                            mediaAssetId = "esc-asset",
                            role = MediaResourceRole.ORIGINAL,
                            mimeType = "image/png",
                            createdAt = 1L
                        ),
                        archiveFileName = name
                    )
                )
            )
            val backup = outputUri("escape-/${name.hashCode()}")
            writeV2Archive(backup, payload, mediaFiles = mapOf(name to byteArrayOf(1, 2, 3)))

            val result = BackupManager.restore(context, wardrobe, backup, db)
            assertThat(result.isSuccess).isFalse()

            // The decisive assertion: nothing was written outside the staging scratch. A traversal
            // attempt that "failed" after writing would still have damaged the user's data.
            assertThat(File(context.filesDir, "evil.jpg").exists()).isFalse()
            assertThat(File(context.filesDir, "foo.jpg").exists()).isFalse()
            assertThat(File(File(context.filesDir, "a"), "b.jpg").exists()).isFalse()
            // The live wardrobe is byte-identical — a name pointing at `closie/items.json` must not
            // have overwritten it.
            assertThat(File(File(context.filesDir, "closie"), "items.json").exists()).isFalse()
        }
    }

    // ------------------------------------------------------------------
    //  Repository memory must not run ahead of the commit point
    // ------------------------------------------------------------------

    /**
     * A pre-commit failure must leave the repository's **in-memory** state on the old version too.
     *
     * This is the regression for publishing the backup into memory at the health-check stage. The
     * restore swaps `closie/` and then health-checks it; if a failure there rolls the *disk* back while
     * memory already holds the backup's items, the running UI shows a wardrobe that exists nowhere on
     * disk. The next write from that stale UI then persists backup-derived data over the user's own.
     *
     * The fix is ordering: memory is only republished after the `COMMITTED` marker is durable. This
     * test fails the health check — the last pre-commit step — and asserts that both the disk *and* the
     * observable repository state are the old version.
     */
    @Test
    fun preCommitFailure_leavesRepositoryMemoryOnTheOldVersion() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-memory.png"),
            oldBytes = listOf(byteArrayOf(5, 5, 5))
        )
        // The repository's observable state before the restore, taken from the live repository itself
        // rather than assumed, so the assertion is about what a composable would actually have seen.
        assertThat(wardrobe.items.value.map { it.id }).containsExactly(OLD_ITEM_ID)

        val result = BackupManager.restore(context, wardrobe, backup, db, failHealthCheck())
        assertThat(result.isSuccess).isFalse()

        // Disk is the old version…
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        // …and so is memory. The two must never disagree: that disagreement is the bug.
        assertThat(wardrobe.items.value.map { it.id }).containsExactly(OLD_ITEM_ID)
        assertThat(wardrobe.items.value.map { it.id }).doesNotContain(NEW_ITEM_ID)
    }

    /**
     * The positive control for the rule above: after a *successful* restore the repository must
     * actually show the restored wardrobe.
     *
     * Without this, the test above would pass on a repository that simply never updates — which would
     * be a different bug wearing the same green tick.
     */
    @Test
    fun committedRestore_publishesTheNewWardrobeIntoTheRepository() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-memory-ok.png"),
            oldBytes = listOf(byteArrayOf(4, 4))
        )

        val result = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(result.isSuccess).isTrue()

        assertThat(wardrobe.items.value.map { it.id }).contains(NEW_ITEM_ID)
        assertThat(wardrobe.items.value.map { it.id }).doesNotContain(OLD_ITEM_ID)
    }

    // ------------------------------------------------------------------
    //  Recovery of an interrupted run, without any database involvement
    // ------------------------------------------------------------------

    @Test
    fun startupRecovery_afterFilesSwapped_revertsClosetAndLeavesDatabaseAlone() = runTest {
        seedCapture("恢复时必须原样保留")

        // Simulate a process killed after the swap: `closie/` holds the backup's version and the old
        // tree is parked, exactly as the marker records.
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_999")
        val stageDir = File(filesDir, ".closie_restore_stage_999")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"old-item","name":"旧衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"new-item","name":"备份里的衣服"}]""")
        stageDir.mkdirs()
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 999,
                state = RestoreState.CLOSET_SWAPPED,
                closetOldDir = oldDir.absolutePath,
                closetStageDir = stageDir.absolutePath
            )
        )

        RestoreCoordinator.recoverFilesystemOnly(context)

        // Closet rolled back to the user's tree…
        val items = File(closieDir, "items.json").readText()
        assertThat(items).contains("旧衣服")
        assertThat(items).doesNotContain("备份里的衣服")
        // …staging and marker are gone…
        assertThat(stageDir.exists()).isFalse()
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
        // …and the database was never touched, because at CLOSET_SWAPPED the final data transition
        // had not been taken yet.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains("恢复时必须原样保留")
    }

    /**
     * The marker can lag the filesystem by one step. If the process dies after `closie/` was renamed
     * away but before the marker was updated to [RestoreState.CLOSET_SWAPPED], recovery must still revert
     * rather than delete the parked copy — the parked copy is the user's only remaining data.
     */
    @Test
    fun startupRecovery_whenMarkerLagsBehindTheSwap_stillReverts() = runTest {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_777")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"old-item","name":"旧衣服"}]""")
        // `closie/` is gone — the rename succeeded but the marker was never rewritten to the swapped
        // state. The marker still records `closetOldDir`, exactly as the live restore writes it before
        // the swap, so recovery can find and restore the parked tree.
        if (closieDir.exists()) closieDir.deleteRecursively()
        RestoreIntentStore.write(
            context,
            RestoreIntent(id = 777, state = RestoreState.STAGED, closetOldDir = oldDir.absolutePath)
        )

        RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(File(closieDir, "items.json").readText()).contains("旧衣服")
        assertThat(oldDir.exists()).isFalse()
    }

    // ------------------------------------------------------------------
    //  DB_COMMITTING: the in-flight database window
    // ------------------------------------------------------------------

    /**
     * A process killed while the database transaction was in flight must be recovered exactly like one
     * killed after it returned.
     *
     * `DB_COMMITTING` means "the snapshot is saved, the transaction may or may not have committed" —
     * nothing on disk can tell the two apart, so recovery has to assume the pessimistic case. It
     * replays the snapshot, which is idempotent, so the cost of being wrong is one no-op transaction.
     * The alternative — treating the window as "not committed" and only reverting the Closet — would
     * leave the user with an old wardrobe and a new life-graph whenever the transaction *had* landed.
     */
    @Test
    fun startupRecovery_fromDbCommitting_revertsBothHalvesAndIsIdempotent() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)

        // Simulate a kill inside the transaction: the marker says DB_COMMITTING and the snapshot on
        // disk holds the rows the user had before the restore, but the database itself already holds
        // the backup's rows (the pessimistic case the state exists to cover).
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_555")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // The snapshot: exactly the user's pre-restore rows.
        val snapshot = LifeBackupApplier.snapshot(db)
        val snapFile = File(filesDir, ".closie_restore_dbsnap_555.json")
        snapFile.writeText(com.google.gson.Gson().toJson(snapshot))

        // Pretend the transaction committed: the database now holds the backup's rows.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 555,
                state = RestoreState.DB_COMMITTING,
                // A `DB_COMMITTING` marker is reached only through the v2 branch that writes the
                // snapshot and opens the transaction, so the flag and the parked tree must be present
                // for this to describe a state a real device can actually be in.
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                // A v2 marker names both media paths whether or not the device had a media directory;
                // here it had none, which is what `mediaExistedBefore` (default false) records.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_555").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_555").absolutePath,
                dbSnapshot = snapFile.absolutePath
            )
        )

        RestoreCoordinator.recover(context, db)

        // Both halves are the user's again.
        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = emptyList()
        )
        assertThat(strayRestoreDirs()).isEmpty()
        assertThat(markerMissing()).isTrue()

        // …and recovery is idempotent: running it again must be a no-op, not a second mutation.
        val after = db.captureDao().getAllOnce().map { it.rawText }
        RestoreCoordinator.recover(context, db)
        assertThat(db.captureDao().getAllOnce().map { it.rawText })
            .containsExactlyElementsIn(after)
            .inOrder()
    }

    /**
     * The same state, but with no database available — the case a component that has no Life OS
     * container would hit. The Closet is still repaired, and the marker is *kept* so a later start with
     * a database can finish the other half.
     */
    @Test
    fun startupRecovery_fromDbCommitting_withoutDatabase_repairsClosetAndKeepsMarker() = runTest {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_444")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val snapFile = File(filesDir, ".closie_restore_dbsnap_444.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 444,
                state = RestoreState.DB_COMMITTING,
                // Same reasoning as the test above: the state implies a v2 restore, and the parked tree
                // really is on disk, so the marker must say both.
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                // As above: a v2 marker carries both media paths even when there was no media to park.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_444").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_444").absolutePath,
                dbSnapshot = snapFile.absolutePath
            )
        )

        RestoreCoordinator.recover(context, lifeDatabase = null)

        // Closet repaired…
        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        // …but the marker survives, because the database half is still outstanding and clearing it
        // here would strand exactly the state it exists to describe.
        assertThat(markerPresent()).isTrue()
        assertThat(snapFile.exists()).isTrue()
    }

    // ------------------------------------------------------------------
    //  Intent persistence failure
    // ------------------------------------------------------------------

    /**
     * A squatter on the marker path — a directory, or anything else a rename cannot replace — must not
     * be able to make the marker unwritable.
     *
     * `write` is tmp-then-rename and the rename *is* the commit. When the destination already exists
     * the first rename fails, and failing there would be the one path that turns a recoverable
     * condition into an unrecoverable one, because the marker is the only thing that makes an
     * interrupted restore decidable. Deleting and retrying turns it into a plain overwrite.
     *
     * The earlier version of this test asserted a *failure* here, on the theory that a directory is
     * unwritable. It is not — the retry lands, which is the better outcome: the restore completes
     * instead of aborting a user's restore over a stale temp path. This now asserts that.
     */
    @Test
    fun markerPathSquatter_doesNotDefeatTheMarkerWrite() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-4.png"),
            oldBytes = listOf(byteArrayOf(1, 1, 1))
        )

        // Occupy the marker path with a directory, so the first rename cannot land on it.
        val marker = RestoreIntentStore.markerFile(context)
        marker.deleteRecursively()
        assertThat(marker.mkdirs()).isTrue()

        val result = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(result.isSuccess).isTrue()

        // The marker was written through the squatter, and the restore cleaned up after itself.
        assertThat(markerMissing()).isTrue()
        assertThat(marker.isDirectory).isFalse()
        assertThat(strayRestoreDirs()).isEmpty()

        // The restore actually happened: the Closet and the database are both the backup's version.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly("备份里的记录")
    }

    /**
     * The genuinely uncommittable case: the marker's *temp* file cannot even be created, so
     * [RestoreIntentStore.write] throws before anything durable has happened.
     *
     * This is the failure that must abort the restore at its first step — a restore that proceeded
     * without a marker would reach states that only the marker could describe, and would then be
     * unrecoverable after a kill. Induced with a directory at the temp path, which is the same class of
     * failure as a full disk or a revoked permission, and needs no `testMode` branch to reach.
     */
    @Test
    fun markerWriteFailure_abortsBeforeAnyUndecidableStateIsReached() = runTest {
        val (backup, oldMedia) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-4.png"),
            oldBytes = listOf(byteArrayOf(1, 1, 1))
        )

        // A directory at the path `AtomicFile` actually writes through. `AtomicFile.newName()` is the
        // file name plus `.new` — not `.tmp` — so squatting on `.tmp` would leave this test passing
        // without ever inducing the failure it claims to cover.
        val tmpPath = File(context.filesDir, "${RestoreIntentStore.MARKER_NAME}.new")
        tmpPath.deleteRecursively()
        assertThat(tmpPath.mkdirs()).isTrue()

        val result = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(result.isSuccess).isFalse()

        // It failed at the very first step, so nothing durable was touched.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(OLD_CAPTURE_TEXT)
        oldMedia.forEach { p ->
            assertThat(File(p).isFile).isTrue()
            assertThat(File(p).length()).isGreaterThan(0L)
        }

        // And recovery still reaches a decidable outcome: the user's data, no stray trees.
        tmpPath.deleteRecursively()
        RestoreCoordinator.recover(context, db)

        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(OLD_CAPTURE_TEXT)
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  Idempotent recovery across restarts
    // ------------------------------------------------------------------

    /**
     * The predicate that decides whether the database half needs repair, held to its exact set.
     *
     * This is a table test rather than an assertion inside a recovery test, because the interesting
     * content is the *other* states: every failure this design prevents comes from a state being
     * classified one notch too low, and a test that only exercised the happy path would agree with a
     * predicate that returned `true` unconditionally.
     *
     * This case pins the **v2** column: with a Life OS section present, the three states that follow the
     * transaction are exactly the ones needing a replay. `HEALTH_CHECKING` is included because for v2 the
     * rows may already be the backup's, and treating it as "nothing to do" would let a process killed
     * there be "recovered" by reverting the Closet alone — the user's wardrobe next to the backup's
     * life-graph.
     *
     * The v1 column is a separate question and is asserted separately below; see
     * [requiresDatabaseRecovery_healthCheckingIsFormatDependent] for why it cannot be folded in here.
     */
    @Test
    fun requiresDatabaseRecovery_isExactlyTheThreeStatesThatFollowTheTransaction() {
        fun needsRecovery(state: RestoreState) =
            RestoreIntent(includesLifeOs = true, state = state).requiresDatabaseRecovery()

        assertThat(RestoreState.entries.filter { needsRecovery(it) }).containsExactly(
            RestoreState.DB_COMMITTING,
            RestoreState.DB_COMMITTED,
            RestoreState.HEALTH_CHECKING
        )

        // …and the states before the transaction are exactly the ones that must NOT trigger a replay,
        // because at those points the database is provably untouched. A false positive here would run
        // a needless transaction; the mirror error is the one that loses data.
        assertThat(RestoreState.entries.filterNot { needsRecovery(it) }).containsExactly(
            RestoreState.PREPARING,
            RestoreState.STAGED,
            RestoreState.CLOSET_SWAPPED,
            RestoreState.MEDIA_SWAPPED,
            RestoreState.COMMITTED,
            RestoreState.FAILED,
            RestoreState.ROLLED_BACK
        )
    }

    /**
     * The full truth table, both columns, asserted state by state.
     *
     * ### Why this cannot be one `includesLifeOs && state in {…}` expression
     *
     * `HEALTH_CHECKING` is reached by **both** formats and means something different in each. For v2 the
     * transaction has run and the rows may be the backup's; for **v1** there is no Life OS section at
     * all — no transaction, no rows written, no snapshot on disk — so replaying anything would
     * `withTransaction` a database the archive never mentioned and overwrite 记录 / 资料库 the user
     * accumulated *after* taking that v1 backup. A single conjunction cannot express that; it collapses
     * to a state-only predicate whenever `includesLifeOs` is true.
     *
     * ### Why `DB_COMMITTING` / `DB_COMMITTED` stay `true` even with `includesLifeOs = false`
     *
     * Those two states do **not** consult the flag, and that asymmetry is deliberate. They are reachable
     * only through the v2 branch that writes the snapshot and opens the transaction, so a marker claiming
     * one of them while saying `includesLifeOs = false` is **self-contradictory** — the durable record of
     * a database half genuinely in flight, with a flag denying it exists. The dangerous reading is "no
     * Life OS, nothing to do": recovery would then consume the parked trees, delete the marker, and step
     * over a database that may hold the backup's rows. The conservative `true` leaves the marker and the
     * snapshot for a later pass, which may waste a retry but can never lose data.
     *
     * Asserting both columns explicitly is the point. A test that only checked the v2 column would pass
     * against the old `includesLifeOs && …` shape and silently lose this distinction.
     */
    @Test
    fun requiresDatabaseRecovery_isFormatDependentInExactlyOneState() {
        // The honest, reachable markers for each format, then the contradictory combination that must
        // still resolve to the safe answer rather than to "nothing to do".
        data class Row(val state: RestoreState, val includesLifeOs: Boolean, val expected: Boolean)

        val table = listOf(
            // --- v2: the full protocol, transaction included. ------------------------------------
            Row(RestoreState.PREPARING, true, false),
            Row(RestoreState.STAGED, true, false),
            Row(RestoreState.CLOSET_SWAPPED, true, false),
            Row(RestoreState.MEDIA_SWAPPED, true, false),
            Row(RestoreState.DB_COMMITTING, true, true),
            Row(RestoreState.DB_COMMITTED, true, true),
            Row(RestoreState.HEALTH_CHECKING, true, true),
            Row(RestoreState.COMMITTED, true, false),
            Row(RestoreState.FAILED, true, false),
            Row(RestoreState.ROLLED_BACK, true, false),

            // --- v1: no Life OS section, so no transaction ever ran. ------------------------------
            // HEALTH_CHECKING is the row that matters: v1 passes through it with no snapshot.
            Row(RestoreState.PREPARING, false, false),
            Row(RestoreState.STAGED, false, false),
            Row(RestoreState.CLOSET_SWAPPED, false, false),
            Row(RestoreState.MEDIA_SWAPPED, false, false),
            Row(RestoreState.DB_COMMITTING, false, true),
            Row(RestoreState.DB_COMMITTED, false, true),
            Row(RestoreState.HEALTH_CHECKING, false, false),
            Row(RestoreState.COMMITTED, false, false),
            Row(RestoreState.FAILED, false, false),
            Row(RestoreState.ROLLED_BACK, false, false)
        )

        table.forEach { row ->
            assertThat(
                RestoreIntent(includesLifeOs = row.includesLifeOs, state = row.state)
                    .requiresDatabaseRecovery()
            ).isEqualTo(row.expected)
        }

        // The complete enumeration is asserted too, so a future state added to the enum cannot quietly
        // fall into `else -> false` without a line here failing.
        assertThat(table.map { it.state }.toSet()).containsExactlyElementsIn(RestoreState.entries.toSet())
    }

    /**
     * The single cell where the format decides the answer: `HEALTH_CHECKING`.
     *
     * Stated on its own because it is the one behavioural difference between v1 and v2 in this predicate,
     * and because it is the cell a careless refactor is most likely to flatten. For a v1 marker the answer
     * must stay `false` — there is no snapshot to replay and touching the database would corrupt data the
     * archive never mentioned.
     */
    @Test
    fun requiresDatabaseRecovery_healthCheckingIsFormatDependent() {
        assertThat(
            RestoreIntent(includesLifeOs = true, state = RestoreState.HEALTH_CHECKING)
                .requiresDatabaseRecovery()
        ).isTrue()

        // v1 HEALTH_CHECKING must remain false. This is the assertion that forbids collapsing the
        // predicate into a state-only check.
        assertThat(
            RestoreIntent(includesLifeOs = false, state = RestoreState.HEALTH_CHECKING)
                .requiresDatabaseRecovery()
        ).isFalse()
    }

    /**
     * The media-recovery predicate, asserted as its complete truth table.
     *
     * [revertMedia] used to gate on `includesLifeOs` while [requiresDatabaseRecovery] had already been
     * corrected to ignore it for `DB_COMMITTING`/`DB_COMMITTED`. Two predicates describing the same
     * durable record, disagreeing — and the disagreement was a permanent half-restore, because the media
     * tree stayed on the backup's version while the Closet and database were rolled back, and the cleanup
     * then deleted the media evidence.
     *
     * ### Why "the flag alone means the media was swapped" is false
     *
     * The obvious fix is `includesLifeOs` for every state, and it is wrong. `includesLifeOs` describes
     * what the **archive** contained, not what this marker's restore had *done* by the time it was killed.
     * A v2 restore killed at `STAGED` has `includesLifeOs = true` and has not renamed a single directory;
     * reverting media there would delete a media tree that was never replaced. So the answer has to come
     * from *where in the sequence* the marker sits, with the flag consulted only where the sequence is
     * genuinely ambiguous — `CLOSET_SWAPPED`, where the rename may have completed without the marker
     * catching up, and `HEALTH_CHECKING`, which both formats pass through.
     *
     * An earlier revision of this test asserted exactly the wrong thing — "v2 flag ⇒ media was swapped,
     * in every state" — which is the belief that produced the bug. It is replaced by the table below.
     */
    @Test
    fun requiresLifeMediaRecovery_isExactlyTheStatesWhereTheSwapCanHaveHappened() {
        data class Row(val state: RestoreState, val includesLifeOs: Boolean, val expected: Boolean)

        val table = listOf(
            // --- v2: the archive carried a media section. -----------------------------------------
            // PREPARING / STAGED are provably before the first rename, so there is nothing to revert.
            Row(RestoreState.PREPARING, true, false),
            Row(RestoreState.STAGED, true, false),
            // The media rename may have completed without the marker catching up — only the format can
            // say whether there was a media section to rename.
            Row(RestoreState.CLOSET_SWAPPED, true, true),
            // MEDIA_SWAPPED *is* the swap; the DB states follow it. Self-evidencing, flag not consulted.
            Row(RestoreState.MEDIA_SWAPPED, true, true),
            Row(RestoreState.DB_COMMITTING, true, true),
            Row(RestoreState.DB_COMMITTED, true, true),
            Row(RestoreState.HEALTH_CHECKING, true, true),
            // Resolved: reverting would destroy a committed restore or redo a finished rollback.
            Row(RestoreState.COMMITTED, true, false),
            Row(RestoreState.FAILED, true, false),
            Row(RestoreState.ROLLED_BACK, true, false),

            // --- v1: the archive had no media section. --------------------------------------------
            Row(RestoreState.PREPARING, false, false),
            Row(RestoreState.STAGED, false, false),
            Row(RestoreState.CLOSET_SWAPPED, false, false),
            // The v2-only states stay `true` even here. They are reachable only through the branch that
            // performs the media swap, so the state contradicts the flag — and the state wins. This is the
            // same asymmetry [requiresDatabaseRecovery] applies, and it must be applied here too, or the
            // two predicates disagree about one marker and recovery repairs two surfaces out of three.
            Row(RestoreState.MEDIA_SWAPPED, false, true),
            Row(RestoreState.DB_COMMITTING, false, true),
            Row(RestoreState.DB_COMMITTED, false, true),
            Row(RestoreState.HEALTH_CHECKING, false, false),
            Row(RestoreState.COMMITTED, false, false),
            Row(RestoreState.FAILED, false, false),
            Row(RestoreState.ROLLED_BACK, false, false)
        )

        table.forEach { row ->
            assertThat(
                RestoreIntent(includesLifeOs = row.includesLifeOs, state = row.state)
                    .requiresLifeMediaRecovery()
            ).isEqualTo(row.expected)
        }

        // No state may fall through an `else` unexamined.
        assertThat(table.map { it.state }.toSet()).containsExactlyElementsIn(RestoreState.entries.toSet())
    }

    /**
     * The two predicates must agree about any one marker.
     *
     * Wherever the database half is known to be in flight, the media half is known to have been swapped,
     * so "database recovery required" must always **imply** "media recovery required". The converse does
     * not hold and must not be asserted: `MEDIA_SWAPPED` needs the media revert while being provably
     * before the transaction, so it needs no database work. Asserting the implication rather than the
     * equivalence keeps that legitimate case legal while still forbidding the contradiction that caused
     * the half-restore.
     */
    @Test
    fun requiresLifeMediaRecovery_neverContradictsTheDatabasePredicate() {
        val markers = RestoreState.entries.flatMap { state ->
            listOf(
                RestoreIntent(includesLifeOs = true, state = state),
                RestoreIntent(includesLifeOs = false, state = state)
            )
        }

        markers.forEach { marker ->
            if (marker.requiresDatabaseRecovery()) {
                assertThat(marker.requiresLifeMediaRecovery()).isTrue()
            }
        }

        // The concrete cell that used to be wrong: DB_COMMITTING with a flag denying Life OS.
        assertThat(
            RestoreIntent(includesLifeOs = false, state = RestoreState.DB_COMMITTING)
                .requiresLifeMediaRecovery()
        ).isTrue()

        // …and the v1 protection in the other direction: a marker that never entered a v2-only state must
        // not have its media touched, because the archive never mentioned a media directory.
        assertThat(
            RestoreState.entries.filter { !it.isV2Only() }.all {
                !RestoreIntent(includesLifeOs = false, state = it).requiresLifeMediaRecovery()
            }
        ).isTrue()
    }

    /** States reachable only through the v2 branch, which therefore imply the media swap happened. */
    private fun RestoreState.isV2Only(): Boolean = when (this) {
        RestoreState.MEDIA_SWAPPED, RestoreState.DB_COMMITTING, RestoreState.DB_COMMITTED -> true
        else -> false
    }

    /**
     * The evidence check fires for every missing piece — **whatever the flag says**.
     *
     * Exhaustive over the missing fields and over both formats, because "fail closed" is only meaningful
     * if it fires for every missing piece: a check that tested only `dbSnapshot` would pass the
     * two-evidence case and let the rest through.
     *
     * ### Why `includesLifeOs = true` is no longer a shortcut to "complete"
     *
     * The previous revision opened with `if (includesLifeOs) return false`, on the theory that the flag
     * establishes v2 and therefore the metadata must be present. It does not. The flag records what the
     * **archive** contained; it says nothing about what this marker file managed to persist. A
     * `DB_COMMITTED` marker with `includesLifeOs = true` and `mediaOldDir == null` would have sailed
     * through the check, had its database and Closet rolled back, failed to revert media — and then had
     * its marker cleared over the resulting half-restore, because cleanup does not care that the revert
     * was incomplete.
     *
     * So completeness is now derived from what recovery is *about to do* ([RestoreIntent.swapInScope],
     * [RestoreIntent.requiresLifeMediaRecovery], [RestoreIntent.requiresDatabaseRecovery]) and is checked
     * for **every** non-terminal marker.
     */
    @Test
    fun missingRecoveryEvidence_firesForEveryMissingPieceWhateverTheFlagSays() {
        fun marker(
            includesLifeOs: Boolean,
            state: RestoreState = RestoreState.DB_COMMITTING,
            closetOld: String? = "/old-closet",
            snapshot: String? = "/snap",
            mediaStage: String? = "/stage",
            mediaOld: String? = "/old"
        ) = RestoreIntent(
            state = state,
            includesLifeOs = includesLifeOs,
            closetOldDir = closetOld,
            dbSnapshot = snapshot,
            mediaStageDir = mediaStage,
            mediaOldDir = mediaOld
        )

        // Complete evidence: decidable, so recovery may proceed — in either format.
        assertThat(marker(includesLifeOs = false).missingRecoveryEvidence()).isNull()
        assertThat(marker(includesLifeOs = true).missingRecoveryEvidence()).isNull()

        // Each missing piece on its own makes the marker undecidable …
        assertThat(marker(includesLifeOs = false, snapshot = null).missingRecoveryEvidence())
            .isEqualTo("dbSnapshot")
        assertThat(marker(includesLifeOs = false, mediaStage = null).missingRecoveryEvidence())
            .isEqualTo("mediaStageDir")
        assertThat(marker(includesLifeOs = false, mediaOld = null).missingRecoveryEvidence())
            .isEqualTo("mediaOldDir")
        assertThat(marker(includesLifeOs = false, closetOld = null).missingRecoveryEvidence())
            .isEqualTo("closetOldDir")

        // … and the *same* gaps in a marker whose flag agrees with its state. This is the assertion the
        // old short-circuit made impossible to write, and it is the one that matters: these are ordinary
        // v2 markers, not exotic contradictions.
        assertThat(marker(includesLifeOs = true, snapshot = null).missingRecoveryEvidence())
            .isEqualTo("dbSnapshot")
        assertThat(marker(includesLifeOs = true, mediaStage = null).missingRecoveryEvidence())
            .isEqualTo("mediaStageDir")
        assertThat(marker(includesLifeOs = true, mediaOld = null).missingRecoveryEvidence())
            .isEqualTo("mediaOldDir")
        assertThat(marker(includesLifeOs = true, closetOld = null).missingRecoveryEvidence())
            .isEqualTo("closetOldDir")

        // The predicate wrapper still answers the same question.
        assertThat(marker(includesLifeOs = true, mediaOld = null).hasIncompleteLifeOsEvidence()).isTrue()
    }

    /**
     * Terminal markers are exempt from the evidence check, on purpose.
     *
     * `COMMITTED` / `ROLLED_BACK` / `FAILED` mean every durable surface already agrees and only cleanup
     * remains. Demanding rollback metadata there would be wrong twice over: it would fail a recovery that
     * has nothing left to undo, and it would do so over data that is already consistent — which is exactly
     * the "bricked by an undeletable leftover" failure the gate's terminal rule exists to prevent.
     */
    @Test
    fun missingRecoveryEvidence_neverDemandsRollbackMetadataFromATerminalMarker() {
        listOf(RestoreState.COMMITTED, RestoreState.ROLLED_BACK, RestoreState.FAILED).forEach { state ->
            listOf(true, false).forEach { includesLifeOs ->
                assertThat(
                    RestoreIntent(includesLifeOs = includesLifeOs, state = state)
                        .missingRecoveryEvidence()
                ).isNull()
            }
        }
    }

    /**
     * The evidence required follows the predicates, so a state that needs no rollback needs no metadata.
     *
     * `PREPARING` is provably before any rename and `MEDIA_SWAPPED` in a v1 marker never touched media, so
     * neither may be rejected for missing paths it would never have written. Over-demanding evidence is
     * not a harmless strictness: it turns a recoverable marker into one that nothing can act on.
     */
    @Test
    fun missingRecoveryEvidence_asksOnlyForWhatRecoveryWouldActuallyUse() {
        // PREPARING is out of scope for every revert, so a bare marker is fine.
        assertThat(
            RestoreIntent(includesLifeOs = true, state = RestoreState.PREPARING, closetOldDir = null)
                .missingRecoveryEvidence()
        ).isNull()

        // A v1 CLOSET_SWAPPED marker needs the Closet path (a swap may have happened) but not the media
        // paths, because v1 never had a media section to swap.
        assertThat(
            RestoreIntent(includesLifeOs = false, state = RestoreState.CLOSET_SWAPPED, closetOldDir = "/old-closet")
                .missingRecoveryEvidence()
        ).isNull()
        assertThat(
            RestoreIntent(includesLifeOs = false, state = RestoreState.CLOSET_SWAPPED, closetOldDir = null)
                .missingRecoveryEvidence()
        ).isEqualTo("closetOldDir")

        // …whereas the same state in v2 does need them, because there the media rename may have run.
        assertThat(
            RestoreIntent(includesLifeOs = true, state = RestoreState.CLOSET_SWAPPED, closetOldDir = "/old-closet")
                .missingRecoveryEvidence()
        ).isEqualTo("mediaStageDir")
    }

    /**
     * The Closet-revert half, held to its exact set of states.
     *
     * The predicate used to be a property on [RestoreState]; it is now derived from two inputs — the
     * state, and whether a swap can already have happened — so the table is asserted through the same
     * classification recovery itself uses. The rule is *not* "every database state reverts the Closet":
     * `COMMITTED` means the restore is official and the Closet is deliberately left on the backup's
     * version, and `FAILED`/`ROLLED_BACK` mean the revert is already accounted for. Both of those are
     * cleanup-only. A committed database does not un-swap the directory, so every state *between* the
     * swap and the commit does revert — and only `PREPARING`, which is provably before the first
     * rename, is out of scope for a delete.
     *
     * The previous test asserted this via the removed property. Keeping the coverage here — rather than
     * deleting the test with the property — is the point: the states are the same, and the one that
     * matters is the state that must *not* revert.
     */
    @Test
    fun closetRevert_coversTheSwapOnwardsButNeverCommitsOrPreparing() {
        fun reverts(state: RestoreState) = RestoreIntent(includesLifeOs = true, state = state).swapInScope

        assertThat(RestoreState.entries.filter { reverts(it) }).containsExactly(
            RestoreState.STAGED,
            RestoreState.CLOSET_SWAPPED,
            RestoreState.MEDIA_SWAPPED,
            RestoreState.DB_COMMITTING,
            RestoreState.DB_COMMITTED,
            RestoreState.HEALTH_CHECKING,
            RestoreState.COMMITTED,
            RestoreState.FAILED,
            RestoreState.ROLLED_BACK
        )
        // Only PREPARING is out of scope: a marker written at PREPARING proves no rename happened yet,
        // so a live directory seen there can never be the backup's tree.
        assertThat(RestoreState.entries.filterNot { reverts(it) }).containsExactly(RestoreState.PREPARING)

        // The implication that has to hold in the one direction that matters: a state needing the
        // database replayed must also be in scope for the Closet revert, or recovery would fix half.
        assertThat(
            RestoreState.entries
                .filter { RestoreIntent(includesLifeOs = true, state = it).requiresDatabaseRecovery() }
                .all { reverts(it) }
        ).isTrue()
    }

    /**
     * First startup of the two that a killed restore needs: no database is reachable, so only the
     * Closet is repaired and the marker is deliberately left behind.
     *
     * The failure mode this pins is the tempting one — "the Closet is fixed, clear the marker, we're
     * done". That is wrong in a way that is invisible until it is not: the database still holds the
     * backup's rows, and clearing the marker destroys the only record that the other half is pending.
     * The user would then launch the app on a correct wardrobe and a stranger's life-graph, with
     * nothing left to say so.
     */
    @Test
    fun recoveryWithoutDatabase_revertsClosetButKeepsTheMarkerForALaterStart() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = parkDbCommittingState(id = 601)

        RestoreCoordinator.recover(context, lifeDatabase = null)

        // The filesystem half is repaired…
        val itemsJson = File(File(context.filesDir, "closie"), "items.json").readText()
        assertThat(itemsJson).contains(OLD_ITEM_ID)
        assertThat(itemsJson).doesNotContain(NEW_ITEM_ID)

        // …and every artefact the later pass depends on survives it: the marker that names the
        // outstanding database work, and the snapshot that work will replay.
        assertThat(markerPresent()).isTrue()
        val pending = requireMarker()
        assertThat(pending.state).isEqualTo(RestoreState.DB_COMMITTING)
        assertThat(pending.dbSnapshot).isEqualTo(snapFile.absolutePath)
        assertThat(snapFile.isFile).isTrue()
    }

    /**
     * Second startup: a database is available now, so the outstanding half finishes.
     *
     * Read together with the previous test this is the whole retry contract — the first start is
     * allowed to only get half-way, and the second start is required to notice and complete the job.
     * That is why the once-per-process guard is in-memory: a guard that survived the restart would
     * make exactly this test impossible.
     */
    @Test
    fun secondStartWithDatabase_finishesTheRestoreAndClearsTheMarker() = runTest {
        // The database at its pre-restore state, and a snapshot of exactly those rows.
        seedCapture(OLD_CAPTURE_TEXT)
        db.mediaDao().deleteAllLinks()
        db.mediaDao().deleteAllResources()
        db.mediaDao().deleteAllAssets()
        val oldMedia = listOf("local-a.png", "local-b.png").mapIndexed { i, name ->
            writeManagedMedia(name, byteArrayOf((i + 10).toByte(), 20, 30))
        }
        oldMedia.forEachIndexed { i, path ->
            insertMediaRow("local-asset-$i", "local-res-$i", path, byteArrayOf((i + 10).toByte(), 20, 30))
        }
        val snapFile = parkDbCommittingState(id = 602, snapshotOf = db)

        // Simulate "the transaction had already committed" before the kill, so the second start has
        // real work to do rather than a no-op replay.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        // First start: filesystem only, no database.
        RestoreCoordinator.recover(context, lifeDatabase = null)
        assertThat(markerPresent()).isTrue()
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly("备份里的记录")

        // Second start, same process boundary or a fresh one: the database half completes.
        RestoreCoordinator.recover(context, db)

        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = oldMedia
        )
        assertThat(markerMissing()).isTrue()
        // The snapshot is a copy of the user's life-graph; once it has been replayed it is pure
        // liability, so it goes with the marker.
        assertThat(snapFile.exists()).isFalse()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * Every recovery is safe to run again, including after it has finished.
     *
     * The requirement is not decoration. Recovery runs from two places — the Application's startup and
     * a wardrobe repository's constructor — and whichever loses the race still calls it. If a second
     * call were anything other than a no-op, "whoever wins" would become a correctness question.
     *
     * The row comparison is ordered on purpose: an unordered set comparison would pass even if the
     * replay had re-inserted every row, since the snapshot's rows and the restored rows are equal as
     * sets. Order catches a genuine re-mutation.
     */
    @Test
    fun thirdCallAfterCompletedRecovery_isAGenuineNoOp() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        parkDbCommittingState(id = 603)

        RestoreCoordinator.recover(context, db)
        assertThat(markerMissing()).isTrue()

        val afterFirst = db.captureDao().getAllOnce()
        val closieBytes = File(File(context.filesDir, "closie"), "items.json").readBytes()

        RestoreCoordinator.recover(context, db)
        RestoreCoordinator.recover(context, lifeDatabase = null)

        assertThat(db.captureDao().getAllOnce().map { it.id }).containsExactlyElementsIn(
            afterFirst.map { it.id }
        ).inOrder()
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(OLD_CAPTURE_TEXT)
        // The Closet must not have been swapped, re-reverted, or otherwise perturbed by the extra runs.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readBytes()).isEqualTo(closieBytes)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * The composition that actually happens on a device: within **one** recovery, the filesystem pass
     * runs first and the database-aware pass runs second.
     *
     * The danger is not hypothetical and it is not symmetric. The first pass reverts the Closet by
     * *consuming* the parked directory — it renames `.closie_restore_old_N` back to `closie/`. The
     * second pass then runs with a marker that still claims the Closet is swapped, because the marker
     * is never rewritten by the filesystem pass. If the second pass re-resolved its `oldDir` from
     * anything other than the live filesystem, it would find nothing to rename back and could conclude
     * that `closie/` was the backup's tree — and delete it. Deleting it would destroy the user's
     * wardrobe, permanently, with no parked copy left to recover from.
     *
     * So this test does not just check "the restore completed". It checks the specific thing that
     * would be lost: `closie/` still exists, still holds the user's item, and does not hold the
     * backup's.
     */
    @Test
    fun filesystemPassThenDatabasePassInOneRecovery_neverLosesTheCloset() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        parkDbCommittingState(id = 604)
        // The database already holds the backup's rows: the pessimistic case the state covers.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        // Exactly the recovery order: filesystem pass, then the database-aware pass, no restart between.
        BackupManager.recoverInterruptedRestore(context)
        RestoreCoordinator.recover(context, db)

        val closieDir = File(context.filesDir, "closie")
        assertThat(closieDir.isDirectory).isTrue()
        assertThat(closieDir.listFiles().orEmpty().map { it.name }).isNotEmpty()

        val itemsJson = File(closieDir, "items.json").readText()
        assertThat(itemsJson).contains(OLD_ITEM_ID)
        assertThat(itemsJson).doesNotContain(NEW_ITEM_ID)

        // Both halves agree, and nothing was left parked.
        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = emptyList()
        )
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  Durable-state helpers (AtomicFile semantics)
    // ------------------------------------------------------------------

    /**
     * A marker that is *present but unparseable* must not behave like an absent one.
     *
     * The failure this pins is the expensive one: recovery reads the marker, gets `null` (the old API
     * conflated absent with corrupt), concludes there is nothing to do, and then — because "nothing to
     * do" also means "clean up" — deletes the parked tree and the snapshot. The user is left with a
     * half-restored Closet and none of the evidence needed to finish. So: a corrupt marker must reach
     * recovery as an explicit `Corrupt`, and recovery must keep every artefact it cannot interpret.
     */
    @Test
    fun corruptMarker_isNeverTreatedAsNoMarker_andKeepsEveryArtefact() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_700")
        val snapFile = File(filesDir, ".closie_restore_dbsnap_700.json")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        snapFile.writeText("{}")

        // Truncated JSON: the file exists, so it is not Missing, but it cannot be parsed.
        RestoreIntentStore.markerFile(context).writeText("{ \"id\": 700, \"state\": ")

        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Corrupt::class.java)
        assertThat(markerPresent()).isTrue()

        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // Every artefact survives: nothing was deleted on the strength of a record we could not read.
        assertThat(markerPresent()).isTrue()
        assertThat(oldDir.exists()).isTrue()
        assertThat(snapFile.exists()).isTrue()
        // …and crucially the Closet was *not* reverted from a bad guess: the parked tree is still the
        // user's, and the live tree is untouched because no instruction could be trusted.
        assertThat(File(oldDir, "items.json").readText()).contains(OLD_ITEM_ID)
        // The database was never asked to do anything.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains(OLD_CAPTURE_TEXT)
    }

    /**
     * The same marker, but with a database available and the state that follows the transaction:
     * recovery must refuse to touch the database too, for the same reason.
     */
    @Test
    fun corruptMarker_doesNotReplayTheDatabaseEither() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val oldDir = File(filesDir, ".closie_restore_old_701")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        val snapFile = File(filesDir, ".closie_restore_dbsnap_701.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))
        // The database has been moved to the backup's rows: the pessimistic DB_COMMITTING case.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        RestoreIntentStore.markerFile(context).writeText("not json at all")

        val outcome = RestoreCoordinator.recover(context, db)
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // The database keeps the backup's rows — recovery did not guess a snapshot location — and the
        // evidence survives so a later, readable marker can finish the job.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly("备份里的记录")
        assertThat(markerPresent()).isTrue()
        assertThat(oldDir.exists()).isTrue()
        assertThat(snapFile.exists()).isTrue()
    }

    /** The plain case: write and read a marker, and a snapshot, through the real AtomicFile path. */
    @Test
    fun markerAndSnapshot_roundTripThroughAtomicJson() {
        val intent = RestoreIntent(
            id = 702,
            state = RestoreState.STAGED,
            includesLifeOs = true,
            closetStageDir = "/tmp/stage",
            closetOldDir = "/tmp/old",
            closetExistedBefore = true
        )
        RestoreIntentStore.write(context, intent)
        assertThat(requireMarker()).isEqualTo(intent)

        val f = LifeBackupApplier.snapshotFile(703, context.filesDir)
        val payload = LifeBackupPayload()
        LifeBackupApplier.writeSnapshot(f, payload)
        assertThat(LifeBackupApplier.readSnapshot(f)).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        // A snapshot that was never written is Missing — not corrupt — so callers can tell the two apart.
        assertThat(LifeBackupApplier.readSnapshot(File(context.filesDir, "nope.json")))
            .isEqualTo(AtomicJson.ReadResult.Missing)

        RestoreIntentStore.clear(context)
        assertThat(markerMissing()).isTrue()
    }

    /** A snapshot that exists but is garbage must report Corrupt, not Missing. */
    @Test
    fun corruptSnapshot_isReportedAsCorruptNotAbsent() {
        val f = LifeBackupApplier.snapshotFile(704, context.filesDir)
        f.writeText("{ this is not a payload")

        assertThat(LifeBackupApplier.readSnapshot(f))
            .isInstanceOf(AtomicJson.ReadResult.Corrupt::class.java)
    }

    // ------------------------------------------------------------------
    //  The original live directory may not have existed
    // ------------------------------------------------------------------

    /**
     * A restore onto a device that had no Closet at all, killed after publishing the new one.
     *
     * There is no parked tree, so the previous revision's `oldDir.exists()` short-circuit did nothing —
     * and "nothing" is the wrong answer: the pre-restore state was *no directory*, so the backup's tree
     * is a half-restore and must go. This is the case `closetExistedBefore` exists for.
     */
    @Test
    fun rollbackWhenClosetDidNotExistBefore_removesThePublishedCloset() = runTest {
        seedCapture("恢复前的记录")
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        // No parked directory at all, and none should be created: the original did not exist.
        val oldDir = File(filesDir, ".closie_restore_old_710")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 710,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = false
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        // The end state is "not there", which is exactly what it was before the restore.
        assertThat(closieDir.exists()).isFalse()
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * The same for Life OS media: a device with no media directory that ran a v2 restore and died
     * after publishing the backup's media.
     */
    @Test
    fun rollbackWhenMediaDidNotExistBefore_removesThePublishedMedia() = runTest {
        val filesDir: File = context.filesDir
        val mediaDir = File(filesDir, "media")
        val mediaOld = File(filesDir, ".life_media_restore_old_711")
        writeManagedMedia("backup-shot.png", byteArrayOf(9, 9, 9))

        // The Closet was present and is already correct: only the media surface is in scope.
        val closieOld = File(filesDir, ".closie_restore_old_711")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 711,
                state = RestoreState.MEDIA_SWAPPED,
                includesLifeOs = true,
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                // Both media paths, as a v2 marker always carries. `mediaOldDir` is named even though the
                // directory does not exist — the device had no media before, which is what
                // `mediaExistedBefore = false` records and what makes "delete the published tree" the
                // correct revert instead of "rename something back".
                mediaStageDir = File(filesDir, ".life_media_restore_stage_711").absolutePath,
                mediaOldDir = mediaOld.absolutePath,
                mediaExistedBefore = false
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        // No media directory before, no media directory now.
        assertThat(mediaDir.exists()).isFalse()
        // …and the Closet came back to the user's version.
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
    }

    /**
     * The guard rail on the two tests above: a marker that never reached a swap must not delete the
     * user's live Closet.
     *
     * `existedBefore = false` is what a *fresh install* looks like, so the rule "no old dir and
     * existedBefore false means the live tree is a half-restore" would be catastrophic if it also
     * applied before any swap happened. `PREPARING` is provably before every rename, so it is excluded.
     */
    @Test
    fun preparingState_neverDeletesTheLiveCloset() = runTest {
        val filesDir: File = context.filesDir
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 712,
                state = RestoreState.PREPARING,
                closetOldDir = File(filesDir, ".closie_restore_old_712").absolutePath,
                closetExistedBefore = false
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
    }

    // ------------------------------------------------------------------
    //  Process death *between* the two renames
    // ------------------------------------------------------------------

    /**
     * Killed after `closie -> old` but before `stage -> closie`. The marker still says STAGED, so the
     * only thing that reveals the truth is the filesystem: `live` is gone and `old` is present.
     */
    @Test
    fun closetRenameMidway_whenLiveAndStageAreGone_restoresTheParkedTree() = runTest {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_720")
        val stageDir = File(filesDir, ".closie_restore_stage_720")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(stageDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        if (closieDir.exists()) closieDir.deleteRecursively()

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 720,
                state = RestoreState.STAGED,
                includesLifeOs = false,
                closetStageDir = stageDir.absolutePath,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(strayRestoreDirs()).isEmpty()
        assertThat(markerMissing()).isTrue()
    }

    /**
     * The mirror: both renames completed but `CLOSET_SWAPPED` was never persisted. The marker says
     * STAGED, the stage directory is gone (it *is* `closie` now), and `live` holds the backup's tree —
     * which must be replaced by the parked one, not left in place.
     */
    @Test
    fun closetSwapDoneButMarkerLagging_stillRevertsToTheParkedTree() = runTest {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_721")
        val stageDir = File(filesDir, ".closie_restore_stage_721")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        assertThat(stageDir.exists()).isFalse()

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 721,
                state = RestoreState.STAGED,
                includesLifeOs = false,
                closetStageDir = stageDir.absolutePath,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        val items = File(closieDir, "items.json").readText()
        assertThat(items).contains(OLD_ITEM_ID)
        assertThat(items).doesNotContain(NEW_ITEM_ID)
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * Killed inside the *media* swap: `media -> old` done, `stage -> media` not. The marker is still
     * CLOSET_SWAPPED, and both surfaces must come back — including the Closet, which the marker does
     * prove was swapped.
     */
    @Test
    fun mediaRenameMidway_whenLiveMediaIsGone_restoresBothSurfaces() = runTest {
        val filesDir: File = context.filesDir
        val mediaDir = File(filesDir, "media")
        val mediaOld = File(filesDir, ".life_media_restore_old_730")
        val mediaStage = File(filesDir, ".life_media_restore_stage_730")
        val closieDir = File(filesDir, "closie")
        val closieOld = File(filesDir, ".closie_restore_old_730")

        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        mediaOld.mkdirs()
        File(mediaOld, "old-shot.png").writeBytes(byteArrayOf(1, 2, 3))
        mediaStage.mkdirs()
        File(mediaStage, "backup-shot.png").writeBytes(byteArrayOf(9, 9, 9))
        if (mediaDir.exists()) mediaDir.deleteRecursively()

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 730,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = true,
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = mediaStage.absolutePath,
                mediaOldDir = mediaOld.absolutePath,
                mediaExistedBefore = true
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        // Media is the user's again…
        assertThat(File(mediaDir, "old-shot.png").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(mediaDir, "backup-shot.png").exists()).isFalse()
        // …and the Closet too, because CLOSET_SWAPPED proves it was published.
        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * The mirror for media: the swap completed but `MEDIA_SWAPPED` was never persisted. The stage
     * directory is gone and `media` holds the backup's files, which must be replaced.
     */
    @Test
    fun mediaSwapDoneButMarkerLagging_stillRevertsToTheParkedTree() = runTest {
        val filesDir: File = context.filesDir
        val mediaDir = File(filesDir, "media")
        val mediaOld = File(filesDir, ".life_media_restore_old_731")
        val mediaStage = File(filesDir, ".life_media_restore_stage_731")
        val closieDir = File(filesDir, "closie")
        val closieOld = File(filesDir, ".closie_restore_old_731")

        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        mediaOld.mkdirs()
        File(mediaOld, "old-shot.png").writeBytes(byteArrayOf(4, 5, 6))
        mediaDir.mkdirs()
        File(mediaDir, "backup-shot.png").writeBytes(byteArrayOf(9, 9, 9))
        assertThat(mediaStage.exists()).isFalse()

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 731,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = true,
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = mediaStage.absolutePath,
                mediaOldDir = mediaOld.absolutePath,
                mediaExistedBefore = true
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        assertThat(File(mediaDir, "old-shot.png").readBytes()).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(File(mediaDir, "backup-shot.png").exists()).isFalse()
        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  v1 / v2 database semantics
    // ------------------------------------------------------------------

    /**
     * A **v1** marker sitting in HEALTH_CHECKING must not send recovery to the database.
     *
     * v1 has no Life OS section: no rows were written, no snapshot exists, and `filesDir/media` was
     * never touched. Treating the state alone as "the database may be committed" would make recovery
     * look for a snapshot that cannot exist and — worse — potentially rewrite the media directory from a
     * v1 archive that never described it. The `includesLifeOs` half of the predicate is what prevents
     * that, and this test is what holds it there.
     */
    @Test
    fun v1MarkerInHealthChecking_neverTouchesDatabaseOrMedia() = runTest {
        seedCapture("v1 恢复时必须原样保留的 Life 记录")
        val filesDir: File = context.filesDir
        val mediaDir = File(filesDir, "media")
        val liveMedia = writeManagedMedia("v1-untouched.png", byteArrayOf(1, 2, 3))

        val closieOld = File(filesDir, ".closie_restore_old_740")
        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 740,
                state = RestoreState.HEALTH_CHECKING,
                includesLifeOs = false,          // v1: no Life OS section at all
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                dbSnapshot = null                // …so there is no snapshot to replay
            )
        )

        assertThat(RestoreCoordinator.recover(context, db)).isEqualTo(RecoveryOutcome.Completed)

        // The Closet is back to the user's version…
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        // …the Life OS database is **unchanged**…
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly("v1 恢复时必须原样保留的 Life 记录")
        // …and the media directory is byte-for-byte untouched.
        assertThat(File(liveMedia).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(mediaDir.isDirectory).isTrue()
        assertThat(markerMissing()).isTrue()
    }

    /**
     * A **v2** marker in the same state must replay the snapshot. This is the mirror of the test above
     * and the reason the predicate cannot simply be keyed on the state: the two markers are identical
     * except for `includesLifeOs`, and they must behave oppositely.
     */
    @Test
    fun v2MarkerInHealthChecking_replaysThePreRestoreSnapshot() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieOld = File(filesDir, ".closie_restore_old_741")
        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val snapFile = File(filesDir, ".closie_restore_dbsnap_741.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))
        // The transaction landed before the kill: the database holds the backup's rows.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 741,
                state = RestoreState.HEALTH_CHECKING,
                includesLifeOs = true,
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                // v2, so both media paths are recorded — this device had no media directory, which is
                // what the default `mediaExistedBefore = false` says.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_741").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_741").absolutePath,
                dbSnapshot = snapFile.absolutePath
            )
        )

        assertThat(RestoreCoordinator.recover(context, db)).isEqualTo(RecoveryOutcome.Completed)

        assertMutuallyConsistent(expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT), expectedMediaFiles = emptyList())
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * The predicate itself, held to its exact truth table — the state half and the `includesLifeOs` half.
     *
     * ### What this replaced, and why the old expectation was the bug
     *
     * This test used to assert `v1: never, in any state` — that `includesLifeOs = false` made
     * `requiresDatabaseRecovery()` false for **every** state, `DB_COMMITTING` included. That is the
     * collapse the predicate was rewritten to avoid. Those two states are reachable *only* through the
     * v2 branch that writes the snapshot and opens the transaction, so a marker claiming one of them
     * while denying the Life OS section is not a v1 restore to be left alone — it is a contradiction
     * describing a database genuinely in flight. Answering "no recovery needed" there would let recovery
     * consume the parked trees, delete the marker, and walk past a database that may hold the backup's
     * rows.
     *
     * The correct table therefore has exactly one format-dependent cell, `HEALTH_CHECKING`, and this test
     * pins both columns so that neither can drift back.
     */
    @Test
    fun requiresDatabaseRecovery_dependsOnBothStateAndIncludesLifeOs() {
        fun needs(state: RestoreState, lifeOs: Boolean) =
            RestoreIntent(includesLifeOs = lifeOs, state = state).requiresDatabaseRecovery()

        // v2: exactly the three states that follow the transaction.
        assertThat(RestoreState.entries.filter { needs(it, lifeOs = true) })
            .containsExactly(
                RestoreState.DB_COMMITTING,
                RestoreState.DB_COMMITTED,
                RestoreState.HEALTH_CHECKING
            )

        // v1: the two transaction states still demand repair, because a marker in either of them cannot
        // be a v1 restore — the contradiction is resolved conservatively, never as "nothing to do".
        assertThat(RestoreState.entries.filter { needs(it, lifeOs = false) })
            .containsExactly(
                RestoreState.DB_COMMITTING,
                RestoreState.DB_COMMITTED
            )

        // The one state that is reachable by both formats and means different things in each — the reason
        // the state alone was not enough, and the cell that forbids the flattened predicate.
        assertThat(needs(RestoreState.HEALTH_CHECKING, lifeOs = true)).isTrue()
        assertThat(needs(RestoreState.HEALTH_CHECKING, lifeOs = false)).isFalse()

        // The difference between the two columns is exactly `HEALTH_CHECKING`, and nothing else. Stated
        // as a set difference so a future state cannot silently join one column only.
        val v2Only = RestoreState.entries.filter { needs(it, lifeOs = true) }
        val v1 = RestoreState.entries.filter { needs(it, lifeOs = false) }
        assertThat(v2Only.toSet() - v1.toSet()).containsExactly(RestoreState.HEALTH_CHECKING)
        assertThat(v1.toSet() - v2Only.toSet()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  Rollback itself fails
    // ------------------------------------------------------------------

    /**
     * Recovery's own rename can fail, and when it does the restore must not be reported as repaired.
     *
     * The first attempt fails the Closet rename; the marker, the parked tree and the snapshot must all
     * survive, the state must not become ROLLED_BACK, and the outcome must be RetryRequired. The second
     * attempt — fault removed — finishes the job. This is the retry contract, and it is the reason the
     * once-per-process guard must not latch on a failed pass.
     */
    @Test
    fun closetRollbackRenameFailure_keepsEvidenceAndRetriesCleanly() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_750")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 750,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        // First attempt: the rename back fails (full disk, open handle, …).
        val failing = FailingRestoreFs(failRenameInto = closieDir.absolutePath)
        val first = RestoreCoordinator.recoverFilesystemOnly(context, failing)
        assertThat(first).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // Evidence intact, and no false claim of a completed rollback.
        assertThat(markerPresent()).isTrue()
        assertThat(oldDir.exists()).isTrue()
        assertThat(requireMarker().state).isEqualTo(RestoreState.CLOSET_SWAPPED)
        assertThat(File(oldDir, "items.json").readText()).contains(OLD_ITEM_ID)

        // Second attempt, fault removed.
        val second = RestoreCoordinator.recoverFilesystemOnly(context)
        assertThat(second).isEqualTo(RecoveryOutcome.Completed)

        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /** The same contract for the media surface. */
    @Test
    fun mediaRollbackRenameFailure_keepsEvidenceAndRetriesCleanly() = runTest {
        val filesDir: File = context.filesDir
        val mediaDir = File(filesDir, "media")
        val mediaOld = File(filesDir, ".life_media_restore_old_751")
        mediaOld.mkdirs()
        File(mediaOld, "old-shot.png").writeBytes(byteArrayOf(1, 2, 3))
        mediaDir.mkdirs()
        File(mediaDir, "backup-shot.png").writeBytes(byteArrayOf(9, 9, 9))

        val closieOld = File(filesDir, ".closie_restore_old_751")
        writeWardrobeFiles(closieOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 751,
                state = RestoreState.MEDIA_SWAPPED,
                includesLifeOs = true,
                closetOldDir = closieOld.absolutePath,
                closetExistedBefore = true,
                // The stage path too: a v2 marker records where the swap happened even though the
                // directory is gone by the time recovery reads it.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_751").absolutePath,
                mediaOldDir = mediaOld.absolutePath,
                mediaExistedBefore = true
            )
        )

        val failing = FailingRestoreFs(failRenameInto = mediaDir.absolutePath)
        assertThat(RestoreCoordinator.recoverFilesystemOnly(context, failing))
            .isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        assertThat(markerPresent()).isTrue()
        assertThat(mediaOld.exists()).isTrue()
        assertThat(File(mediaOld, "old-shot.png").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        assertThat(File(mediaDir, "old-shot.png").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(mediaDir, "backup-shot.png").exists()).isFalse()
        assertThat(markerMissing()).isTrue()
    }

    /**
     * A failure to *delete* is a failure too. `deleteRecursively()` returns a Boolean and a tree with an
     * open file (or a locked child) reports false while leaving the directory in place; treating that as
     * done would clear the marker over a surviving half-restore.
     */
    @Test
    fun failedDelete_doesNotCountAsARollback() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 752,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = File(filesDir, ".closie_restore_old_752").absolutePath,
                closetExistedBefore = false    // nothing to rename back -> the live tree must be deleted
            )
        )

        val failing = FailingRestoreFs(failDelete = closieDir.absolutePath)
        assertThat(RestoreCoordinator.recoverFilesystemOnly(context, failing))
            .isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // The marker stays, so the next start still knows the Closet is a half-restore.
        assertThat(markerPresent()).isTrue()
        assertThat(closieDir.exists()).isTrue()
    }

    /**
     * A DB replay failure must keep the snapshot and the parked trees, not half-rollback the filesystem
     * and then declare itself done.
     */
    @Test
    fun dbReplayFailure_keepsSnapshotAndParkedTreesThenSucceedsOnRetry() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_753")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val snapFile = File(filesDir, ".closie_restore_dbsnap_753.json")
        // The snapshot is the *pre-restore* rows, captured before the "already committed" rows are
        // installed — that is what a replay is supposed to bring back.
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))
        val preRestoreSnapshot = snapFile.readBytes()
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 753,
                state = RestoreState.DB_COMMITTED,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                // v2, so the media paths are recorded; this device had no media directory before the
                // restore, which is what the default `mediaExistedBefore = false` says.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_753").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_753").absolutePath,
                dbSnapshot = snapFile.absolutePath
            )
        )

        // First attempt: the snapshot file is unreadable (simulating a damaged evidence file).
        snapFile.writeText("{ truncated")
        val first = RestoreCoordinator.recover(context, db)
        assertThat(first).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // Nothing was discarded, and nothing was half-rolled: the marker, the snapshot and the parked
        // tree all survive for the retry.
        assertThat(markerPresent()).isTrue()
        assertThat(snapFile.exists()).isTrue()
        assertThat(oldDir.exists()).isTrue()
        assertThat(requireMarker().state).isNotEqualTo(RestoreState.ROLLED_BACK)

        // Second attempt: evidence repaired by restoring the bytes the first write produced. The
        // database is deliberately left holding the backup's rows, so the replay has real work to do
        // and a no-op cannot be mistaken for success.
        snapFile.writeBytes(preRestoreSnapshot)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly("备份里的记录")

        assertThat(RestoreCoordinator.recover(context, db)).isEqualTo(RecoveryOutcome.Completed)

        assertMutuallyConsistent(expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT), expectedMediaFiles = emptyList())
        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  COMMITTED cleanup
    // ------------------------------------------------------------------

    /**
     * Once COMMITTED, the new data is official and a cleanup failure must never roll it back.
     *
     * COMMITTED means the Closet, the media and the database all passed their health checks — the
     * restore *succeeded*. Failing to delete the parked copy afterwards is housekeeping, and answering
     * housekeeping failure with a rollback would throw away a successful restore to tidy up after it.
     * So: stay COMMITTED, keep the marker, retry next start.
     */
    @Test
    fun committedCleanupFailure_keepsTheNewDataAndRetriesCleanup() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_760")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        // The live Closet is the backup's version — i.e. the restore already succeeded.
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        val snapFile = File(filesDir, ".closie_restore_dbsnap_760.json")
        snapFile.writeText("{}")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 760,
                state = RestoreState.COMMITTED,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                dbSnapshot = snapFile.absolutePath
            )
        )

        val failing = FailingRestoreFs(failDelete = oldDir.absolutePath)
        assertThat(RestoreCoordinator.recoverFilesystemOnly(context, failing))
            .isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        // The successful restore is untouched: new Closet still live, new database still there,
        // marker still COMMITTED so the retry knows not to roll back.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(OLD_CAPTURE_TEXT)
        assertThat(requireMarker().state).isEqualTo(RestoreState.COMMITTED)
        assertThat(oldDir.exists()).isTrue()

        // Retry with the fault removed: cleanup completes, the marker goes last.
        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        assertThat(oldDir.exists()).isFalse()
        assertThat(snapFile.exists()).isFalse()
        assertThat(markerMissing()).isTrue()
        // …and the new data is *still* the live data.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /**
     * The marker is deleted *after* the artefacts, so a cleanup that dies partway is resumable rather
     * than orphaning a parked tree with nothing left to point at it.
     */
    @Test
    fun committedCleanup_removesArtefactsBeforeTheMarker() = runTest {
        val filesDir: File = context.filesDir
        val oldDir = File(filesDir, ".closie_restore_old_761")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 761,
                state = RestoreState.COMMITTED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        assertThat(RestoreCoordinator.recoverFilesystemOnly(context)).isEqualTo(RecoveryOutcome.Completed)

        assertThat(oldDir.exists()).isFalse()
        assertThat(markerMissing()).isTrue()
        // COMMITTED cleanup never renames the parked tree back: the new version is the correct one.
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(NEW_ITEM_ID)
    }

    /**
     * The mirror of [assertMutuallyConsistent] for a **committed** restore: all three surfaces must
     * now hold the *backup's* version.
     *
     * Kept as a separate helper rather than parameterised, because the two directions assert genuinely
     * different things — the rollback case must prove the backup's data is *absent*, and the committed
     * case must prove the user's old data is *absent*. Folding them into one flagged function would
     * make it possible to assert the wrong direction by passing a boolean, which is exactly the mistake
     * the two named helpers exist to make impossible.
     */
    private suspend fun assertCommittedConsistent() {
        // 1. Life OS database holds the backup's rows, and none of the user's pre-restore ones.
        val captureTexts = db.captureDao().getAllOnce().mapNotNull { it.rawText }
        assertThat(captureTexts).contains("备份里的记录")
        assertThat(captureTexts).doesNotContain(OLD_CAPTURE_TEXT)
        assertThat(db.planDao().getAllOnce().map { it.title }).contains("备份里的计划")

        // 2. Every media row still resolves to a real, non-empty file — the commit did not leave rows
        //    pointing at bytes that a rollback removed.
        db.mediaDao().getAllResources().forEach { row ->
            val path = row.managedPath
            assertThat(path).isNotNull()
            assertThat(File(path!!).isFile).isTrue()
        }

        // 3. The Closet is the *backup's* wardrobe, not the user's previous one.
        val itemsJson = File(File(context.filesDir, "closie"), "items.json").readText()
        assertThat(itemsJson).contains(NEW_ITEM_ID)
        assertThat(itemsJson).doesNotContain(OLD_ITEM_ID)
    }

    /**
     * Asserts the Closet, the Life OS database and the Life OS media directory all describe the same
     * version of the world.
     *
     * This is the assertion the defect needed. Any half-restore is caught by exactly one of these
     * clauses, and the failure message names which surface disagreed, so a regression is diagnosed
     * rather than merely detected.
     */
    private suspend fun assertMutuallyConsistent(
        expectedCaptureTexts: List<String>,
        expectedMediaFiles: List<String>
    ) {
        // 1. Life OS database: the user's rows, and none of the backup's.
        val captureTexts = db.captureDao().getAllOnce().mapNotNull { it.rawText }
        assertThat(captureTexts).containsExactlyElementsIn(expectedCaptureTexts)
        assertThat(captureTexts).doesNotContain("备份里的记录")
        assertThat(db.planDao().getAllOnce()).isEmpty()
        assertThat(db.referenceDao().getAllOnce()).isEmpty()

        // 2. Life OS media rows must be the user's rows — not the backup's. A restore that committed
        //    the database but reverted the filesystem would show up here as the backup's ids.
        val resourceIds = db.mediaDao().getAllResources().map { it.id }
        assertThat(resourceIds).containsExactlyElementsIn(
            expectedMediaFiles.indices.map { "local-res-$it" }
        )

        // 3. Every row's managedPath must still resolve to a real, non-empty file.
        db.mediaDao().getAllResources().forEach { row ->
            val path = row.managedPath
            assertThat(path).isNotNull()
            assertThat(File(path!!).isFile).isTrue()
            assertThat(File(path).length()).isGreaterThan(0L)
        }

        // 4. The user's media files must exist on disk with their original bytes.
        expectedMediaFiles.forEach { p ->
            assertThat(File(p).isFile).isTrue()
            assertThat(File(p).length()).isGreaterThan(0L)
        }

        // 5. The Closet must be the *old* wardrobe, not the backup's.
        val itemsJson = File(File(context.filesDir, "closie"), "items.json").readText()
        assertThat(itemsJson).contains(OLD_ITEM_ID)
        assertThat(itemsJson).doesNotContain(NEW_ITEM_ID)
    }

    /**
     * Every scratch artefact a restore may leave behind, across both filesystem surfaces.
     *
     * Three prefixes, because the restore now parks *two* independent directory trees (Closet and Life
     * media) plus a database snapshot. Checking only the Closet prefix — as this helper used to — would
     * happily report "clean" while a parked media tree and a snapshot file sat in `filesDir`.
     *
     * The marker itself (`.life_restore_intent.json`) is deliberately **not** in this set: it is durable
     * protocol state, and several tests assert on its presence or absence separately.
     */
    private fun strayRestoreDirs(): List<String> {
        val prefixes = listOf(".closie_restore_", ".life_media_restore_", ".life_restore_dbsnap_")
        return context.filesDir.listFiles().orEmpty()
            .filter { f -> prefixes.any { f.name.startsWith(it) } }
            .map { it.name }
            .toList()
    }

    // --- marker helpers -------------------------------------------------------------------------
    // `RestoreIntentStore.read` is deliberately three-valued now (Missing / Success / Corrupt), so
    // these read as plainly as the assertions they replaced while still letting a test distinguish
    // "no marker" from "a marker I could not parse".

    /** True when there is genuinely no marker on disk. */
    private fun markerMissing(): Boolean =
        RestoreIntentStore.read(context) is AtomicJson.ReadResult.Missing

    /** True when a marker exists — valid or corrupt. A corrupt marker is still evidence. */
    private fun markerPresent(): Boolean =
        RestoreIntentStore.read(context) !is AtomicJson.ReadResult.Missing

    /** The marker, failing the test with the parse error if it is corrupt. */
    private fun requireMarker(): RestoreIntent =
        when (val r = RestoreIntentStore.read(context)) {
            is AtomicJson.ReadResult.Success -> r.value
            is AtomicJson.ReadResult.Missing -> throw AssertionError("期望存在恢复标记，但没有找到")
            is AtomicJson.ReadResult.Corrupt -> throw AssertionError("恢复标记损坏", r.cause)
        }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    private companion object {
        const val NEW_ITEM_ID = "backup-item-only"
        const val OLD_ITEM_ID = "local-item-only"
        const val OLD_CAPTURE_TEXT = "用户原有的记录"
        const val NEW_CAPTURE_TEXT = "备份里的记录"
    }

    /** Builds a v2 backup whose wardrobe contains a marker item no local state has. */
    private suspend fun makeBackupWithNewContent(): Uri {
        wardrobe.createItem(
            com.qq.closie.data.model.ClothingItem(
                id = NEW_ITEM_ID,
                name = "备份里的衣服",
                category = "上衣",
                status = com.qq.closie.data.model.ItemStatus.OWNED
            )
        )
        seedPlan("备份里的计划")
        seedReference("备份里的资料")

        val uri = outputUri("coordinator")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()
        return uri
    }

    /**
     * Builds a backup carrying [mediaFileNames], then installs a *distinct* old state and wipes the
     * database, so the backup and the pre-restore world are unambiguously different versions.
     *
     * The separation matters more than it looks. If the backup were exported from the very state the
     * test later asserts on, a half-restore would still satisfy every assertion — both halves would
     * hold the same rows, and the test would pass while the bug it targets was present.
     *
     * @return the backup URI plus the old media paths the restore must leave intact.
     */
    private suspend fun backupThenInstallOldState(
        mediaFileNames: List<String>,
        oldBytes: List<ByteArray>
    ): Pair<Uri, List<String>> {
        // --- Phase 1: the backup's own content, with its own media. ---
        mediaFileNames.forEachIndexed { i, name ->
            val path = writeManagedMedia(name, oldBytes[i])
            insertMediaRow("bk-asset-$i", "bk-res-$i", path, oldBytes[i])
        }
        seedCapture("备份里的记录")
        val backup = makeBackupWithNewContent()

        // --- Phase 2: the user's actual, unrelated data. ---
        db.planDao().deleteAll()
        db.referenceDao().deleteAll()
        db.captureDao().deleteAll()
        db.mediaDao().deleteAllLinks()
        db.mediaDao().deleteAllResources()
        db.mediaDao().deleteAllAssets()

        // The Closet must also be an *unrelated* wardrobe, or reverting the swap would be
        // indistinguishable from leaving the backup's version in place.
        wardrobe.deleteItem(NEW_ITEM_ID)
        wardrobe.createItem(
            com.qq.closie.data.model.ClothingItem(
                id = OLD_ITEM_ID,
                name = "用户原有的衣服",
                category = "下装",
                status = com.qq.closie.data.model.ItemStatus.OWNED
            )
        )

        val oldPaths = mediaFileNames.mapIndexed { i, name ->
            writeManagedMedia("local-$name", byteArrayOf((i + 10).toByte(), 20, 30))
        }
        oldPaths.forEachIndexed { i, path ->
            insertMediaRow("local-asset-$i", "local-res-$i", path, byteArrayOf((i + 10).toByte(), 20, 30))
        }
        seedCapture(OLD_CAPTURE_TEXT)

        return backup to oldPaths
    }

    /**
     * Parks the exact on-disk shape of a process killed inside the database transaction, and returns
     * the snapshot file the marker names.
     *
     * The layout it produces is not invented — it is the one [RestoreCoordinator.restore] leaves when
     * it dies between writing `DB_COMMITTING` and returning from the transaction:
     *
     *  - `closie/` holds the **backup's** wardrobe (the swap already happened),
     *  - `.closie_restore_old_$id/` holds the user's previous wardrobe,
     *  - the marker says `DB_COMMITTING` and names the snapshot,
     *  - the snapshot holds the rows the *database* had before the transaction.
     *
     * ### Every field a real `DB_COMMITTING` marker carries, and why each one is set
     *
     * The rule this fixture follows — and the rule the whole suite has to follow — is that *every other*
     * piece of evidence must be valid so that the one condition under test is the only thing that can
     * produce the observed behaviour. A fixture that is also missing, say, `mediaStageDir` makes
     * `validateRecoveryEvidence` refuse the marker first, and the test then passes for a reason unrelated
     * to its name: a false positive that keeps passing after the real behaviour breaks.
     *
     *  - `includesLifeOs = true` — the state is reachable only through the v2 branch that writes the
     *    snapshot and opens the transaction. A `DB_COMMITTING` marker with `includesLifeOs = false` is
     *    self-contradictory, and [requiresDatabaseRecovery] deliberately reads it as "handle the
     *    database" rather than as a v1 restore, so it is not a shape a test should be built on.
     *  - `closetStageDir` / `closetOldDir` — the marker records **where the swap happened**, and the
     *    stage path is recorded even though (precisely because) the directory is gone by now: by the
     *    time a marker says `DB_COMMITTING`, `closetStage` has already been renamed into `closie/`. Both
     *    are `dbSnapshot`'s peers in `missingRecoveryEvidence`, so omitting the stage path made this
     *    fixture describe a marker no device can write.
     *  - `closetExistedBefore = true` — `closie/` really did exist before the restore (the fixture
     *    writes the user's wardrobe into the parked tree), and the flag is what tells the revert path
     *    that `old` holds real data to rename back rather than a tree to delete.
     *  - the media fields — a v2 restore swaps media too, so a marker that omits them describes a
     *    restore that never published the media surface. Both paths are set: the stage path as metadata
     *    naming where the swap happened, and the parked directory as a real tree holding the user's
     *    previous media.
     *  - `mediaExistedBefore = true` — same argument as the Closet flag, for the same reason.
     *
     * @param snapshotOf the database to snapshot. Pass the database **after** seeding the user's
     *   pre-restore rows and **before** mutating it to the backup's, so the snapshot describes the
     *   state recovery is supposed to restore. Defaults to the field `db`, which is already at that
     *   state in most tests.
     */
    // FIX7-ANCHOR parkDbCommittingState-definition-start.
    //
    // This marker exists so the *definition* of this helper is findable by grep, which it previously was
    // not in a way a reader could rely on. A patch hunk's `@@` header carries the enclosing *class*
    // (`class RestoreCoordinatorTest {`), not the enclosing function, so `grep "parkDbCommittingState"`
    // over a patch file matches only the call sites in the added lines — the definition itself appears
    // inside a hunk whose header names neither the function nor its line. That made it look, on a casual
    // read, as though the helper's body had never been changed, when in fact the definition was rewritten
    // to carry the full v2 evidence set (see the KDoc above). Searching for this marker instead finds the
    // definition unambiguously.
    //
    // The fields below are the whole point of the fixture: a real `DB_COMMITTING` marker is reachable
    // only after `MEDIA_SWAPPED`, so `requiresLifeMediaRecovery()` is true and `validateRecoveryEvidence`
    // demands both `mediaStageDir` and `mediaOldDir` before any mutation. A fixture missing them would
    // never enter the recovery path it claims to exercise.
    private suspend fun parkDbCommittingState(
        id: Long,
        snapshotOf: LifeDatabase = db
    ): File {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_$id")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // The media surface, parked the same way the Closet is. A v2 restore publishes both, so a
        // fixture that only describes the Closet half would not be the shape this state is reached in.
        val mediaDir = File(filesDir, "media")
        val mediaOldDir = File(filesDir, ".life_media_restore_old_$id")
        // The swap has *already happened*, so whatever the user had in the live tree is now parked in
        // `old` — including files a caller seeded before calling this fixture (their media rows point at
        // those paths, and recovery will only find them if they were parked here rather than left in the
        // live tree the revert is about to delete). Synthesising a fresh `old` directory instead would
        // describe a swap that silently destroyed the user's media, and every caller that asserts on its
        // own media files would be asserting against a tree recovery was never going to restore.
        mediaOldDir.mkdirs()
        File(mediaOldDir, "old-media.txt").writeText("user's previous media")
        mediaDir.listFiles()?.forEach { f ->
            val target = File(mediaOldDir, f.name)
            if (!target.exists()) f.renameTo(target) else f.deleteRecursively()
        }
        mediaDir.mkdirs()
        File(mediaDir, "new-media.txt").writeText("backup's media")

        val snapFile = File(filesDir, ".closie_restore_dbsnap_$id.json")
        // Written through the same helper production uses, so the fixture and the reader agree on the
        // serialisation by construction rather than by two `Gson` instances happening to match.
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(snapshotOf))

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = id,
                state = RestoreState.DB_COMMITTING,
                // The transaction branch is v2-only, so the marker that records it says so.
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                // Gone from disk by now — renamed into `closie/` — but still named, because the marker
                // records where the swap happened and `missingRecoveryEvidence` demands the path be
                // present. Leaving it out made this fixture describe a marker no device can produce,
                // and the evidence check then refused the marker before any snapshot logic ran.
                closetStageDir = File(filesDir, ".closie_restore_stage_$id").absolutePath,
                // Both media paths, because a v2 marker always carries both and `missingRecoveryEvidence`
                // demands them: the stage path is *metadata* naming where the swap happened, so it is
                // recorded even though — indeed precisely because — the directory is gone afterwards.
                // Omitting it made this fixture describe a marker no real device can write, and the
                // evidence check correctly refused to act on it.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_$id").absolutePath,
                mediaOldDir = mediaOldDir.absolutePath,
                dbSnapshot = snapFile.absolutePath,
                // Both surfaces really did exist before the restore: the parked trees hold the user's
                // data, which is exactly what makes them worth renaming back.
                closetExistedBefore = true,
                mediaExistedBefore = true
            )
        )
        return snapFile
    }

    private fun outputUri(name: String): Uri {
        val f = File(context.cacheDir, "$name-${System.nanoTime()}.zip")
        createdUris += Uri.fromFile(f)
        return Uri.fromFile(f)
    }

    /**
     * Writes a v2 archive from an explicit [LifeBackupPayload] plus any media bytes. Used to exercise the
     * staging validator: pass an entry in [LifeBackupPayload.mediaResources] whose [MediaResourceRecord.archiveFileName]
     * is absent from [mediaFiles] and [BackupImporter.stage] will fail during staging.
     */
    private fun writeV2Archive(uri: Uri, payload: LifeBackupPayload, mediaFiles: Map<String, ByteArray>) {
        val gson = com.google.gson.Gson()
        val manifest = BackupManifest(formatVersion = 2, schemaVersion = 1, includesLifeOs = true)
        val textEntries = mapOf(
            "manifest.json" to gson.toJson(manifest),
            "data/items.json" to "[]",
            "data/wear.json" to "[]",
            "data/wash.json" to "[]",
            "data/ootds.json" to "[]",
            "data/outfits.json" to "[]",
            "life/data.json" to gson.toJson(payload)
        )
        context.contentResolver.openOutputStream(uri)!!.use { raw ->
            ZipOutputStream(raw).use { zip ->
                textEntries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                mediaFiles.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry("life/media/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun writeManagedMedia(fileName: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private suspend fun insertMediaRow(assetId: String, resourceId: String, managedPath: String, bytes: ByteArray) {
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = assetId, mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = resourceId,
                mediaAssetId = assetId,
                role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png",
                managedPath = managedPath,
                sizeBytes = bytes.size.toLong(),
                createdAt = 1L
            )
        )
    }

    private suspend fun seedCapture(text: String): String {
        val id = "cap-${System.nanoTime()}"
        captureRepo.create(id = id, source = CaptureSource.SHARE, rawText = text)
        return id
    }

    private suspend fun seedReference(title: String) =
        referenceRepo.create(title = title, referenceType = com.qq.closie.life.reference.ReferenceType.ARTICLE)

    private suspend fun seedPlan(title: String) = planRepo.create(title = title)

    /** Writes the five core wardrobe files, so the directory is recognised as valid. */
    private fun writeWardrobeFiles(dir: File, itemsJson: String) {
        File(dir, "drafts").mkdirs()
        File(dir, "items.json").writeText(itemsJson)
        File(dir, "wear.json").writeText("[]")
        File(dir, "wash.json").writeText("[]")
        File(dir, "ootds.json").writeText("[]")
        File(dir, "outfits.json").writeText("[]")
    }

    /** Writes one file into a media directory, creating it. Used to make the media surface detectable. */
    private fun writeMediaFile(dir: File, name: String, contents: String) {
        dir.mkdirs()
        File(dir, name).writeText(contents)
    }

    // ------------------------------------------------------------------
    //  RealRestoreFs.park: a fresh-only contract, with no fabricated evidence
    // ------------------------------------------------------------------

    /**
     * `park` refuses to invent a parked tree when the live directory it was told to preserve is missing.
     *
     * ### The fabrication this forbids, and why it was worse than failing
     *
     * The old `park` had an `else if (existedBefore)` branch that did `old.mkdirs()` — creating an
     * **empty** directory and reporting success. The stated reason was to keep the invariant
     * "`old.exists()` means there is an original to restore", which the revert logic relies on. It
     * achieved the opposite: the placeholder is an empty directory that is *not the user's data*, and
     * `revertCloset` treats `old.exists()` as exactly that data. So recovery would have renamed the empty
     * placeholder over the live Closet, cleared the marker, and reported the user's wardrobe restored —
     * destroying it and removing the evidence in one move.
     *
     * Failing loudly is strictly better. The marker and the real parked tree (if any) survive, the next
     * start retries, and nothing has been overwritten with a directory that only *looks* like data.
     *
     * A caller reaching this state is not something to paper over: either something external deleted the
     * user's tree or the restore's bookkeeping is wrong. In both cases the rename that follows cannot
     * produce a correct result.
     */
    @Test
    fun park_whenTheOriginalWasExpectedButIsGone_refusesInsteadOfFabricatingAnEmptyOldDir() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-missing")
        val old = File(filesDir, ".closie_restore_old_contract1")
        // Deliberately: `live` does not exist, and the caller claimed it did.
        live.deleteRecursively()
        old.deleteRecursively()

        val failure = runCatching { RealRestoreFs.park(live, old, existedBefore = true) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        // The important assertion: no fake `old` was left behind for the revert logic to mistake for the
        // user's data. If this ever regresses, the rename-back would silently restore an empty directory.
        assertThat(old.exists()).isFalse()
    }

    /**
     * The legitimate `existedBefore = true` case: the live tree is moved aside intact.
     *
     * The positive control for the test above — the refusal must not be so eager that a normal park stops
     * working. The parked tree must hold the user's *content*, not merely exist.
     */
    @Test
    fun park_movesTheLiveTreeAsideWhenItIsActuallyThere() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-present")
        val old = File(filesDir, ".closie_restore_old_contract2")
        old.deleteRecursively()
        writeWardrobeFiles(live, itemsJson = """[{"id":"user-item","name":"用户的衣服"}]""")

        RealRestoreFs.park(live, old, existedBefore = true)

        // Moved, not copied: the live slot is empty for the staged tree to take.
        assertThat(live.exists()).isFalse()
        assertThat(old.exists()).isTrue()
        // …and the parked tree carries the user's item, which is the whole point of parking it.
        assertThat(File(old, "items.json").readText()).contains("user-item")
    }

    /**
     * `park` refuses to merge two generations of data when the parked slot is already occupied.
     *
     * `File.renameTo` onto an existing **directory** does not replace it — on most filesystems it moves
     * the source *inside* it. Silently succeeding there would leave one directory holding both the user's
     * wardrobe and the backup's, and no way to tell them apart afterwards. Since a fresh restore owns the
     * parked path (it is derived from the restore id), a pre-existing directory means the bookkeeping is
     * wrong and the run must stop.
     */
    @Test
    fun park_whenTheParkingSlotIsAlreadyOccupied_refusesToMergeTwoGenerations() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-occupied")
        val old = File(filesDir, ".closie_restore_old_contract3")
        writeWardrobeFiles(live, itemsJson = """[{"id":"new-gen","name":"这一代"}]""")
        writeWardrobeFiles(old, itemsJson = """[{"id":"stale-gen","name":"上一代"}]""")

        val failure = runCatching { RealRestoreFs.park(live, old, existedBefore = true) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        // Both trees are untouched: nothing was merged and nothing was lost.
        assertThat(File(live, "items.json").readText()).contains("new-gen")
        assertThat(File(old, "items.json").readText()).contains("stale-gen")
    }

    /**
     * `existedBefore = false` does **not** license an occupied parking slot.
     *
     * This is the resurrection window the previous revision left open. The `old.exists()` guard sat inside
     * the `existedBefore == true` branch, so with `existedBefore = false` a stale parked directory was
     * simply ignored:
     *
     * ```
     *   existedBefore = false      (no live tree when the restore started)
     *   old/ still present         (a leftover from an earlier, finished restore)
     *   -> park is a no-op
     *   -> the staged tree is published as live
     *   -> a later rollback sees old/.exists() and renames the STALE generation over live/
     * ```
     *
     * `revertCloset` and `revertMedia` both open with "if the parked tree exists, it is the user's data",
     * so `old` is not inert litter — it is a directory that will be treated as the user's wardrobe the
     * moment anything looks at it. The user would end up with data from two restores ago and a marker
     * reporting a successful rollback.
     *
     * A free parking slot is therefore a **precondition** of the operation, not a consequence of the flag,
     * and is checked before the flag is consulted at all.
     */
    @Test
    fun park_whenNoOriginalButParkingSlotAlreadyExists_refusesAndPreservesIt() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-stale-slot")
        val old = File(filesDir, ".closie_restore_old_contract6")
        // Deliberately: no live tree (consistent with `existedBefore = false`) …
        live.deleteRecursively()
        // … but the parking slot is occupied by a stale generation.
        writeWardrobeFiles(old, itemsJson = """[{"id":"stale-gen","name":"上一次恢复留下的"}]""")

        val failure = runCatching { RealRestoreFs.park(live, old, existedBefore = false) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        // The stale tree is preserved exactly as it was: refusing must not destroy the thing whose
        // presence is the problem, or the diagnosis disappears with the evidence.
        assertThat(File(old, "items.json").readText()).contains("stale-gen")
        // …and nothing was fabricated in the live slot either.
        assertThat(live.exists()).isFalse()
    }

    /**
     * The `existedBefore = false` contract: there was no original, so nothing is parked — but a live
     * tree appearing anyway is a contradiction, not a licence to overwrite it.
     *
     * This is the other half of "the decision comes from the marker, not from the disk". The marker says
     * the user had no Closet; if one is there, it was written by something the restore does not know
     * about, and silently renaming it away would discard it. The revert path handles the "no original"
     * case by *deleting* the swapped-in tree, which is only correct when the flag is truthful — so the
     * flag being contradicted must stop the run.
     */
    @Test
    fun park_whenNoOriginalWasDeclaredButALiveTreeIsPresent_refuses() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-unexpected")
        val old = File(filesDir, ".closie_restore_old_contract4")
        old.deleteRecursively()
        writeWardrobeFiles(live, itemsJson = """[{"id":"surprise","name":"意外的数据"}]""")

        val failure = runCatching { RealRestoreFs.park(live, old, existedBefore = false) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        // The unexpected tree is still there, untouched, for someone to look at.
        assertThat(File(live, "items.json").readText()).contains("surprise")
        assertThat(old.exists()).isFalse()
    }

    /**
     * The normal `existedBefore = false` case is a genuine no-op.
     *
     * The positive control: a first-ever restore has nothing to park, and that must not be an error.
     */
    @Test
    fun park_whenThereWasGenuinelyNoOriginal_isANoOp() {
        val filesDir = context.filesDir
        val live = File(filesDir, "closie-contract-absent")
        val old = File(filesDir, ".closie_restore_old_contract5")
        live.deleteRecursively()
        old.deleteRecursively()

        RealRestoreFs.park(live, old, existedBefore = false)

        assertThat(live.exists()).isFalse()
        // Critically: no placeholder directory was created, so the revert path will delete the
        // swapped-in tree rather than rename an empty stand-in over it.
        assertThat(old.exists()).isFalse()
    }

    // ------------------------------------------------------------------
    //  recoverFilesystemOnly: the completion contract
    // ------------------------------------------------------------------

    /**
     * The filesystem-only pass concludes a marker that has nothing outstanding — and *proves* it.
     *
     * ### What the old behaviour got wrong, in both directions
     *
     * `recoverFilesystemOnly` used to return `Completed` unconditionally as soon as the filesystem repair
     * did not throw, and never cleared the marker at all. So a marker whose database half was still
     * outstanding was reported as finished, while a marker that was genuinely finished stayed on disk
     * forever. The second half is the less obvious one: a marker that is never cleared makes every
     * subsequent start redo the work, and — because the gate keys off the same verdict — makes a
     * completed recovery look permanently pending.
     *
     * This test pins the concluding direction: a pre-transaction marker with nothing left to do must come
     * back `Completed` **with the marker actually gone**, verified by re-reading through the same
     * `AtomicJson` semantics rather than by trusting the delete.
     */
    @Test
    fun recoverFilesystemOnly_concludesAMarkerWithNothingOutstanding_andRemovesIt() {
        val filesDir = context.filesDir
        val oldDir = File(filesDir, ".closie_restore_old_fs1")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(File(filesDir, "closie"), itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        val snapFile = File(filesDir, ".closie_restore_dbsnap_fs1.json")
        snapFile.writeText("{}")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9001,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                dbSnapshot = snapFile.absolutePath,
                closetExistedBefore = true
            )
        )

        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        // Concluded means concluded: the marker is verifiably gone…
        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Missing::class.java)
        // …and so are the leftovers, including the snapshot, which is pure liability once replayed.
        assertThat(oldDir.exists()).isFalse()
        assertThat(snapFile.exists()).isFalse()
    }

    /**
     * The other direction: a marker with an outstanding database half must come back `RetryRequired`
     * **with the marker and the snapshot intact** — and must not be mistaken for "the Closet is broken".
     *
     * This is the assertion that makes the filesystem-only pass safe to run without a database. The
     * tempting "clean up and report done" behaviour would delete the snapshot, and the snapshot is the
     * only record of the rows a later start must replay. The parked trees are also preserved, because
     * nothing about them is finished either.
     *
     * Note what the Closet looks like at the end: it **is** repaired. `RetryRequired` here means "the
     * protocol has outstanding durable work", not "the wardrobe was left half-swapped" — the distinction
     * the caller must not collapse, and the reason this test asserts both facts at once.
     */
    @Test
    fun recoverFilesystemOnly_keepsTheMarkerAndSnapshotWhenTheDatabaseHalfIsOutstanding() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = parkDbCommittingState(id = 9002)

        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // The Closet repair happened anyway — this is the "RetryRequired is not about the Closet" part.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).doesNotContain(NEW_ITEM_ID)
        // …and every artefact the database half depends on survives.
        assertThat(markerPresent()).isTrue()
        val pending = requireMarker()
        assertThat(pending.state).isEqualTo(RestoreState.DB_COMMITTING)
        assertThat(pending.dbSnapshot).isEqualTo(snapFile.absolutePath)
        assertThat(snapFile.isFile).isTrue()
        // The parked trees survive too: a later pass may still need to reason about them.
        assertThat(strayRestoreDirs()).isNotEmpty()
    }

    /**
     * A corrupt marker is never a clean slate, for this entry point too.
     *
     * The three-valued read exists so that "unreadable" cannot be confused with "absent" — and this is the
     * entry point that used to have the most to lose from that confusion, since a clean slate is what
     * authorises deleting the parked trees.
     */
    @Test
    fun recoverFilesystemOnly_treatsACorruptMarkerAsRetryNotAsCleanSlate() {
        val filesDir = context.filesDir
        val oldDir = File(filesDir, ".closie_restore_old_fs2")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        RestoreIntentStore.markerFile(context).writeText("{ \"id\": 9003, \"state\":")

        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // Nothing was deleted on the strength of an unreadable record.
        assertThat(RestoreIntentStore.markerFile(context).exists()).isTrue()
        assertThat(oldDir.exists()).isTrue()
        assertThat(File(oldDir, "items.json").readText()).contains(OLD_ITEM_ID)
    }

    /**
     * No marker at all is the one case that is genuinely no work — and it must touch nothing.
     */
    @Test
    fun recoverFilesystemOnly_withNoMarker_reportsNoWork() {
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        val before = File(closieDir, "items.json").readBytes()

        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(outcome).isEqualTo(RecoveryOutcome.NoWork)
        assertThat(File(closieDir, "items.json").readBytes()).isEqualTo(before)
    }

    // ------------------------------------------------------------------
    //  A marker that contradicts its own flags is handled conservatively
    // ------------------------------------------------------------------

    /**
     * `DB_COMMITTING` + `includesLifeOs = false`: the contradictory marker must **not** be treated as
     * "nothing to do", because a database half may genuinely be in flight.
     *
     * ### Why this fixture exists despite being unreachable in normal operation
     *
     * A real restore writes `DB_COMMITTING` only inside the `if (includesLifeOs)` branch, so the flag can
     * never be false there on a device. But the marker is *durable data read from disk*, and the whole
     * point of the three-valued read is that recovery must not assume the file is well-formed. A torn or
     * hand-edited marker, or one written by a future build with a different branch order, can present this
     * combination — and it is precisely the combination where the wrong answer is destructive.
     *
     * Reading it as "v1, no Life OS" would make recovery consume the parked trees, delete the marker and
     * the snapshot, and walk past a database that may hold the backup's rows. The conservative reading is
     * `requiresDatabaseRecovery() == true`, which preserves everything for a later pass. This test pins
     * that, so the asymmetry in the truth table cannot be "simplified" away.
     */
    @Test
    fun dbCommittingMarkerWithoutLifeOsFlag_isStillTreatedAsNeedingDatabaseRecovery() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_contradiction")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")
        val snapFile = File(filesDir, ".closie_restore_dbsnap_contradiction.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9004,
                state = RestoreState.DB_COMMITTING,
                // The contradiction under test.
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                // The v2 media paths, even though the flag denies Life OS.
                //
                // `includesLifeOs` and "this marker carries v2 media metadata" are different claims: the
                // flag records what the *archive* had, the paths record what this *marker file* persisted.
                // The state is what makes the paths meaningful — `DB_COMMITTING` is written only from the
                // v2 branch — and `missingRecoveryEvidence` demands them for exactly that reason, whatever
                // the flag says.
                //
                // Supplying them is what keeps this test about its actual subject. Without them the marker
                // is simply *incomplete*, `validateRecoveryEvidence` refuses it before the predicate under
                // test is consulted, and the test passes without ever exercising the flag/predicate
                // disagreement that the asymmetry in the truth table exists for. The paths need no
                // directory behind them; `mediaExistedBefore` defaults to `false`, recording that this
                // device had no media tree.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_contradiction").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_contradiction").absolutePath,
                dbSnapshot = snapFile.absolutePath,
                closetExistedBefore = true
            )
        )

        // The predicate itself, first: it must be `true` despite the flag.
        assertThat(requireMarker().requiresDatabaseRecovery()).isTrue()
        // …and the marker must be *actionable*, so that the outcome below is decided by the database
        // predicate rather than by the evidence check.
        assertThat(requireMarker().missingRecoveryEvidence()).isNull()

        // And the entry point must agree — the marker survives, so a later pass can finish the database
        // half rather than inheriting a clean slate over a half-applied transaction.
        val outcome = RestoreCoordinator.recoverFilesystemOnly(context)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(markerPresent()).isTrue()
        assertThat(snapFile.isFile).isTrue()
    }

    // ------------------------------------------------------------------
    //  Contradictory markers: the media half must be repaired too
    // ------------------------------------------------------------------

    /**
     * `DB_COMMITTING` + `includesLifeOs = false` **with complete v2 evidence**: the database, the Closet
     * *and* the media must all be rolled back together.
     *
     * ### The half-restore this pins
     *
     * [RestoreIntent.requiresDatabaseRecovery] was corrected to treat these states as "the database needs
     * attention regardless of the flag", but [revertMedia] still opened with `if (!includesLifeOs) return`.
     * The two decisions then disagreed about the same marker, and the result was a **permanent** split:
     *
     * ```
     *   DB      -> replayed back to the user's rows
     *   Closet  -> reverted to the user's wardrobe
     *   media   -> skipped, because the flag says "no Life OS"      <- still the backup's
     *   cleanup -> deletes mediaOldDir and clears the marker
     * ```
     *
     * Two surfaces on the user's version, one on the backup's, and the evidence that would have detected
     * it deleted. That is strictly worse than leaving everything alone, because nothing on disk records
     * the inconsistency any more.
     *
     * The state is what proves the media was swapped (`DB_COMMITTING` is written only inside the v2 branch,
     * *after* `MEDIA_SWAPPED`), so it is the state that must drive the media revert — see
     * [RestoreIntent.requiresLifeMediaRecovery].
     *
     * ### Why the database is put into a *different* state first
     *
     * An earlier revision of this test seeded nothing, so the "database was replayed" assertions were
     * satisfied by a database that had never changed — the replay could have been skipped entirely and
     * every assertion would still have passed. The snapshot is therefore written from an explicitly
     * different, earlier version of the data (an OLD capture row), and the live database is then moved to
     * the backup's version (the OLD row deleted, a NEW row inserted) before recovery runs. Only a real
     * replay can turn the NEW row back into the OLD one, which is what the assertions now check.
     */
    @Test
    fun contradictoryDbCommittingMarker_withCompleteV2Evidence_recoversDbClosetAndMediaTogether() = runTest {
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")

        // ---- 1. The pre-restore ("OLD") state, and the snapshot taken from it. --------------------
        val oldCaptureId = seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = File(filesDir, ".closie_restore_dbsnap_contradiction.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))

        // ---- 2. The live state is now the BACKUP's, on all three surfaces. ------------------------
        // Database: the OLD row is gone and a NEW one is in its place.
        captureRepo.delete(oldCaptureId)
        seedCapture(NEW_CAPTURE_TEXT)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(NEW_CAPTURE_TEXT)

        // Closet: the parked tree is the user's, the live tree is the backup's.
        val closetOld = File(filesDir, ".closie_restore_old_contradiction")
        writeWardrobeFiles(closetOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // Media: swapped in exactly the shape a real v2 restore leaves behind.
        val mediaLive = File(filesDir, MediaStoreImporter.MEDIA_DIR)
        val mediaOld = File(filesDir, ".life_media_restore_old_contradiction")
        val mediaStage = File(filesDir, ".life_media_restore_stage_9004")
        writeMediaFile(mediaOld, "old-photo.jpg", "用户原有的照片")
        writeMediaFile(mediaLive, "new-photo.jpg", "备份里的照片")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9004,
                state = RestoreState.DB_COMMITTING,
                // The contradiction: the state says v2, the flag says v1.
                includesLifeOs = false,
                closetOldDir = closetOld.absolutePath,
                mediaOldDir = mediaOld.absolutePath,
                // The stage path is *metadata*: it names where the swap happened. After the rename it
                // legitimately no longer exists, which is why evidence completeness is checked on the
                // field and never on the file. Listed here because a real v2 marker always carries it —
                // a fixture that omitted it was itself incomplete and made the marker undecidable.
                mediaStageDir = mediaStage.absolutePath,
                dbSnapshot = snapFile.absolutePath,
                closetExistedBefore = true,
                mediaExistedBefore = true
            )
        )

        // The fixture is genuinely complete — otherwise this test would be asserting a refusal.
        assertThat(requireMarker().missingRecoveryEvidence()).isNull()

        // Recovery drives the database half, so it needs a database.
        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        // Database: the OLD row is back and the backup's row is gone. Only a real replay can do this.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(OLD_CAPTURE_TEXT)
        // Closet: the user's wardrobe is back.
        assertThat(File(closieDir, "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(File(closieDir, "items.json").readText()).doesNotContain(NEW_ITEM_ID)
        // Media: the user's photo is back — this is the assertion that failed before the fix.
        assertThat(File(mediaLive, "old-photo.jpg").isFile).isTrue()
        assertThat(File(mediaLive, "new-photo.jpg").exists()).isFalse()
        // Nothing is left pended, and the evidence is gone because the rollback really finished.
        assertThat(markerPresent()).isFalse()
    }

    /**
     * The same contradictory state **without** the v2 media evidence must fail closed, before any mutation.
     *
     * A marker cannot claim a v2 database state while omitting the paths a v2 restore always records. When
     * it does, its provenance is undecidable — and every way of guessing is destructive: treating it as v1
     * deletes the media tree and the marker over data the archive may have included, while treating it as
     * v2 would revert directories that were never swapped. Both write a wrong answer into the durable
     * record.
     *
     * So recovery must refuse *before* touching anything, leaving every surface and every piece of evidence
     * exactly as it found them. Compare with the test above, which differs only by carrying the evidence.
     */
    @Test
    fun contradictoryDbCommittingMarker_missingMediaEvidence_failsClosedBeforeMutation() = runTest {
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        val closetOld = File(filesDir, ".closie_restore_old_contradiction")
        writeWardrobeFiles(closetOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // Put the database into a visibly different state first, so "the database was not replayed" is a
        // real assertion rather than a tautology over an unchanged database.
        val oldCaptureId = seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = File(filesDir, ".closie_restore_dbsnap_contradiction.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))
        captureRepo.delete(oldCaptureId)
        seedCapture(NEW_CAPTURE_TEXT)

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9004,
                state = RestoreState.DB_COMMITTING,
                // The contradiction, *and* no media paths at all: the evidence is incomplete.
                includesLifeOs = false,
                closetOldDir = closetOld.absolutePath,
                dbSnapshot = snapFile.absolutePath,
                closetExistedBefore = true,
                // mediaOldDir / mediaStageDir deliberately omitted.
            )
        )

        assertThat(requireMarker().missingRecoveryEvidence()).isEqualTo("mediaStageDir")

        val outcome = RestoreCoordinator.recover(context, db)

        // Refused, and refused *before* mutating: every surface is exactly as it was.
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(closetOld.exists()).isTrue()
        // The database is still on the BACKUP's version — the snapshot was never replayed. This is the
        // assertion that proves the refusal happens before the replay and not merely before the cleanup.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(NEW_CAPTURE_TEXT)
        assertThat(markerPresent()).isTrue()
        assertThat(snapFile.isFile).isTrue()
    }

    /**
     * The same refusal for an **ordinary v2 marker** — `includesLifeOs = true` — that is missing
     * `mediaOldDir`.
     *
     * This is the case the old short-circuit let through. The flag agreeing with the state was treated as
     * proof that the metadata must be present, and the marker was acted on: database replayed, Closet
     * reverted, media skipped for want of a path, evidence deleted, marker cleared. A complete-looking
     * flag over an incomplete marker is the most dangerous shape of this bug, because nothing about it
     * looks exceptional.
     *
     * The assertions are the same as the contradictory case on purpose: the flag must not change the
     * answer, only the state and the fields may.
     */
    @Test
    fun completeLookingFlagWithMissingMediaEvidence_alsoFailsClosedBeforeMutation() = runTest {
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        val closetOld = File(filesDir, ".closie_restore_old_flagtrue")
        writeWardrobeFiles(closetOld, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val oldCaptureId = seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = File(filesDir, ".closie_restore_dbsnap_flagtrue.json")
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(db))
        captureRepo.delete(oldCaptureId)
        seedCapture(NEW_CAPTURE_TEXT)

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9005,
                state = RestoreState.DB_COMMITTED,
                // The flag *agrees* with the state — nothing here looks contradictory …
                includesLifeOs = true,
                closetOldDir = closetOld.absolutePath,
                mediaStageDir = File(filesDir, ".life_media_restore_stage_9005").absolutePath,
                dbSnapshot = snapFile.absolutePath,
                closetExistedBefore = true,
                mediaExistedBefore = true
                // … but `mediaOldDir` is missing, so the media half cannot be reverted.
            )
        )

        assertThat(requireMarker().missingRecoveryEvidence()).isEqualTo("mediaOldDir")

        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(closetOld.exists()).isTrue()
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).containsExactly(NEW_CAPTURE_TEXT)
        assertThat(markerPresent()).isTrue()
        assertThat(snapFile.isFile).isTrue()
    }

    // ------------------------------------------------------------------
    //  recover: validate the evidence before consuming it
    // ------------------------------------------------------------------

    /**
     * An unusable snapshot aborts the **whole** recovery with every surface untouched — the Closet is
     * *not* rolled back first.
     *
     * ### The ordering bug this pins
     *
     * The full recovery used to run the filesystem half first and only then look for the snapshot:
     *
     * ```kotlin
     * recoverFilesystem(context, intent, fs)              // <- consumes the parked tree
     * if (intent.requiresDatabaseRecovery()) { …readSnapshot… }
     * ```
     *
     * `revertCloset` deletes the live tree and renames the parked one into place, so by the time the
     * snapshot is read the "before" state is gone. If the snapshot then turned out to be missing or
     * corrupt, recovery had already rolled the Closet back and could not roll the database back with it —
     * a three-way split *manufactured by the recovery path itself*, on the very marker that told it to
     * keep the surfaces consistent. Aborting with nothing touched is the only safe answer, and it is only
     * reachable if the evidence is validated first.
     *
     * The assertions are chosen to distinguish the two orderings rather than merely observe a failure:
     * `closie/` must still hold the **backup's** item (nothing was reverted) and the parked tree must
     * still exist (nothing was consumed).
     */
    @Test
    fun recover_withAnUnusableSnapshot_abortsBeforeTouchingTheCloset() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_order1")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // The marker names a snapshot that is not there. Recovery cannot replay the database, so it must
        // not touch anything at all.
        val missingSnapshot = File(filesDir, ".closie_restore_dbsnap_order1.json")
        missingSnapshot.deleteRecursively()

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9101,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                // Both media paths, present but with no directory behind them.
                //
                // ### Why a fixture whose *subject* is the snapshot still needs these
                //
                // `missingRecoveryEvidence` demands `mediaStageDir` and `mediaOldDir` for every
                // `DB_COMMITTING` marker, because those states are reachable only after `MEDIA_SWAPPED`.
                // Omitting them made `validateRecoveryEvidence` refuse the marker *before* the snapshot
                // was ever read — so this test passed by proving "evidence validation rejects an
                // incomplete marker", which is a different test, and the ordering it exists to pin (read
                // the snapshot before reverting the Closet) was never exercised. The principle: every
                // other piece of evidence must be valid so the one fault under test is the only thing
                // that can produce the observed behaviour.
                //
                // No directory is created at either path, which is legitimate: the paths are metadata
                // recording where the swap happened, and `mediaExistedBefore = false` is what says the
                // device had no media tree.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_9101").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_9101").absolutePath,
                dbSnapshot = missingSnapshot.absolutePath,
                closetExistedBefore = true
            )
        )

        // The fixture is complete apart from the snapshot: the abort below must come from the *snapshot*
        // read, not from evidence validation. Without this, the test could silently regress to passing for
        // the wrong reason (see the comment on the media paths above).
        assertThat(requireMarker().missingRecoveryEvidence()).isNull()

        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // The decisive assertion: the Closet is *still the backup's*, i.e. nothing was reverted. Under
        // the old ordering this would already hold the user's item and the parked tree would be gone.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(oldDir.exists()).isTrue()
        assertThat(File(oldDir, "items.json").readText()).contains(OLD_ITEM_ID)
        // The marker is kept so a later start — one with usable evidence — can finish.
        assertThat(markerPresent()).isTrue()
    }

    /**
     * A **corrupt** snapshot is treated exactly like a missing one: abort, do not discard the evidence.
     *
     * Corrupt and absent are both "cannot replay now", and neither justifies deleting the file — a corrupt
     * snapshot is still the only record of what the database held, and discarding it would remove the last
     * chance of a human recovering anything from it.
     */
    @Test
    fun recover_withACorruptSnapshot_abortsAndKeepsTheEvidence() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val filesDir = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_order2")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val corruptSnapshot = File(filesDir, ".closie_restore_dbsnap_order2.json")
        corruptSnapshot.writeText("{ \"captures\": [ { \"id\": ")

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9102,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                // The media paths are metadata and are present even though no directory exists at them,
                // so this marker is *complete* and the abort below comes from the corrupt snapshot rather
                // than from evidence validation. See the longer comment in
                // `recover_withAnUnusableSnapshot_abortsBeforeTouchingTheCloset`.
                mediaStageDir = File(filesDir, ".life_media_restore_stage_9102").absolutePath,
                mediaOldDir = File(filesDir, ".life_media_restore_old_9102").absolutePath,
                dbSnapshot = corruptSnapshot.absolutePath,
                closetExistedBefore = true
            )
        )

        assertThat(requireMarker().missingRecoveryEvidence()).isNull()

        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(oldDir.exists()).isTrue()
        // The unusable file is preserved, not tidied away.
        assertThat(corruptSnapshot.exists()).isTrue()
        assertThat(markerPresent()).isTrue()
    }

    /**
     * The positive control for the ordering: with a *usable* snapshot the whole recovery completes.
     *
     * Without this, the two tests above could pass with a recovery that simply always aborts.
     */
    @Test
    fun recover_withAUsableSnapshot_rollsBackBothSurfacesAndConcludes() = runTest {
        seedCapture(OLD_CAPTURE_TEXT)
        val snapFile = parkDbCommittingState(id = 9103)
        // Move the database to the backup's rows, so there is genuinely something to replay.
        db.captureDao().deleteAll()
        seedCapture("备份里的记录")

        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertMutuallyConsistent(
            expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT),
            expectedMediaFiles = emptyList()
        )
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
        assertThat(snapFile.exists()).isFalse()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  COMMITTED cleanup failure must never clear the marker
    // ------------------------------------------------------------------

    /**
     * A **failed** post-commit cleanup must leave the marker in place, because the marker is the only
     * pointer to the leftover.
     *
     * ### The defect, and why "best effort" was the wrong shape
     *
     * Phase B used to run the cleanup and the marker clear as two independent `runCatching` blocks and
     * merely OR their failures together for logging. That looks harmless and is not: the marker names the
     * parked trees *and the snapshot path*. Clearing it after a failed cleanup does not leave a stray
     * directory — it destroys the record of where that directory is, and a `HEALTH_CHECKING` marker's
     * snapshot is unrecoverable by construction once the pointer is gone. The leftover becomes permanent,
     * unattributed litter holding the user's previous wardrobe, and nothing will ever clean it up.
     *
     * The fix is that the clear is *conditional on the cleanup succeeding*. Note what this test must
     * therefore assert: not merely "a retry eventually works" (the existing cleanup-failure tests cover
     * that) but that **the marker survives the failed attempt at all** — which is the thing the old code
     * destroyed.
     */
    @Test
    fun committedCleanupFailure_leavesTheMarkerSoTheLeftoverCanBeFoundAgain() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-marker-kept.png"),
            oldBytes = listOf(byteArrayOf(7, 7))
        )

        // Deleting the parked Closet tree fails, so `cleanupChecked` throws.
        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = FailingRestoreFs(failDeleteContaining = "closie_restore_old_")
        )

        // The restore itself still succeeded — cleanup is housekeeping and must not turn success into
        // failure, or the user would be invited to re-run a restore onto restored data.
        assertThat(result.isSuccess).isTrue()

        // The decisive assertion: the marker is still there. If the clear had run unconditionally, this
        // would be Missing and the leftover tree below would be unreachable forever.
        assertThat(markerPresent()).isTrue()
        assertThat(requireMarker().state).isEqualTo(RestoreState.COMMITTED)

        // …and the leftover it points at is indeed still on disk, which is exactly why the marker matters.
        val leftoverOldDirs = context.filesDir.listFiles().orEmpty()
            .filter { it.name.startsWith(".closie_restore_old_") }
        assertThat(leftoverOldDirs).isNotEmpty()

        // A later start, with a working filesystem, finds it via the marker and converges.
        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { db })
        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
        // Still the backup's version — cleanup never rolls a commit back.
        assertCommittedConsistent()
    }

    /**
     * The positive control: when cleanup succeeds, the marker **is** cleared.
     *
     * Without this, the conditional clear could be broken into "never clear", and every start would redo
     * a finished cleanup forever — and, worse, would leave a marker that makes a completed restore look
     * pending to anything reading it.
     */
    @Test
    fun successfulCleanup_verifiablyRemovesTheMarker() = runTest {
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-marker-cleared.png"),
            oldBytes = listOf(byteArrayOf(6, 6))
        )

        val result = BackupManager.restore(context, wardrobe, backup, db)

        assertThat(result.isSuccess).isTrue()
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  compensateRestore: the same invariants as recovery
    // ------------------------------------------------------------------

    /**
     * A failed restore whose compensation cannot replay the database must **not** delete the evidence.
     *
     * ### The silent split the old guard manufactured
     *
     * `compensateRestore` guarded the database replay with
     * `requiresDatabaseRecovery() && intent.dbSnapshot != null && lifeDatabase != null`. A marker that
     * *required* a replay but named no snapshot therefore fell straight through to the cleanup below,
     * which deleted the parked trees, the snapshot and the marker — over a database that may still hold
     * the backup's rows. The compensation reported success while leaving exactly the three-way split this
     * protocol exists to prevent.
     *
     * A missing snapshot on a marker that requires one is a contradiction, not a licence to proceed. It
     * is now a hard failure that aborts with everything preserved, which is what this test asserts: the
     * Closet is reverted (that half is safe and idempotent), but the marker and the parked trees survive
     * so a later start can finish the database half.
     *
     * The failure is triggered by a health-check hook *after* the database commit, so the marker is
     * genuinely at `HEALTH_CHECKING` — a state that requires a replay — when compensation runs.
     */
    @Test
    fun compensationWithAnUnreplayableDatabase_keepsTheEvidenceAndDoesNotClaimSuccess() = runTest {
        val closieDir = File(context.filesDir, "closie")
        seedCapture(OLD_CAPTURE_TEXT)
        val oldDir = File(context.filesDir, ".closie_restore_old_comp1")

        // A marker that requires a database replay but names no snapshot: the contradiction.
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 9201,
                state = RestoreState.HEALTH_CHECKING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                // Deliberately no `dbSnapshot` — the contradiction under test.
                dbSnapshot = null
            )
        )
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        // Recovery is the same code path's counterpart and shares the invariant; running it must not
        // silently conclude over the unusable database half.
        val outcome = RestoreCoordinator.recover(context, db)

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // Nothing was reverted here either, because recovery validates before consuming.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(oldDir.exists()).isTrue()
        // The marker is preserved, so a later, better-informed start can decide what to do.
        assertThat(markerPresent()).isTrue()
    }

    /**
     * A compensation that cannot finish — reached **through the real restore**, not by hand-writing a
     * marker — must fail closed over the whole process.
     *
     * ### Why the previous "compensation test" proved nothing about compensation
     *
     * It wrote a marker by hand and called [RestoreCoordinator.recover]. That exercises the *startup*
     * path, never [compensateRestore], so the compensation's ordering, its evidence handling and its
     * failure behaviour were all untested — and all three were wrong. This test reaches compensation the
     * way production does: a restore that fails late, in-process, with the marker still pre-commit.
     *
     * ### What is injected, and why those two things
     *
     *  - a [RestoreHooks] that fails at `beforeHealthCheck`, so the restore is past its database commit
     *    and still pre-`COMMITTED` — the exact window in which compensation is allowed to run;
     *  - a [FailingRestoreFs] that fails `deleteTree` of the **live Closet**, which is the first
     *    filesystem step compensation performs *after* the database replay.
     *
     * The second choice is what makes this a regression test for the ordering: if compensation reverted
     * the filesystem first, the parked tree would already be consumed by the time the revert failed, and
     * the assertion below that the parked tree survives would fail.
     *
     * ### What "fail closed" means here, concretely
     *
     * The previous code was `runCatching { compensateRestore(...) }`. The failure was discarded, the
     * `Result` the caller already had was unaffected, and the process carried on: every repository
     * instance held by every live ViewModel kept reading and writing a half-restored data set. So the
     * assertions below are about the *process*, not about the return value.
     */
    @Test
    fun compensationFailure_throughRestore_keepsEvidenceAndFailClosesTheWholeProcess() = runTest {
        val closieDir = File(context.filesDir, "closie")
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-compensation.png"),
            oldBytes = listOf(byteArrayOf(7, 7))
        )

        // Ordinarily usable — held *before* anything fails, which is the case the gate has to cover.
        assertThat(RestoreStartupGate.isReady).isTrue()
        val heldWardrobe = wardrobe
        val heldLife = life
        assertThat(heldLife.count()).isGreaterThan(0)

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = object : RestoreHooks {
                override fun beforeHealthCheck() =
                    throw IllegalStateException("injected: 健康检查失败，进入补偿")
            },
            // Fails the *first filesystem step after the database replay*.
            fs = FailingRestoreFs(failDelete = closieDir.absolutePath)
        )

        // The restore failed — that was always true, and is not the point.
        assertThat(result.isFailure).isTrue()

        // ---- 1. The evidence is preserved, so the next start can finish. ---------------------------
        assertThat(markerPresent()).isTrue()
        // …and it was not advanced: claiming ROLLED_BACK over an unfinished revert would be a lie that
        // the next start would act on.
        assertThat(requireMarker().state).isEqualTo(RestoreState.HEALTH_CHECKING)
        assertThat(requireMarker().dbSnapshot).isNotNull()
        assertThat(File(requireMarker().dbSnapshot!!).isFile).isTrue()
        // The parked tree was NOT consumed. This is the assertion that pins the ordering.
        assertThat(requireMarker().closetOldDir).isNotNull()
        assertThat(File(requireMarker().closetOldDir!!).exists()).isTrue()

        // ---- 2. The database half *did* run, and ran first. ---------------------------------------
        // Proven by the rows: the replay is the only thing that can turn the backup's rows back into the
        // user's. (Read through the raw DAO: the repositories now correctly refuse.)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains(OLD_CAPTURE_TEXT)
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).doesNotContain(NEW_CAPTURE_TEXT)
        // …while the Closet is still the backup's, because the revert is exactly what failed.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)

        // ---- 3. The whole process refuses business reads and writes. ------------------------------
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()

        // Instances held *before* the failure included — a constructor-time check cannot do this.
        assertThat(runCatching { heldWardrobe.listItems() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(
            runCatching {
                heldWardrobe.createItem(
                    com.qq.closie.data.model.ClothingItem(
                        id = "written-after-failure",
                        name = "失败之后写入的数据",
                        category = "上衣",
                        status = com.qq.closie.data.model.ItemStatus.OWNED
                    )
                )
            }.exceptionOrNull()
        ).isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(runCatching { heldLife.getEntity("any") }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)

        // ---- 4. Nothing in this process may reopen the gate, and no new restore may start. --------
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.isReady).isFalse()

        // The refusal arrives as a failed `Result`, not as a thrown exception, and that is the
        // [BLOCKER 8] contract: this is a `suspend` function the Settings screen calls from a button
        // handler that does `result.fold(...)` and then clears its `busy` flag. Anything thrown out of
        // it escapes that block, so the spinner would never stop and the screen would be stuck — a
        // worse outcome than the refusal itself.
        val refused = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(refused.isFailure).isTrue()
        assertThat(refused.exceptionOrNull()).isInstanceOf(RestoreAlreadyPendingException::class.java)
    }

    /**
     * The other half of the compensation ordering, reached the same way: a **database replay** failure
     * must not consume the filesystem evidence.
     *
     * The test above injects a filesystem failure, which proves the database runs *first* but says
     * nothing about what happens when the database itself is the failure. That case is the one
     * [BLOCKER 6's ordering note] exists for, and it is the one that used to produce the permanent split:
     *
     * ```
     *   revertCloset / revertMedia   -> parked trees consumed, surfaces on the user's version
     *   restoreSnapshot              -> throws
     *   result: Closet = old, media = old, DB = backup, and nothing left to repair from
     * ```
     *
     * ### How the replay is made to fail
     *
     * `afterDbCommit` drops one of the tables the replay touches and then throws. Two things fall out of
     * that single injection:
     *
     *  - the throw puts Phase A into failure at `DB_COMMITTED`, i.e. post-transaction and pre-`COMMITTED`,
     *    so compensation is entered with real database work to undo;
     *  - the dropped table makes `LifeBackupApplier.restoreSnapshot` fail inside its transaction. Not a
     *    missing file, not an unreadable snapshot — the replay itself, on evidence that reads fine.
     *
     * The assertions are therefore about *evidence*: the Closet is still the backup's version and both
     * parked trees are still on disk. Had the filesystem reverted first, every one of them would be gone.
     */
    @Test
    fun compensationWithDbReplayFailure_throughRestore_doesNotConsumeFilesystemEvidence() = runTest {
        val closieDir = File(context.filesDir, "closie")
        val liveMediaDir = File(context.filesDir, MediaStoreImporter.MEDIA_DIR)
        val (backup, _) = backupThenInstallOldState(
            mediaFileNames = listOf("bk-comp-dbreplay.png"),
            oldBytes = listOf(byteArrayOf(9, 9))
        )

        assertThat(RestoreStartupGate.isReady).isTrue()

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = object : RestoreHooks {
                // Fires immediately after the transaction commits — the earliest point at which a later
                // compensation has database work to undo.
                override fun afterDbCommit() {
                    // Break the *replay*, not any file: `applyDeleteOrder` opens with this table, so the
                    // compensation's restoreSnapshot throws inside its transaction.
                    db.openHelper.writableDatabase.execSQL("DROP TABLE plan_items")
                    throw IllegalStateException("injected: 数据库提交后失败，进入补偿")
                }
            },
            fs = RealRestoreFs
        )

        assertThat(result.isFailure).isTrue()

        // ---- 1. The database half failed, so the filesystem half must never have run. ---------------
        // This is the assertion that pins the ordering: `revertCloset` deletes the live tree and renames
        // the parked one into the resulting empty slot, so a consumed parked tree is unrecoverable.
        assertThat(File(closieDir, "items.json").readText()).contains(NEW_ITEM_ID)
        assertThat(liveMediaDir.exists()).isTrue()

        // ---- 2. Every piece of evidence survives. ---------------------------------------------------
        assertThat(markerPresent()).isTrue()
        val marker = requireMarker()
        assertThat(marker.state).isEqualTo(RestoreState.DB_COMMITTED)
        assertThat(File(marker.closetOldDir!!).exists()).isTrue()
        assertThat(File(marker.mediaOldDir!!).exists()).isTrue()
        assertThat(File(marker.dbSnapshot!!).isFile).isTrue()

        // ---- 3. The database really is still on the backup's version. ------------------------------
        // Read through the raw DAO: nothing built it back from the snapshot, so only an unrestored row
        // can be here.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains(NEW_CAPTURE_TEXT)

        // ---- 4. Fail closed, process-wide. ---------------------------------------------------------
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()
        assertThat(runCatching { wardrobe.listItems() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
    }
}
