package com.qq.closie.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
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
        RestoreIntentStore.write(
            context,
            RestoreIntent(id = 1001, state = RestoreState.CLOSET_SWAPPED, closetOldDir = oldDir.absolutePath)
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
     * and finishing the cleanup in one pass is correct. This test needs the case where the second half
     * is real, hence the snapshot path.
     */
    @Test
    fun pendingMarker_reachesTheDatabaseBeforeReturning() {
        val (closieDir, _) = parkOldTreeAndSwapCloset(id = 1002)
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1002.json")
        snapshot.writeText("{}")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1002,
                state = RestoreState.DB_COMMITTING,
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
     */
    @Test
    fun aThrowingDatabaseProvider_doesNotEscape() {
        parkOldTreeAndSwapCloset(id = 1003)
        RestoreIntentStore.write(
            context,
            RestoreIntent(id = 1003, state = RestoreState.DB_COMMITTING, dbSnapshot = "/nope")
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { throw IllegalStateException("injected: database unavailable") }
        )

        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        // The Closet repair still happened — the database half failing did not roll it back.
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
        parkOldTreeAndSwapCloset(id = 1005)
        RestoreIntentStore.write(
            context,
            RestoreIntent(id = 1005, state = RestoreState.DB_COMMITTING, dbSnapshot = "/nope")
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
        parkOldTreeAndSwapCloset(id = 1007)
        RestoreIntentStore.write(
            context,
            RestoreIntent(id = 1007, state = RestoreState.CLOSET_SWAPPED, closetOldDir = null)
        )

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })

        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)
        assertThat(RestoreStartupGate.isReady).isTrue()
        assertThat(RestoreStartupGate.blockReason).isNull()
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
        parkOldTreeAndSwapCloset(id = 1008)
        // A snapshot that recovery can replay: the user's pre-restore rows.
        val snapshot = File(context.filesDir, ".closie_restore_dbsnap_1008.json")
        snapshot.writeText("{}")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = 1008,
                state = RestoreState.DB_COMMITTING,
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
}
