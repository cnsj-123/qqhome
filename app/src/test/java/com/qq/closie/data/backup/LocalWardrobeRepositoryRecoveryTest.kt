package com.qq.closie.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.repository.LocalWardrobeRepository
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
     *
     * ### Why the failure is injected rather than provoked with a non-empty directory
     *
     * This test used to create `closie/blocker/child`, on the theory that `File.renameTo` refuses to
     * replace an existing **non-empty** directory, so the rename back would fail "for real, without a
     * mock". The theory is sound about `renameTo` and wrong about the code path, which makes it a test
     * that could never have failed for the reason it claimed:
     *
     * `revertCloset` handles the "swap completed, marker lagging" case by **deleting `live` first**, then
     * renaming `old` into its place. So the blocker directory — child file and all — is gone before the
     * rename is attempted, the rename finds an empty destination, and it succeeds. The test then observed
     * `RetryRequired`, which it read as "the rename failed", when the real cause was that the marker
     * described `DB_COMMITTING` with no Life OS section and so had an *outstanding database half*. The
     * assertion happened to hold for a reason unrelated to what it was testing.
     *
     * With the truth table corrected, that accidental cause is gone too, so the fixture and the failure
     * both have to be explicit. [RestoreRecoveryManager.recoverOnStartup] takes an `internal` `RestoreFs`
     * seam precisely so a test can name the operation to fail; the parked tree is left untouched by the
     * failed rename, which is what the evidence assertions below pin.
     */
    @Test
    fun retryRequired_blocksTheRepositoryInsteadOfReadingHalfRestoredData() {
        val oldDir = parkInterruptedSwap(id = 900)
        val closieDir = File(context.filesDir, "closie")

        // Fail the one operation that matters: renaming the parked tree back into the live slot. The
        // filesystem is otherwise completely real.
        val failingFs = FailingRestoreFs(failRenameInto = closieDir.absolutePath)

        // The barrier runs first, exactly as `Application.onCreate` would.
        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = context,
            lifeDatabase = { null },
            fs = failingFs
        )
        assertThat(outcome).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)

        val failure = runCatching { LocalWardrobeRepository(context) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(failure).hasMessageThat().contains("恢复尚未完成")

        // The evidence survives — recovery refused, it did not tidy up after itself.
        assertThat(RestoreIntentStore.read(context)).isNotInstanceOf(AtomicJson.ReadResult.Missing::class.java)
        assertThat(oldDir.exists()).isTrue()

        // ### What the failed rename-back actually leaves behind, and why it is still safe
        //
        // `revertCloset` cannot rename `old` onto a non-empty `closie/`, so it removes the live tree
        // first and renames the parked one into the empty slot. The rename is the step that failed here,
        // so the slot was cleared but never refilled:
        //
        //   closie/  -> gone   (it held the backup's tree, which is exactly the half-restore to remove)
        //   oldDir   -> intact (it holds the user's wardrobe, untouched)
        //
        // The user's data is therefore still on disk and still named by the marker, so the next start
        // renames it back into place — see `aLaterSuccessfulStart_clearsTheBlock`. An earlier revision of
        // this assertion expected `closie/items.json` to still hold `new-item` after the failure; that is
        // not what the code does, and asserting it would have pinned a state the implementation never
        // produces. The property under test is the one asserted above: nothing read that directory as if
        // it were the user's wardrobe.
        assertThat(closieDir.exists()).isFalse()
        assertThat(File(oldDir, "items.json").readText()).contains("old-item")
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
     * process. This test simulates the restart by making the failure stop happening, then running
     * recovery again; the gate is process state, so it starts blocked and must be resolved by the second
     * run rather than inherited from the first.
     *
     * The obstruction is a real injected failure again, for the reason spelled out on
     * [retryRequired_blocksTheRepositoryInsteadOfReadingHalfRestoredData]: the parked tree's rename is
     * what has to fail, and a non-empty destination directory does not accomplish that because
     * `revertCloset` deletes the destination first.
     */
    @Test
    fun aLaterSuccessfulStart_clearsTheBlock() {
        parkInterruptedSwap(id = 905)
        val closieDir = File(context.filesDir, "closie")

        // First start: the rename back fails, so the gate blocks.
        assertThat(
            RestoreRecoveryManager.recoverOnStartup(
                context = context,
                lifeDatabase = { null },
                fs = FailingRestoreFs(failRenameInto = closieDir.absolutePath)
            )
        ).isInstanceOf(RecoveryOutcome.RetryRequired::class.java)
        assertThat(RestoreStartupGate.isReady).isFalse()
        // The failed attempt cleared the live slot without refilling it (see the note on the test above),
        // so the second start is the one that puts the user's wardrobe back — that is the state it has to
        // resolve, and it resolves it from the parked tree, which is still intact.
        assertThat(closieDir.exists()).isFalse()
        assertThat(File(File(context.filesDir, ".closie_restore_old_905"), "items.json").readText())
            .contains("old-item")

        // The obstruction is removed (in reality: the transient error is gone), and a *new* process
        // start runs recovery again with a working filesystem.
        RestoreStartupGate.resetForTesting()

        assertThat(RestoreRecoveryManager.recoverOnStartup(context = context, lifeDatabase = { null }))
            .isEqualTo(RecoveryOutcome.Completed)

        val repo = LocalWardrobeRepository(context)
        assertThat(repo.items.value.map { it.id }).containsExactly("old-item")
    }
}
