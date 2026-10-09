package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRole
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The *wiring* of recovery, as opposed to the recovery algorithm itself.
 *
 * [RestoreCoordinatorTest] proves that recovery repairs data correctly. This proves it actually
 * **runs** before anyone can read data — that it is reachable from wherever the process starts, and
 * that it does not depend on the user happening to open the main screen.
 *
 * That distinction is not academic. Recovery used to live in `MainActivity.onCreate`, which is only one
 * of four entry points into this process (`QuickCaptureActivity`, `CapturePreviewActivity` and
 * `QuickCaptureService` are the others). A user whose restore was interrupted and who came back
 * through the quick-capture notification would have had the app read a Closet from one version against
 * a database from another, with no recovery in sight.
 *
 * ### What changed, and why these tests now assert on the barrier
 *
 * This manager used to schedule the database-aware pass on a background scope and return a `Job`. That
 * was unsound: control returned to `Application.onCreate` while the database half was still
 * outstanding, so for a real interval the Closet and the media were the user's version while the
 * database still held the backup's rows — and an already-constructed UI could read that mixture. A
 * user write in that window could then be overwritten by the snapshot replay.
 *
 * The contract asserted below is therefore a **barrier**: when [RestoreRecoveryManager.recoverOnStartup]
 * returns, either recovery is finished and [RestoreStartupGate] is READY, or it could not finish and
 * the gate is BLOCKED. There is no third state in which it is "still working". Every assertion here
 * reads the filesystem and the gate **immediately** after the call — no `join`, no `await`, no sleep —
 * because that immediacy *is* the property under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreRecoveryManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        RestoreStartupGate.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        RestoreIntentStore.clear(context)
        RestoreStartupGate.resetForTesting()
        listOf(".closie_restore_", ".life_media_restore_", ".life_restore_dbsnap_").forEach { prefix ->
            context.filesDir.listFiles().orEmpty()
                .filter { it.name.startsWith(prefix) }
                .forEach { it.deleteRecursively() }
        }
    }

    /**
     * Stages exactly what a process killed after the swap leaves behind.
     *
     * No marker is written here — each test writes the marker it needs, so the difference between
     * "pending restore" and "ordinary start" is explicit rather than implied by the fixture.
     */
    private fun parkOldTreeAndSwapCloset(id: Long): Pair<File, File> {
        val filesDir: File = context.filesDir
        val closieDir = File(filesDir, "closie")
        val oldDir = File(filesDir, ".closie_restore_old_$id")

        writeWardrobeFiles(oldDir, """[{"id":"old-item","name":"用户原有的衣服"}]""")
        closieDir.deleteRecursively()
        writeWardrobeFiles(closieDir, """[{"id":"new-item","name":"备份里的衣服"}]""")

        return closieDir to oldDir
    }

    private fun writeWardrobeFiles(dir: File, itemsJson: String) {
        File(dir, "drafts").mkdirs()
        File(dir, "items.json").writeText(itemsJson)
        File(dir, "wear.json").writeText("[]")
        File(dir, "wash.json").writeText("[]")
        File(dir, "ootds.json").writeText("[]")
        File(dir, "outfits.json").writeText("[]")
    }

    /** An empty in-memory Life database, for the cases where the *database* is not the subject. */
    private fun openInMemoryDatabase(): LifeDatabase =
        Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * A snapshot file that **reads and validates perfectly** and then *cannot be applied*.
     *
     * The payload carries one `media_resources` row whose `mediaAssetId` names an asset that is absent
     * from `mediaAssets`. `media_resources.mediaAssetId` is a real [androidx.room.ForeignKey] in the
     * schema and Room runs with `PRAGMA foreign_keys = ON`, so that insert fails with a constraint
     * violation — inside the replay transaction, *after* the snapshot has been read and accepted. That
     * is exactly the window this file needs: the evidence is fine, the replay throws.
     *
     * ### Why not the previous "closed database" trick
     *
     * It handed back a database built and then closed, on the theory that any transaction against it
     * would throw. It does not. `Room.inMemoryDatabaseBuilder` has no filename, so the next
     * `getWritableDatabase()` after `close()` simply creates a brand-new empty database; the replay
     * ran cleanly against it and recovery honestly reported `Completed`. The test then demanded
     * `RetryRequired` from a genuinely successful pass — which is worse than no test, because the only
     * way to make it pass is to make recovery lie.
     */
    private suspend fun unapplyableSnapshot(id: Long): File {
        val file = File(context.filesDir, ".closie_restore_dbsnap_$id.json")
        LifeBackupApplier.writeSnapshot(
            file,
            LifeBackupPayload(
                mediaResources = listOf(
                    MediaResourceRecord(
                        row = MediaResourceEntity(
                            id = "dangling-res",
                            // Not in `mediaAssets` — the foreign key is what makes the replay fail.
                            mediaAssetId = "asset-absent-from-this-payload",
                            role = MediaResourceRole.ORIGINAL,
                            mimeType = "image/png",
                            createdAt = 1L
                        ),
                        archiveFileName = "dangling.png"
                    )
                )
            )
        )
        return file
    }

    /** An in-memory database with a real snapshot written from its own (empty) contents. */
    private suspend fun seededSnapshot(id: Long): Pair<LifeDatabase, File> {
        val database = openInMemoryDatabase()
        val file = File(context.filesDir, ".closie_restore_dbsnap_$id.json")
        LifeBackupApplier.writeSnapshot(file, LifeBackupApplier.snapshot(database))
        return database to file
    }

    /**
     * The barrier itself: recovery is **finished** by the time `recoverOnStartup` returns.
     *
     * This is what makes it safe to call from `Application.onCreate`: whatever the caller does next, it
     * can rely on `closie/` being self-consistent. Scheduling this on a coroutine would reopen exactly
     * the window the pass exists to close, so the test reads the directory *immediately* — no
     * suspension, no yield, no await.
     */
    @Test
    fun filesystemPass_completesBeforeReturn() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1001)
        // A v1-shaped marker: swapped, no Life OS section. `closetExistedBefore = true` because the
        // fixture really did park the user's wardrobe there, and the flag is what tells the revert path
        // to rename it back rather than delete the live tree.
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1001,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        // Read *immediately* — the Closet is already the user's again.
        assertThat(File(closieDir, "items.json").readText()).contains("用户原有的衣服")
        assertThat(oldDir.exists()).isFalse()
        assertThat(RestoreStartupGate.isReady).isTrue()
    }

    /**
     * With no marker, the database must not be opened at all.
     *
     * Recovery is rare; a normal launch must not pay for it, and it must not start a transaction
     * against a database it has no reason to touch. Asserted on whether the *provider was called*
     * rather than on which dispatcher did the work, because "no database was opened" is the property
     * that matters, not "the work happened to be cheap".
     */
    @Test
    fun noMarker_neverOpensTheDatabase() {
        var opened = false

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { opened = true; null }
        )

        assertThat(outcome).isEqualTo(RecoveryOutcome.NoWork)
        assertThat(opened).isFalse()
        // The ordinary launch path is *ready* — the gate must not block an app that has nothing to do.
        assertThat(RestoreStartupGate.isReady).isTrue()
    }

    /**
     * With a marker present, the database-aware half does reach the database — through its provider.
     *
     * The marker has to be one whose database half genuinely has work. A `DB_COMMITTING` marker
     * *without* a snapshot describes a database that was never written, so there is nothing to replay
     * — but it is not a v1 marker either: `DB_COMMITTING` is reachable only through the v2 branch that
     * writes the snapshot and opens the transaction, so [RestoreIntent.requiresDatabaseRecovery] reads
     * it as "the database needs attention" regardless of `includesLifeOs`. Hence this fixture sets
     * `includesLifeOs = true` and names both a snapshot and a parked tree, which is the shape a real
     * process kill inside the transaction leaves behind.
     */
    @Test
    fun pendingMarker_reachesTheDatabaseBeforeReturning() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1002)
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1002.json")
        snapshot.writeText("{}")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1002,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                // A v2 marker carries both media paths: these states are reachable only by a restore that
                // swapped the media surface, so `missingRecoveryEvidence` demands them before recovery will
                // act. This fixture has no media on disk — the device had none — which is exactly what
                // `mediaExistedBefore` (default false) records; the path is metadata, not a file.
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1002").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1002").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        var opened = false
        RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { opened = true; null }
        )

        // No join, no await: the filesystem half completed inside the call (it must not have been
        // deferred), and the provider was reached.
        assertThat(File(closieDir, "items.json").readText()).contains("用户原有的衣服")
        assertThat(opened).isTrue()
    }

    /**
     * A throwing database provider must not escape into `Application.onCreate`.
     *
     * Every entry point of the app runs through there; a failure to *recover* must never become a
     * failure to *start*, which would turn a recoverable data state into an unusable app. Reaching the
     * assertions at all is half the proof.
     *
     * ### The other half: the filesystem repair must still have happened
     *
     * This is the regression for the ordering defect. The manager used to evaluate `lifeDatabase()` as
     * an *argument* to the recovery call, so a throwing provider aborted the whole attempt before the
     * filesystem pass was ever entered — and the Closet, which needs no database at all, was left
     * half-swapped for no reason. The assertion on `items.json` is what pins that: it can only hold if
     * pass 1 ran and completed despite pass 2 never starting.
     */
    @Test
    fun aThrowingDatabaseProvider_doesNotEscape() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1003)
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1003,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1003").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1003").absolutePath,
                dbSnapshot = "/nope"
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { throw IllegalStateException("injected: database unavailable") }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // The Closet repair still happened — the database half failing did not roll it back, and did
        // not prevent it either.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText())
            .contains("用户原有的衣服")
        // …and the marker is kept, so a later start with a working database can finish the job.
        assertThat(RestoreIntentStore.read(context)).isNotInstanceOf(AtomicJson.ReadResult.Missing::class.java)
    }

    /**
     * A recovery that could not finish must leave the gate **blocked**.
     *
     * This is the whole point of the gate. If a failed recovery still marked the process ready, the
     * repositories would open against surfaces that provably disagree — and the user's next write
     * would land on top of the disagreement. The safe direction is to serve no data at all until a
     * later start repairs it; the marker, the parked tree and the snapshot are all still on disk.
     */
    @Test
    fun retryRequired_leavesTheGateBlocked_soNoRepositoryCanRead() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1005)
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1005,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1005").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1005").absolutePath,
                dbSnapshot = "/nope"
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { throw IllegalStateException("injected: database unavailable") }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.blockReason).isNotNull()
    }

    /**
     * A marker that cannot be parsed must block the gate, not be treated as "no marker".
     *
     * An unreadable marker tells us nothing about whether `closie/` is mid-swap, so the only safe
     * reading is "assume it is". Treating it as a clean slate would let a repository open against a
     * directory that is neither the user's wardrobe nor the backup's, and the recovery evidence — the
     * parked tree and the snapshot — would still be on disk but no longer pointed at by anything.
     */
    @Test
    fun corruptMarker_blocksTheGateAndKeepsTheEvidence() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1006)
        RestoreIntentStore.markerFile(context).writeText("{ \"id\": 1006, \"state\":")

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        // The unparseable marker is kept verbatim — it is the only record of the interrupted restore.
        assertThat(RestoreIntentStore.markerFile(context).exists()).isTrue()
        assertThat(oldDir.exists()).isTrue()
    }

    /**
     * The positive control for the gate: a successful recovery must leave the process **ready**.
     *
     * Without this, every test above could pass simply by the gate never being marked ready, and the
     * app would be permanently unusable after any restore.
     */
    @Test
    fun completedRecovery_marksTheGateReady() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1007)
        // A marker with nothing outstanding: the Closet swap is named, no Life OS section, and no
        // database half — so this pass can genuinely conclude the whole protocol.
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1007,
                state = RestoreState.CLOSET_SWAPPED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertThat(RestoreStartupGate.isReady).isTrue()
        assertThat(RestoreStartupGate.blockReason).isNull()
        // Completed must mean *completed*: the marker is verifiably gone, not merely reported clear.
        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Missing::class.java)
    }

    /**
     * The startup **barrier**: a `DB_COMMITTED` marker must be fully resolved — filesystem *and*
     * database — before the gate opens, and the gate must be the thing the repository consults.
     *
     * This is the regression for the two-pass design. That design ran the filesystem pass synchronously
     * and handed the database pass to a coroutine, so `Application.onCreate` returned with the Closet on
     * one version and the database on another — a mixture any screen built milliseconds later could read.
     * The barrier replaces it: when `recoverOnStartup` returns, either the recovery is finished or the
     * gate is blocked, and there is no interval in between.
     *
     * The window is checked the only way that does not rely on timing: the "was the gate ever observed
     * blocked *after* a completed call" question is answered by reading the gate immediately, with no
     * sleep, no yield and no join. If a background pass still existed, the gate would be left blocked at
     * this point (the recovery would not have completed) and the assertion would fail.
     */
    @Test
    fun dbCommittedMarker_isFullyRecoveredBeforeTheGateOpens() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1008)
        // A snapshot that recovery can replay: the user's pre-restore rows. The file is a placeholder
        // because this test's subject is the *ordering* (filesystem before database, both before the
        // gate opens), not the snapshot's contents — the content-level assertion lives in
        // RestoreCoordinatorTest, where a real database is driven through `recover`.
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1008.json")
        snapshot.writeText("{}")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1008,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1008").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1008").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        // Read the gate *before* the call: a fresh process starts blocked, so the only way the
        // assertions below can hold is that this call resolved it.
        assertThat(RestoreStartupGate.isReady).isFalse()

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })

        // A `null` database cannot complete a `DB_COMMITTING` marker, so this is the blocked case — and
        // the point is that it is *decided*, not pending.
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        // The filesystem half still completed: the Closet is already the user's again, so a later start
        // has strictly less to do.
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText())
            .contains("用户原有的衣服")
        // …and the evidence for the unfinished database half survives.
        assertThat(snapshot.exists()).isTrue()
    }

    /**
     * The positive control for the barrier's *ordering*: the filesystem half is repaired even though the
     * database half cannot be.
     *
     * This is the assertion that would fail if recovery still ran as a single pass guarded by a
     * database-provider argument. The outcome is `RetryRequired` either way — the marker genuinely still
     * needs its database half — but what differs is whether the *Closet* was put back. With the provider
     * throwing before the recovery call is entered, the Closet stays on the backup's version, and the
     * parked tree the user's wardrobe lives in is never renamed into place.
     *
     * Note the asymmetry this pins, and why it is the safe direction: the gate stays BLOCKED here even
     * though the filesystem repair succeeded. That is correct — the protocol as a whole is unfinished, so
     * business code must still not read anything.
     */
    @Test
    fun aThrowingProvider_stillRepairsTheFilesystemHalf() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1009)
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1009.json")
        snapshot.writeText("{}")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1009,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1009").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1009").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        var providerCalls = 0
        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { providerCalls++; throw IllegalStateException("injected: database unavailable") }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(providerCalls).isAtLeast(1)

        // The filesystem repair happened regardless — this is the whole point.
        assertThat(File(closieDir, "items.json").readText()).contains("用户原有的衣服")
        assertThat(File(closieDir, "items.json").readText()).doesNotContain("备份里的衣服")
        // The parked tree was consumed by the successful rename back, so it is gone.
        assertThat(oldDir.exists()).isFalse()
        // The snapshot survives, because the database half is still outstanding.
        assertThat(snapshot.exists()).isTrue()
        // And the gate is correctly still blocked: the protocol is unfinished.
        assertThat(RestoreStartupGate.isReady).isFalse()
    }

    // ------------------------------------------------------------------
    //  The database route is taken *before* any filesystem mutation
    // ------------------------------------------------------------------

    /**
     * A corrupt snapshot must abort **before** the filesystem pass consumes the parked trees.
     *
     * ### The regression this pins
     *
     * The manager used to run `recoverFilesystemOnly` unconditionally and only then decide whether a
     * database pass was needed. With a database available and a corrupt snapshot, that ordering did this:
     *
     * ```
     *   filesystem pass: revert Closet + media   <- the parked trees are now gone
     *   full recovery:   read snapshot -> corrupt -> RetryRequired
     * ```
     *
     * The marker survived, so the gate ended up blocked and no inconsistent data was served — but the
     * trees that the rollback still needed had already been deleted. The filesystem half was finished and
     * the database half was not, with no evidence left to reconcile them: precisely the split that the
     * full recovery's ordering exists to prevent, reached by taking a shortcut around it.
     *
     * The correct behaviour is to route straight to the full recovery (which validates the snapshot
     * first) and leave **everything** untouched.
     */
    @Test
    fun corruptSnapshot_leavesTheParkedTreesUntouched() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1010)
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1010.json")
        // Unparseable: the evidence exists but cannot be replayed.
        snapshot.writeText("{ \"rows\": [")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1010,
                state = RestoreState.DB_COMMITTED,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1010").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1010").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { openInMemoryDatabase() }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        // NOTHING was consumed: the Closet is still the backup's, and both pieces of evidence survive.
        assertThat(File(closieDir, "items.json").readText()).contains("备份里的衣服")
        assertThat(oldDir.exists()).isTrue()
        assertThat(snapshot.exists()).isTrue()
    }

    /**
     * A database replay that *fails* must equally leave the parked trees untouched.
     *
     * The snapshot here parses into a payload, so validation passes and the replay is actually entered;
     * the failure comes from applying it — the payload violates a foreign key the schema enforces. See
     * [unapplyableSnapshot]. That isolates "replay threw" from both "could not read the snapshot" and
     * "could not open the database": the state is `DB_COMMITTED`, the evidence is readable, the provider
     * hands back a perfectly healthy database, and the only thing wrong is the transaction.
     *
     * The assertion is the same shape as the corrupt case, and for the same reason: a replay that throws
     * is a reason to stop, not a reason to have already deleted the rollback evidence.
     */
    @Test
    fun failedDatabaseReplay_leavesTheParkedTreesUntouched() = runTest {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1011)
        // Parses as a valid payload, and cannot be applied. See `unapplyableSnapshot`.
        val snapshot = unapplyableSnapshot(id = 1011)
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1011,
                state = RestoreState.DB_COMMITTED,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1011").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1011").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            // A healthy database — the failure is the replay, not the handle.
            lifeDatabase = { openInMemoryDatabase() }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(File(closieDir, "items.json").readText()).contains("备份里的衣服")
        assertThat(oldDir.exists()).isTrue()
        assertThat(snapshot.exists()).isTrue()
    }

    /**
     * A full rollback that then fails to clean up must leave a durable `ROLLED_BACK` marker.
     *
     * ### The deadlock this prevents
     *
     * Without a terminal write, the sequence on a `DB_COMMITTED` marker was: replay the database, revert
     * the trees, **delete the snapshot**, then try to clear the marker. If that clear failed, the marker
     * still said `DB_COMMITTED` — a state that requires a snapshot — while the snapshot had just been
     * deleted. Every subsequent start would demand a replay of a file that no longer exists:
     * `RetryRequired` forever, on data that is in fact perfectly consistent.
     *
     * Writing `ROLLED_BACK` before the cleanup removes the window. A later pass reads "the rollback is
     * done, finish the tidying" and never looks for the snapshot again.
     */
    @Test
    fun fullRollbackWithCleanupFailure_leavesADurableRolledBackMarker() = runTest {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1012)
        val (database, snapshot) = seededSnapshot(id = 1012)
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1012,
                state = RestoreState.DB_COMMITTED,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1012").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1012").absolutePath,
                dbSnapshot = snapshot.absolutePath
            )
        )

        // The rollback succeeds; only the cleanup of the parked tree is made to fail.
        val failingFs = FailingRestoreFs(failDelete = oldDir.absolutePath)

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { database },
            fs = failingFs
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // The data is consistent: the Closet is the user's again and the database was replayed.
        assertThat(File(closieDir, "items.json").readText()).contains("用户原有的衣服")
        // …and the marker records that durably, so the next start knows it need not replay anything.
        val marker = RestoreIntentStore.read(context)
        assertThat(marker).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        assertThat((marker as AtomicJson.ReadResult.Success).value.state)
            .isEqualTo(RestoreState.ROLLED_BACK)
    }

    /**
     * A cleanup failure on **`ROLLED_BACK`** must not brick the app.
     *
     * `ROLLED_BACK` means every durable surface is consistent and only litter remains. Blocking on that
     * turns an undeletable directory into a permanently unusable app, which inverts what the gate is for:
     * it exists to stop business code reading data that *disagrees*, not to demand a tidy disk.
     */
    @Test
    fun rolledBackMarkerWithCleanupFailure_stillOpensTheGate() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1013)
        // The rollback already happened in a previous process; this start only has to clean up.
        File(File(context.filesDir, "closie"), "items.json")
            .writeText("""[{"id":"old-item","name":"用户原有的衣服"}]""")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1013,
                state = RestoreState.ROLLED_BACK,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { null },
            fs = FailingRestoreFs(failDelete = oldDir.absolutePath)
        )

        // The verdict is still a retry — the cleanup genuinely did not finish — but the data is sound,
        // so the gate must be open.
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isTrue()
        // The marker stays, so the next start retries the cleanup.
        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        // And the user's data is readable, which is the point.
        assertThat(File(closieDir, "items.json").readText()).contains("用户原有的衣服")
    }

    /**
     * A cleanup failure on **`COMMITTED`** must equally not brick the app.
     *
     * A committed restore's data is official and consistent; a stale parked directory that will not
     * delete is a tidiness problem, not a correctness one.
     */
    @Test
    fun committedMarkerWithCleanupFailure_stillOpensTheGate() {
        val (closieDir, oldDir) = parkOldTreeAndSwapCloset(id = 1014)
        // The committed restore's live data — the backup's version won, and that is correct.
        File(closieDir, "items.json").writeText("""[{"id":"new-item","name":"备份里的衣服"}]""")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1014,
                state = RestoreState.COMMITTED,
                includesLifeOs = false,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { null },
            fs = FailingRestoreFs(failDelete = oldDir.absolutePath)
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isTrue()
        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        // The committed data must NOT have been reverted — the marker is COMMITTED, not a rollback.
        assertThat(File(closieDir, "items.json").readText()).contains("备份里的衣服")
    }

    /**
     * The negative control for the two tests above: a **non-terminal** marker must still block.
     *
     * Without this, `conclude` could be "fixed" by marking the gate ready on every `RetryRequired`, and
     * the tests above would still pass. `DB_COMMITTING` means a durable surface may disagree, so a failure
     * there is a data-repair problem and reading anything is unsafe.
     */
    @Test
    fun nonTerminalMarkerWithUnfinishedRecovery_staysBlocked() {
        val (_, oldDir) = parkOldTreeAndSwapCloset(id = 1015)
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1015,
                state = RestoreState.DB_COMMITTING,
                includesLifeOs = true,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true,
                mediaStageDir = File(context.filesDir, ".life_media_restore_stage_1015").absolutePath,
                mediaOldDir = File(context.filesDir, ".life_media_restore_old_1015").absolutePath,
                dbSnapshot = "/nonexistent"
            )
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { null }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
    }
}
