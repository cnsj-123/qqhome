package com.qq.closie.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.data.repository.RestoreRecoveryPendingException
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The layer that decides whether the app may serve data, and the one that actually reads `closie/`.
 *
 * `ClosieApplication.onCreate` runs recovery for every entry point, but that recovery is allowed to
 * fail — it must not bring the process down. That leaves one question for the repository: what does it
 * do when the data on disk is still mid-restore?
 *
 * The answer this file enforces is **refuse to open**. A repository that read anyway would parse a
 * directory that is neither the user's wardrobe nor the backup's — the swap was interrupted between the
 * two renames — and then publish it through its `StateFlow`s as if it were the user's data. That is a
 * far worse outcome than a failed launch: the marker, the parked tree and the snapshot are all still on
 * disk, so the next start repairs everything. Availability is not worth serving the wrong version of
 * the user's data.
 *
 * ### How it refuses, and why the mechanism is [RestoreStartupGate]
 *
 * The repository used to run the filesystem pass itself, from its constructor, guarded by an in-memory
 * "once per process" flag. That is gone. Two entry points racing to repair the same directories was
 * never a safety net — it was a second way to do renames, and the flag that was supposed to keep them
 * from overlapping latched on outcomes, which made the failure modes subtle. The startup barrier in
 * [RestoreRecoveryManager] is now the only thing that performs recovery, and the repository simply
 * **asks the gate whether the process is ready**. If it is not, there is exactly one reason: recovery
 * did not finish. So the refusal is decidable, not a guess.
 *
 * Note what these tests deliberately do *not* assert: they never check that the repository "degraded
 * gracefully". There is no graceful degradation here, by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalWardrobeRepositoryRecoveryTest {

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
        val prefixes = listOf(".closie_restore_", ".life_media_restore_", ".life_restore_dbsnap_")
        context.filesDir.listFiles().orEmpty()
            .filter { f -> prefixes.any { f.name.startsWith(it) } }
            .forEach { it.deleteRecursively() }
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
     * Stage a marker plus a live Closet that is already the *backup's* version — the shape a process
     * kill between the two renames leaves behind.
     */
    private fun parkInterruptedSwap(id: Long): File {
        val filesDir: File = context.filesDir
        val oldDir = File(filesDir, ".closie_restore_old_$id")
        writeWardrobeFiles(oldDir, """[{"id":"old-item","name":"用户原有的衣服"}]""")
        // The live directory is the backup's: the swap began and was interrupted.
        writeWardrobeFiles(File(filesDir, "closie"), """[{"id":"new-item","name":"备份里的衣服"}]""")
        RestoreIntentStore.write(
            context,
            RestoreIntent(
                id = id,
                state = RestoreState.STAGED,
                includesLifeOs = false,
                closetStageDir = File(filesDir, ".closie_restore_stage_$id").absolutePath,
                closetOldDir = oldDir.absolutePath,
                closetExistedBefore = true
            )
        )
        return oldDir
    }

    /**
     * A recovery that cannot finish must block the repository, not let it read a half-restored directory.
     *
     * The injected failure is the Closet rename back — the exact operation that stands between the user
     * and their own wardrobe. With it failing, `closie/` still holds the *backup's* item, so a repository
     * that opened would be handing the UI someone else's data.
     */
    @Test
    fun retryRequired_blocksTheRepositoryInsteadOfReadingHalfRestoredData() {
        val oldDir = parkInterruptedSwap(id = 900)
        val closieDir = File(context.filesDir, "closie")

        // Make `closie/` non-empty, which is what makes `renameTo(closieDir)` fail: `File.renameTo`
        // refuses when the destination is an existing non-empty directory. This is a real filesystem
        // refusal rather than a mock, and it is the same class of failure as a permission error.
        val blocker = File(closieDir, "blocker")
        blocker.mkdirs()
        File(blocker, "child").writeText("x")

        // The barrier runs first, exactly as `Application.onCreate` would.
        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        val failure = runCatching { LocalWardrobeRepository(context) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(failure).hasMessageThat().contains("恢复尚未完成")

        // The evidence survives — recovery refused, it did not tidy up after itself.
        assertThat(RestoreIntentStore.read(context)).isNotInstanceOf(AtomicJson.ReadResult.Missing::class.java)
        assertThat(oldDir.exists()).isTrue()
        // And crucially the backup's item was never exposed: the live directory still holds it, because
        // nothing was allowed to read it as if it were the user's wardrobe.
        assertThat(File(closieDir, "items.json").readText()).contains("new-item")
    }

    /**
     * The positive control: with recovery completed, the repository opens and serves the *user's* data.
     * Without this, the test above could pass simply by always throwing.
     */
    @Test
    fun healthyRecovery_opensTheRepositoryOnTheUsersData() {
        parkInterruptedSwap(id = 901)

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })
        assertThat(outcome).isEqualTo(RecoveryOutcome.Completed)

        val repo = LocalWardrobeRepository(context)

        assertThat(repo.items.value.map { it.id }).containsExactly("old-item")
        assertThat(repo.items.value.map { it.id }).doesNotContain("new-item")
        // The repair completed, so the marker is gone rather than left as litter.
        assertThat(RestoreIntentStore.read(context)).isInstanceOf(AtomicJson.ReadResult.Missing::class.java)
    }

    /**
     * An ordinary launch — no marker — must leave the repository usable.
     *
     * This is the other half of "block only when there is something to be blocked by". A gate that
     * started blocked and was never resolved would brick the app for every user who never restored
     * anything, which is most of them.
     */
    @Test
    fun ordinaryLaunch_opensTheRepository() {
        writeWardrobeFiles(File(context.filesDir, "closie"), """[{"id":"mine","name":"我的衣服"}]""")

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })
        assertThat(outcome).isEqualTo(RecoveryOutcome.NoWork)

        val repo = LocalWardrobeRepository(context)
        assertThat(repo.items.value.map { it.id }).containsExactly("mine")
    }

    /**
     * A corrupt marker must block the repository too.
     *
     * This is the nastiest shape of the bug the three-valued read exists to prevent: an unreadable marker
     * tells us nothing about whether `closie/` is mid-swap, so the only safe reading is "assume it is".
     * Opening anyway would be a coin flip on the user's wardrobe.
     */
    @Test
    fun corruptMarker_blocksTheRepositoryAndPreservesEvidence() {
        val oldDir = parkInterruptedSwap(id = 904)
        // Overwrite the valid marker with something that cannot be parsed.
        RestoreIntentStore.markerFile(context).writeText("{ \"id\": 904, \"state\":")

        val outcome = RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null })
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        val failure = runCatching { LocalWardrobeRepository(context) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RestoreRecoveryPendingException::class.java)
        // The marker is kept verbatim — it is the only record of the interrupted restore, and deleting an
        // unparseable file to "clean up" would destroy the evidence a later, smarter recovery could use.
        assertThat(RestoreIntentStore.markerFile(context).exists()).isTrue()
        assertThat(oldDir.exists()).isTrue()
    }

    /**
     * A blocked gate must not be a *permanent* state: a later start that recovers cleanly is usable.
     *
     * The failure is transient by design — the marker, the parked tree and the snapshot all outlive the
     * process — so the repository must not have cached the refusal in any way that survives a new
     * process. This test simulates the restart by clearing the obstruction and resolving the gate again.
     */
    @Test
    fun aLaterSuccessfulStart_clearsTheBlock() {
        parkInterruptedSwap(id = 905)
        val closieDir = File(context.filesDir, "closie")
        File(closieDir, "blocker").mkdirs()
        File(File(closieDir, "blocker"), "child").writeText("x")

        // First start: blocked.
        assertThat(RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null }))
            .isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()

        // The obstruction is removed (in reality: the transient error is gone), and a *new* process
        // start runs recovery again — the gate is process state, so it starts blocked and must be
        // resolved by this second run rather than inherited from the first.
        File(closieDir, "blocker").deleteRecursively()
        RestoreStartupGate.resetForTesting()

        assertThat(RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null }))
            .isEqualTo(RecoveryOutcome.Completed)

        val repo = LocalWardrobeRepository(context)
        assertThat(repo.items.value.map { it.id }).containsExactly("old-item")
    }
}
