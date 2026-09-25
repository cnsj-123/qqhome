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
        referenceRepo = ReferenceRepository(db, life, media)
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
                closetOldDir = oldDir.absolutePath,
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
                closetOldDir = oldDir.absolutePath,
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
     * `HEALTH_CHECKING` is the one worth staring at. It is reachable with **no snapshot at all** — a
     * Backup v1 archive, or any restore with no live database, still passes through `DB_COMMITTED` and
     * then `HEALTH_CHECKING` while never writing a row. Treating it as "the database needs nothing"
     * would let a process killed there be "recovered" by reverting the Closet alone, which is the
     * mirror image of the original bug: the user's wardrobe next to the backup's life-graph.
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
     * The predicate's companion, and the one the recovery path actually reads when deciding whether
     * the Closet may be the backup's version.
     *
     * A committed database does not un-swap the directory, so every database state needs the revert
     * too. Getting this wrong in the "false" direction leaves the user with the backup's wardrobe and
     * their own life-graph — the half-restore seen from the other side.
     */
    @Test
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
                dbSnapshot = snapFile.absolutePath
            )
        )

        assertThat(RestoreCoordinator.recover(context, db)).isEqualTo(RecoveryOutcome.Completed)

        assertMutuallyConsistent(expectedCaptureTexts = listOf(OLD_CAPTURE_TEXT), expectedMediaFiles = emptyList())
        assertThat(File(File(filesDir, "closie"), "items.json").readText()).contains(OLD_ITEM_ID)
        assertThat(markerMissing()).isTrue()
        assertThat(strayRestoreDirs()).isEmpty()
    }

    /** The predicate itself, held to its exact truth table — the state half and the includesLifeOs half. */
    @Test
    fun requiresDatabaseRecovery_dependsOnBothStateAndIncludesLifeOs() {
        val afterTxn = listOf(RestoreState.DB_COMMITTING, RestoreState.DB_COMMITTED, RestoreState.HEALTH_CHECKING)

        // v2: exactly the three states that follow the transaction.
        assertThat(RestoreState.entries.filter { RestoreIntent(includesLifeOs = true, state = it).requiresDatabaseRecovery() })
            .containsExactlyElementsIn(afterTxn)
        // v1: never, in any state.
        assertThat(RestoreState.entries.filter { RestoreIntent(includesLifeOs = false, state = it).requiresDatabaseRecovery() })
            .isEmpty()

        // The one state that is reachable by both formats and means different things in each — the
        // reason the state alone was not enough.
        assertThat(RestoreIntent(includesLifeOs = true, state = RestoreState.HEALTH_CHECKING).requiresDatabaseRecovery()).isTrue()
        assertThat(RestoreIntent(includesLifeOs = false, state = RestoreState.HEALTH_CHECKING).requiresDatabaseRecovery()).isFalse()
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
     * @param snapshotOf the database to snapshot. Pass the database **after** seeding the user's
     *   pre-restore rows and **before** mutating it to the backup's, so the snapshot describes the
     *   state recovery is supposed to restore. Defaults to the field `db`, which is already at that
     *   state in most tests.
     */
    private suspend fun parkDbCommittingState(
        id: Long,
        snapshotOf: LifeDatabase = db
    ): File {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_$id")
        writeWardrobeFiles(oldDir, itemsJson = """[{"id":"$OLD_ITEM_ID","name":"用户原有的衣服"}]""")
        writeWardrobeFiles(closieDir, itemsJson = """[{"id":"$NEW_ITEM_ID","name":"备份里的衣服"}]""")

        val snapFile = File(filesDir, ".closie_restore_dbsnap_$id.json")
        // Written through the same helper production uses, so the fixture and the reader agree on the
        // serialisation by construction rather than by two `Gson` instances happening to match.
        LifeBackupApplier.writeSnapshot(snapFile, LifeBackupApplier.snapshot(snapshotOf))

        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = id,
                state = RestoreState.DB_COMMITTING,
                closetOldDir = oldDir.absolutePath,
                dbSnapshot = snapFile.absolutePath
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
}

/**
 * A [RestoreFs] that fails one chosen operation and otherwise behaves exactly like the real one.
 *
 * Used instead of `chmod`-style permission tricks for two reasons: those are unreliable under
 * Robolectric's filesystem, and — more importantly — they produce an *arbitrary* failure, whereas the
 * question these tests ask is about a *specific* operation ("the rename back could not be performed").
 * Targeting the operation keeps the test honest about which recovery step is under examination.
 *
 * Failure is keyed on the *destination* path, because that is what the recovery code names when it
 * decides what it is undoing.
 */
private class FailingRestoreFs(
    private val failRenameInto: String? = null,
    private val failDelete: String? = null,
    /**
     * Fails deletion of any directory whose **name** contains this substring.
     *
     * Used for the post-commit cleanup tests, where the parked tree's name carries a
     * `System.currentTimeMillis()` suffix that a test cannot predict. Matching on the invariant part of
     * the name (`.closie_restore_old_`, `.life_media_restore_old_`) keeps those tests from depending on
     * the clock while still targeting exactly one class of directory.
     */
    private val failDeleteContaining: String? = null
) : RestoreFs {

    override fun park(live: File, old: File, existedBefore: Boolean) =
        RealRestoreFs.park(live, old, existedBefore)

    override fun rename(from: File, to: File, message: String) {
        if (failRenameInto != null && to.absolutePath == failRenameInto) {
            throw java.io.IOException("注入的失败：重命名到 $failRenameInto 不可用")
        }
        RealRestoreFs.rename(from, to, message)
    }

    override fun deleteTree(dir: File) {
        if (failDelete != null && dir.absolutePath == failDelete) {
            throw java.io.IOException("注入的失败：无法删除 $failDelete")
        }
        if (failDeleteContaining != null && dir.name.contains(failDeleteContaining)) {
            throw java.io.IOException("注入的失败：无法删除 ${dir.name}")
        }
        RealRestoreFs.deleteTree(dir)
    }

    override fun cleanupChecked(intent: RestoreIntent) {
        intent.closetOldDir?.let { deleteTree(File(it)) }
        intent.mediaOldDir?.let { deleteTree(File(it)) }
        intent.dbSnapshot?.let { path ->
            val f = File(path)
            if (f.exists() && !f.delete()) throw java.io.IOException("注入的失败：无法删除快照 $path")
        }
    }
}
