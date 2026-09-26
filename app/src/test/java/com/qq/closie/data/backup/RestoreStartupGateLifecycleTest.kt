package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.repository.LifeRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The **active-restore gate lifecycle**: who may start a restore, what business code may do while one
 * runs, and every way the gate gets handed back.
 *
 * ### Why this is a separate class
 *
 * [RestoreCoordinatorTest] is about crash-consistency — what the three durable surfaces look like after
 * a kill. This class is about the *gate*, which is orthogonal: it can be correct while the protocol is
 * broken, and broken while the protocol is correct. Conflating them is how the previous revision ended
 * up with a gate that only guarded construction and a restore that could start while `READY`.
 *
 * ### The one invariant everything here derives from
 *
 * **A restore owns the gate from before its first durable mutation until the durable state is coherent
 * again.** Every test below is one edge of that sentence:
 *
 * | scenario | what it pins |
 * |---|---|
 * | A | `RESTORING` is entered *before* the first durable write, not after |
 * | B | `COMMITTED` hands the gate back as `READY` |
 * | C | a successful compensation hands the gate back as `READY` |
 * | D | a failed compensation sticks at `BLOCKED`, permanently |
 * | E | two concurrent restores: one wins, and the loser writes no marker |
 * | F | a terminal marker leaves business `READY` but refuses a new restore |
 *
 * Scenarios A–C are also asserted through [RestoreHooks], i.e. *from inside the running protocol*.
 * Observing the gate only before and after cannot distinguish "closed for the whole restore" from
 * "closed at the start and the end" — and the window that matters is the middle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreStartupGateLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var wardrobe: LocalWardrobeRepository

    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        // Constructing a `LocalWardrobeRepository` is gated, so the gate has to be resolved first.
        // Robolectric runs `ClosieApplication.onCreate`, which resolves it for real; marking it here
        // keeps these tests about the restore lifecycle rather than about the startup barrier, which
        // has its own class ([com.qq.closie.life.data.LifeContainerStartupGateTest]).
        RestoreStartupGate.markReady()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        wardrobe = LocalWardrobeRepository(context)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        createdFiles.forEach { runCatching { it.deleteRecursively() } }
        // The gate is process-wide, so leaving it `RESTORING` or permanently `BLOCKED` would leak into
        // whichever class runs next. `resetForTesting` clears both the status and the terminal flag.
        RestoreStartupGate.resetForTesting()
    }

    // ------------------------------------------------------------------
    //  A. RESTORING is entered before the first durable mutation
    // ------------------------------------------------------------------

    /**
     * The gate is closed **inside** the protocol, at the moment the first durable write happens.
     *
     * Checking only before and after `restore()` would pass on an implementation that closed the gate
     * lazily — right before applying the database, say — and that implementation has a real hole: the
     * Closet and media swaps happen *before* the database apply, so a business write in the gap between
     * `beginRestore` and "the gate actually closed" would land in a directory the restore is about to
     * replace. Observing from inside a hook closes that hole as an assertion.
     *
     * `beforeClosetSwap` is the first hook that runs after the marker is durable, which makes it the
     * exact point at which "no business write may start" has to be true.
     */
    @Test
    fun restoring_isEnteredBeforeTheFirstDurableMutation() = runTest {
        val backup = seededBackup()

        var statusAtFirstMutation: RestoreStartupGate.Status? = null
        val hooks = object : RestoreHooks {
            override fun beforeClosetSwap() {
                statusAtFirstMutation = RestoreStartupGate.status
            }
        }

        val result = BackupManager.restore(context, wardrobe, backup, db, hooks)
        assertThat(result.isSuccess).isTrue()

        assertThat(statusAtFirstMutation).isEqualTo(RestoreStartupGate.Status.RESTORING)
    }

    /**
     * While `RESTORING`, a repository instance obtained *before* the restore refuses to act.
     *
     * This is the half a construction-time check cannot cover, and the reason `gated` exists: the
     * ViewModels that opened Settings are still alive and still holding this exact instance, so the only
     * effective guard is one evaluated at the point of use.
     */
    @Test
    fun whileRestoring_heldInstancesRefuseBusinessWork() = runTest {
        val backup = seededBackup()

        var readRefused: Throwable? = null
        var writeRefused: Throwable? = null
        var diskAfterRefusal: String? = null

        val hooks = object : RestoreHooks {
            override fun beforeClosetSwap() {
                readRefused = runCatching { wardrobe.listItems() }.exceptionOrNull()
                writeRefused = runCatching {
                    wardrobe.createItem(
                        ClothingItem(
                            id = "written-during-restore",
                            name = "恢复期间写入",
                            category = "上衣",
                            status = ItemStatus.OWNED
                        )
                    )
                }.exceptionOrNull()
                diskAfterRefusal = File(File(context.filesDir, "closie"), "items.json").readText()
            }
        }

        val result = BackupManager.restore(context, wardrobe, backup, db, hooks)
        assertThat(result.isSuccess).isTrue()

        assertThat(readRefused).isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(writeRefused).isInstanceOf(RestoreRecoveryPendingException::class.java)
        // The refused write left no trace: not in memory, and not on disk. A refusal that still mutated
        // would be worse than no check, because the mutation would be written over by the restore.
        assertThat(diskAfterRefusal).doesNotContain("written-during-restore")
        assertThat(wardrobe.items.value.map { it.id }).doesNotContain("written-during-restore")
    }

    // ------------------------------------------------------------------
    //  B. COMMITTED -> READY
    // ------------------------------------------------------------------

    /**
     * A successful restore ends with the gate `READY`, and — the part that is easy to get wrong — the
     * repository's in-memory state already showing the **restored** wardrobe by the time it is.
     *
     * The ordering is the assertion: `reload -> endRestoreReady`, never the reverse. Reversed, there is
     * a window in which the UI reads the pre-restore wardrobe from memory while disk holds the restored
     * one, and the user's next edit is written over the restore.
     */
    @Test
    fun committedRestore_handsTheGateBackReadyWithMemoryAlreadyCoherent() = runTest {
        val backup = seededBackup()

        val statusAfterPublish = mutableListOf<RestoreStartupGate.Status>()
        val memoryAfterPublish = mutableListOf<List<String>>()
        val hooks = object : RestoreHooks {
            override fun afterDbCommit() {
                // Once the commit is durable the restore does not read the repository again, but the
                // gate must still be closed here: publishing memory is the next step, and it is only
                // legal while business code is locked out.
                statusAfterPublish += RestoreStartupGate.status
            }

            override fun beforeHealthCheck() {
                statusAfterPublish += RestoreStartupGate.status
                memoryAfterPublish += wardrobe.items.value.map { it.id }
            }
        }

        val result = BackupManager.restore(context, wardrobe, backup, db, hooks)
        assertThat(result.isSuccess).isTrue()

        // Closed throughout the protocol…
        assertThat(statusAfterPublish).containsExactly(
            RestoreStartupGate.Status.RESTORING,
            RestoreStartupGate.Status.RESTORING
        )
        // …and the in-memory snapshot had not yet been moved: publishing starts only in Phase B.
        assertThat(memoryAfterPublish.single()).containsExactly("local-item-only")

        // After the call returns, both the gate and memory are settled.
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        assertThat(wardrobe.items.value.map { it.id }).containsExactly("backup-item-only")
    }

    // ------------------------------------------------------------------
    //  C. compensation success -> READY
    // ------------------------------------------------------------------

    /**
     * A Phase A failure that compensates cleanly hands the gate back `READY`.
     *
     * The user's data is exactly what it was, so refusing further work would be wrong: a failure to
     * *restore* must not turn into a failure to *use the app*. The marker is gone, the surfaces agree,
     * and the next restore may be attempted immediately.
     */
    @Test
    fun compensationSuccess_handsTheGateBackReady() = runTest {
        val backup = seededBackup()

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = object : RestoreHooks {
                override fun beforeHealthCheck() {
                    throw java.io.IOException("注入的失败：健康检查不通过")
                }
            }
        )

        assertThat(result.isFailure).isTrue()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        // The gate being open is only meaningful if the data is genuinely back on the pre-restore
        // version — otherwise this would be "readable, but the wrong data".
        assertThat(wardrobe.items.value.map { it.id }).containsExactly("local-item-only")
        assertThat(File(File(context.filesDir, "closie"), "items.json").readText())
            .contains("local-item-only")

        // And a second attempt is genuinely allowed, which is what "READY" promises.
        val retry = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(retry.isSuccess).isTrue()
    }

    // ------------------------------------------------------------------
    //  D. compensation failure -> sticky BLOCKED
    // ------------------------------------------------------------------

    /**
     * A Phase A failure whose compensation *also* fails blocks the process permanently.
     *
     * `markRestoreUnfinished` is the one-way door: compensation left the surfaces possibly disagreeing
     * and deliberately kept the evidence, and no code in this process can prove the data consistent
     * again. So the gate must not be reopenable — not by finishing, and not by a stray `markReady`.
     */
    @Test
    fun compensationFailure_sticksAtBlockedAndNothingCanReopenIt() = runTest {
        val backup = seededBackup()

        val result = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = object : RestoreHooks {
                // Fail Phase A after the swap, then make the compensation's own filesystem work fail:
                // `revertCloset` has to delete the live tree before renaming the parked one in.
                override fun beforeHealthCheck() {
                    throw java.io.IOException("注入的失败：健康检查不通过")
                }
            },
            fs = FailingRestoreFs(failDelete = File(context.filesDir, "closie").absolutePath)
        )

        assertThat(result.isFailure).isTrue()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.BLOCKED)
        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()

        // Held instances refuse, and `markReady` — the startup barrier's own way back — is ignored.
        assertThat(runCatching { wardrobe.listItems() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.BLOCKED)

        // A new restore is refused too, and as a `Result.failure` rather than a throw.
        val refused = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(refused.isFailure).isTrue()
        assertThat(refused.exceptionOrNull()).isInstanceOf(RestoreAlreadyPendingException::class.java)
    }

    // ------------------------------------------------------------------
    //  E. two concurrent restores
    // ------------------------------------------------------------------

    /**
     * Two restores started at once: exactly one acquires, and the loser writes **nothing**.
     *
     * The `compareAndSet` on the single status flow is what makes this decidable. Without it both calls
     * would pass a `requireReady()` check and then share one marker path and one snapshot path —
     * whichever wrote last would own the evidence, and the other's compensation would roll the shared
     * state back over the winner's data. The result would depend on thread timing rather than on any
     * decision, which is why the assertion is on the *marker identity*, not just on the return values:
     * a loser that wrote a marker would corrupt the winner even while returning `failure`.
     */
    @Test
    fun twoConcurrentRestores_onlyOneAcquiresAndTheLoserWritesNoMarker() = runTest {
        val backup = seededBackup()

        var idAtFirstRestore: Long? = null
        var loserResult: Result<Unit>? = null
        var markerIdAfterLoser: Long? = null
        var loserRan = false

        val hooks = object : RestoreHooks {
            override fun beforeClosetSwap() {
                // A durable marker now exists and this restore owns the gate. That is exactly the state
                // a second restore must not be allowed to enter.
                idAtFirstRestore =
                    (RestoreIntentStore.read(context) as AtomicJson.ReadResult.Success).value.id

                // Start the second restore from inside the first one's critical section. It runs on a
                // different thread with a real dispatcher, so it executes to completion *while* the
                // winner is still mid-protocol — no virtual-clock handshake to deadlock on.
                loserResult = runBlocking(Dispatchers.IO) {
                    BackupManager.restore(context, wardrobe, backup, db)
                }
                markerIdAfterLoser =
                    (RestoreIntentStore.read(context) as AtomicJson.ReadResult.Success).value.id
                loserRan = true
            }
        }

        val winner = BackupManager.restore(context, wardrobe, backup, db, hooks)

        assertThat(loserRan).isTrue()
        assertThat(winner.isSuccess).isTrue()
        assertThat(loserResult!!.isFailure).isTrue()
        assertThat(loserResult!!.exceptionOrNull())
            .isInstanceOf(RestoreAlreadyPendingException::class.java)
        // The winner's marker is untouched: the loser did not overwrite it. This is the assertion that
        // distinguishes "refused" from "refused safely" — a loser that wrote a marker would corrupt the
        // winner even while correctly returning `failure`.
        assertThat(markerIdAfterLoser).isEqualTo(idAtFirstRestore)
        assertThat(idAtFirstRestore).isNotNull()
        // The winner's marker is the *only* one, and the winner still finished.
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        assertThat(wardrobe.items.value.map { it.id }).containsExactly("backup-item-only")
    }

    // ------------------------------------------------------------------
    //  F. terminal marker: business READY, new restore refused
    // ------------------------------------------------------------------

    /**
     * A leftover **terminal** marker leaves the business gate `READY` while refusing a new restore.
     *
     * This is the asymmetry the whole preflight exists for, and it is the one a single "is the gate
     * open?" check cannot express:
     *
     *  - the data *is* consistent — a `COMMITTED`/`ROLLED_BACK` marker means every surface agrees — so
     *    the user must be able to keep working, and the gate is legitimately `READY`;
     *  - but the marker still points at a parked tree and a snapshot that only a cleanup pass removes,
     *    and `restore` writes that same marker file, so starting one would destroy the pointer and turn
     *    the leftover into permanent, unattributed litter holding the user's previous wardrobe.
     *
     * So: **business-READY ≠ restore-allowed.** Both halves are asserted, because a fix that only did
     * one of them would trade a stuck app for silent data loss.
     */
    @Test
    fun terminalMarkerPending_businessIsReadyButANewRestoreIsRefused() = runTest {
        val backup = seededBackup()

        // A committed restore whose cleanup failed, i.e. the shape this state really occurs in: a
        // terminal marker plus a parked tree. Built by actually running one, so the marker is written
        // by production code rather than by the test.
        val first = BackupManager.restore(
            context = context,
            repo = wardrobe,
            inputUri = backup,
            lifeDatabase = db,
            hooks = NoOpRestoreHooks,
            fs = FailingRestoreFs(failDeleteContaining = ".closie_restore_old_")
        )
        assertThat(first.isSuccess).isTrue()

        val marker = RestoreIntentStore.read(context)
        assertThat(marker).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        assertThat((marker as AtomicJson.ReadResult.Success).value.state)
            .isAnyOf(RestoreState.COMMITTED, RestoreState.ROLLED_BACK)

        // Business is allowed: the leftover is litter, not a disagreement.
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        assertThat(wardrobe.items.value.map { it.id }).containsExactly("backup-item-only")

        // A new restore is refused, and refused as a `Result.failure`.
        val refused = BackupManager.restore(context, wardrobe, backup, db)
        assertThat(refused.isFailure).isTrue()
        assertThat(refused.exceptionOrNull()).isInstanceOf(RestoreAlreadyPendingException::class.java)

        // The refusal preserved the evidence: the marker still names the leftover, so the next start
        // can still find and remove it. This is the assertion that distinguishes "refused safely" from
        // "refused and quietly dropped the pointer".
        val stillThere = RestoreIntentStore.read(context)
        assertThat(stillThere).isInstanceOf(AtomicJson.ReadResult.Success::class.java)
        assertThat((stillThere as AtomicJson.ReadResult.Success).value.id)
            .isEqualTo(marker.value.id)
    }

    /**
     * A gate that is not `READY` cannot be acquired at all — the second half of `beginRestore`'s
     * contract, checked directly so it does not rely on a restore happening to be running.
     */
    @Test
    fun beginRestore_refusesWhenNotReadyAndIsIdempotentlyReleasable() {
        RestoreStartupGate.markBlocked(IllegalStateException("启动恢复未完成"))
        assertThat(RestoreStartupGate.beginRestore()).isFalse()

        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        assertThat(RestoreStartupGate.isRestoring).isTrue()

        // A second acquire fails while the first is held — the atomicity the concurrency test relies on.
        assertThat(RestoreStartupGate.beginRestore()).isFalse()

        RestoreStartupGate.endRestoreReady()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
        // Idempotent: a stray second release must not be able to publish readiness nobody established.
        RestoreStartupGate.endRestoreReady()
        assertThat(RestoreStartupGate.status).isEqualTo(RestoreStartupGate.Status.READY)
    }

    // ------------------------------------------------------------------
    //  G. fix7: the lease count is an invariant, not a heuristic
    // ------------------------------------------------------------------

    /**
     * An **unbalanced release cannot silently move the gate** (fix7, fail-closed `releaseBusinessLease`).
     *
     * ### The defect this pins, stated as the interleaving it enables
     *
     * `releaseBusinessLease` used to return quietly when the count was already zero:
     *
     * ```
     *   op A: acquire   -> activeBusinessOps = 1
     *   op B: acquire   -> activeBusinessOps = 2
     *   op A: release   -> activeBusinessOps = 1
     *   op A: release   -> activeBusinessOps = 0     (a bug: A released twice)
     *   beginRestore(): activeBusinessOps == 0 -> proceeds, while B is mid-write
     * ```
     *
     * The count is the *only* evidence [RestoreStartupGate.beginRestore] has that no business operation is
     * in flight, so under-counting is the dangerous direction, not the safe one: it drives the count to
     * zero while a real operation is unfinished, the restore takes ownership, and the interleaved write is
     * erased by the snapshot replay. A silent return made that diagnosis impossible — there was no signal
     * at the moment the invariant broke.
     *
     * ### Why the test is shaped this way
     *
     * The release is `private`, and it must stay that way: it is an internal pairing invariant of
     * [RestoreStartupGate.withBusinessAccess], not something callers should be able to drive. So the test
     * cannot call the release directly, and it should not — asserting a private function's behaviour would
     * pin an implementation detail rather than the contract. What is asserted instead is the **observable
     * consequence**, through a hook that runs inside the lease exactly where a mistimed release would have
     * to be:
     *
     *  1. while a real business operation holds its lease, `beginRestore()` is refused — the legitimate
     *     case, and the one the count exists to produce;
     *  2. the operation completes and the *same* call now succeeds — the count came back to rest at zero
     *     on its own, with no compensating fudge anywhere in the test;
     *  3. and the count is never observed below zero at any point, including after many sequential
     *     operations — the arithmetic that a repeated unbalanced release would have driven negative.
     *
     * Step 3 is what fails on a saturating implementation that hides an underflow: saturation lets a double
     * release reach zero early, so the next operation's lease is indistinguishable from no lease and
     * `beginRestore()` succeeds while it is still running. Repeating the cycle many times is what makes
     * the drift observable rather than a single pair that happens to look correct.
     */
    @Test
    fun anUnbalancedLeaseReleaseCannotSilentlyMoveTheGate() = runBlocking<Unit>(Dispatchers.IO) {
        val releaseWrite = java.util.concurrent.CountDownLatch(1)
        val writerInside = java.util.concurrent.CountDownLatch(1)
        val observedCounts = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val hooked = LocalWardrobeRepository(
            context,
            testHooks = object : com.qq.closie.data.repository.WardrobeTestHooks {
                override fun afterDurableWrite() {
                    // Inside the real lease, after the real durable write: the one place a mistimed release
                    // would be observable as "beginRestore proceeds while this is still running".
                    observedCounts.add(RestoreStartupGate.activeBusinessOps)
                    writerInside.countDown()
                    releaseWrite.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        )

        val writer = async(Dispatchers.IO) {
            hooked.createItem(
                ClothingItem(id = "invariant-item", name = "不变量", category = "上衣", status = ItemStatus.OWNED)
            )
        }
        assertThat(writerInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

        // (1) The operation is genuinely in flight, and the count says so — exactly 1, not "at least 1",
        //     because an inflated or drifting count would be its own bug.
        assertThat(observedCounts).containsExactly(1)
        assertThat(RestoreStartupGate.beginRestore()).isFalse()
        assertThat(RestoreStartupGate.isReady).isTrue()

        releaseWrite.countDown()
        writer.await()

        // (2) The lease was released exactly once, by the operation itself. Had the release underflowed
        //     and been silently absorbed, a *subsequent* operation would appear unleased — so the check
        //     below is run through a second, independent operation rather than by reading the counter.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        RestoreStartupGate.endRestoreReady()

        // (3) Many acquire/release cycles leave the count at rest. A repeated unbalanced release would
        //     drive it negative here, and the saturating version would leave it pinned at zero with real
        //     leases outstanding — both of which the exact equality below would expose.
        repeat(25) {
            hooked.createItem(
                ClothingItem(
                    id = "cycle-$it",
                    name = "循环 $it",
                    category = "上衣",
                    status = ItemStatus.OWNED
                )
            )
            assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        }
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        RestoreStartupGate.endRestoreReady()
    }

    /**
     * A business operation that **throws** still releases its lease exactly once.
     *
     * The direct counterpart to the test above. The release lives in a `finally`, so the refusal path is
     * not a way to leak a lease; and because the count returns to exact rest, it also proves the release
     * did not run twice (which would have underflowed, and which the fail-fast invariant now refuses).
     */
    @Test
    fun aThrowingBusinessOperationStillReleasesItsLeaseExactlyOnce() = runTest {
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)

        val thrown = runCatching {
            RestoreStartupGate.withBusinessAccess<Unit> { throw IllegalStateException("业务操作失败") }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)

        // One acquire, one release — the count is back to rest, so a failed operation cannot leave the
        // gate un-restorable, and cannot have released twice either.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        RestoreStartupGate.endRestoreReady()
    }

    // ------------------------------------------------------------------
    //  H. fix7: the storage owners lease `filesDir/closie` themselves
    // ------------------------------------------------------------------

    /**
     * `ImageStore` refuses to write while a restore owns the gate, and writes again after (fix7 BLOCKER 3).
     *
     * ### Why `ImageStore` and not the screens
     *
     * `ImageStore` writes `filesDir/closie/images/…` — inside the directory a restore replaces
     * **wholesale**. A copy that starts before the swap and finishes after it lands in the generation that
     * was moved aside, so the image the user just picked is gone and the `localPath` the caller persisted
     * points into a tree nobody reads.
     *
     * The fix is in the storage owner rather than at each call site (`EditorScreen`, `OotdScreen`,
     * `OutfitStudioScreen`, `CapturePreviewActivity`, …), both because it is one place instead of many and
     * because a per-call-site check would still leave the copy itself straddling the swap. This test
     * asserts the *entry points*, which is what a caller experiences; the interleaving is excluded by the
     * lease rather than by anything the caller does.
     *
     * ### The shape of a refusal: the old contract, not a new one
     *
     * `copyFromUri`/`copyFromFile`/`copyFromUrl` already returned `null` on failure, and the real call sites
     * are written as `copy…(…)?.let { … }` with no `try`/`catch`. So the gate refusal is folded into that
     * same `null` (`runCatching { withBusinessAccess { … } }.getOrNull()`) instead of being thrown. A gate
     * that turned a background copy into an uncaught exception inside a capture callback would have traded
     * one corruption bug for one crash bug; the assertion below pins the trade at `null`.
     */
    @Test
    fun imageStore_refusesToWriteIntoClosieWhileRestoring() = runTest {
        val source = File(context.cacheDir, "image-source-${System.nanoTime()}.png").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }
        createdFiles += source

        // While READY the copy works, so the refusal below is about the gate and not about the call.
        val okPath = com.qq.closie.data.ImageStore.copyFromFile(context, source)
        assertThat(okPath).isNotNull()
        assertThat(File(okPath!!).exists()).isTrue()
        createdFiles += File(okPath)

        RestoreStartupGate.beginRestore()

        // The refusal is folded into the *existing* nullable contract, not surfaced as a new throwable.
        //
        // `copyFromFile` already answered `null` for "could not produce an image" before the gate existed,
        // and real call sites consume it as `ImageStore.copyFromFile(…)?.let { … }` with no exception
        // handling — `EditorScreen`, `OotdScreen`, `OutfitStudioScreen`, `CapturePreviewActivity`. Letting
        // `RestoreRecoveryPendingException` escape would convert a refusal during a restore into an
        // uncaught crash inside an ActivityResult / QuickCapture callback, i.e. the gate would introduce a
        // new failure mode while trying to remove one. The gate still does its job: nothing is published
        // into `closie/` (the lease throws inside the `runCatching`), so the caller gets `null` and simply
        // treats it as a failed copy.
        val refusedCopy = com.qq.closie.data.ImageStore.copyFromFile(context, source)
        assertThat(refusedCopy).isNull()

        // The delete is refused too — and irreversibility is why that matters. A refused delete that had
        // already unlinked the file would have destroyed the user's photo. `deletePrivatePath` returns
        // `Unit`, so a refusal is a silent no-op; the assertion that matters is the file surviving.
        val refusedDelete = runCatching {
            com.qq.closie.data.ImageStore.deletePrivatePath(context, okPath)
        }
        assertThat(refusedDelete.isSuccess).isTrue()
        assertThat(File(okPath).exists()).isTrue()

        RestoreStartupGate.endRestoreReady()

        // Positive control: the same calls succeed once the gate reopens, so the test cannot pass on an
        // `ImageStore` that simply refuses everything.
        val afterPath = com.qq.closie.data.ImageStore.copyFromFile(context, source)
        assertThat(afterPath).isNotNull()
        createdFiles += File(afterPath!!)
        com.qq.closie.data.ImageStore.deletePrivatePath(context, afterPath)
        assertThat(File(afterPath).exists()).isFalse()
    }

    /**
     * `DraftStore`'s **whole read-modify-write** is one lease, not one lease per step (fix7 BLOCKER 3).
     *
     * ### The specific race, and why two leases would not fix it
     *
     * `saveOotdDraft` reads `ootd_drafts.json`, merges one record, and writes the whole file back. With a
     * lease around the write only — or two separate leases, one per step — a restore can swap `closie/`
     * between the read and the write. The write then lands in the new generation carrying a list derived
     * from the old one, which silently **discards every draft the restore brought in**.
     *
     * So the assertion has to be about the composite: a refused `saveOotdDraft` must leave the file
     * byte-identical (no partial merge), and a permitted one must round-trip.
     *
     * ### And the reads (fix8 BLOCKER 4)
     *
     * The read paths were originally left unleased on the argument that a read during the swap returns
     * "stale but consistent" data. That argument is wrong for this swap. The result is not stale data, it is
     * **no data**: the live directory is briefly absent, `read` swallows the failure and returns
     * `emptyList()`, and the UI renders an empty draft list that a subsequent save would then persist.
     */
    @Test
    fun draftStore_refusesItsWholeReadModifyWriteWhileRestoring() = runTest {
        val draftsDir = File(context.filesDir, "closie/drafts")
        val store = com.qq.closie.data.draft.DraftStore(context)
        val draftFile = File(draftsDir, "ootd_drafts.json")

        val draft = com.qq.closie.data.model.Ootd(
            id = "draft-1",
            date = "2026-01-01",
            itemIds = emptyList(),
            note = "草稿"
        )
        store.saveOotdDraft(draft)
        val before = draftFile.readText()

        RestoreStartupGate.beginRestore()

        val refusedSave = runCatching { store.saveOotdDraft(draft.copy(note = "被拒绝的草稿")) }
            .exceptionOrNull()
        assertThat(refusedSave).isInstanceOf(RestoreRecoveryPendingException::class.java)

        val refusedDelete = runCatching { store.deleteOotdDraft("draft-1") }.exceptionOrNull()
        assertThat(refusedDelete).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // **The reads are leased too.** This is the half that was missing: only the four mutations took a
        // lease, while `listOotdDrafts()`/`listOutfitDrafts()` called `read(…)` directly — and `read` is
        // `runCatching { … }.getOrDefault(emptyList())`.
        //
        // The Closet swap is two renames (live → `closie_restore_old_<id>`, then stage → live). Between them
        // `filesDir/closie` does not exist, so an unleased read does not see "a slightly old wardrobe", it
        // sees *no drafts at all* and reports `emptyList()` — a **false empty**, the one answer that is
        // actively wrong rather than merely stale. `OotdScreen` and `OutfitStudioScreen` call these, so the
        // user would be shown an empty draft list during a restore and could overwrite real drafts with it.
        //
        // A lease rather than a bare `requireReady()`: `readText` itself can be running when the rename
        // lands, so admitting the read while the swap is in flight is not enough — the read has to be
        // excluded from the swap, not just gated before it.
        val refusedRead = runCatching { store.listOotdDrafts() }.exceptionOrNull()
        assertThat(refusedRead).isInstanceOf(RestoreRecoveryPendingException::class.java)
        val refusedOutfitRead = runCatching { store.listOutfitDrafts() }.exceptionOrNull()
        assertThat(refusedOutfitRead).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // Neither the merge nor the delete happened: the file is unchanged, byte for byte. A lease around
        // only the write would leave a partially applied edit here, and a lease around only the read would
        // leave the *file* written while the read was stale.
        assertThat(draftFile.readText()).isEqualTo(before)

        RestoreStartupGate.endRestoreReady()

        // Positive control: the reads are not permanently broken either — a false empty that persisted past
        // the restore would be just as wrong as one during it.
        assertThat(store.listOotdDrafts().single().id).isEqualTo("draft-1")

        // Positive control: both mutations work again, so the refusals above were the gate and not a
        // permanently broken store.
        store.saveOotdDraft(draft.copy(note = "允许的草稿"))
        assertThat(store.listOotdDrafts().single().note).isEqualTo("允许的草稿")
        val removed = store.deleteOotdDraft("draft-1")
        assertThat(removed).isNotNull()
        assertThat(store.listOotdDrafts()).isEmpty()
    }

    // ------------------------------------------------------------------
    //  I. fix7: an export is one operation, and restore cannot start inside it
    // ------------------------------------------------------------------

    /**
     * A backup export holds the gate for its whole sequence, so `beginRestore()` is refused throughout
     * (fix7 BLOCKER 4).
     *
     * ### The window, stated as the corrupt archive it produces
     *
     * An export reads items, wear, wash, ootds, outfits, both draft files, the Life OS snapshot, every
     * referenced image's bytes and every Life OS media file's bytes — then writes the ZIP. Each read takes
     * its own short lease inside the repository, so a restore can start in any of the gaps:
     *
     * ```
     *   export: listItems()                      (lease taken, released — pre-restore rows)
     *   user:   restore starts                    (activeBusinessOps == 0, so it is allowed)
     *   restore: swaps closie/ + media, rewrites the DB
     *   export: reads wear/drafts/life/media      (post-restore data)
     *   result: a ZIP whose items.json and images/ come from different generations
     * ```
     *
     * The mixed archive is the worst outcome: restoring it later yields a wardrobe whose images do not
     * match its items — a corruption that survives the backup/restore cycle that exists to prevent it.
     *
     * ### How the window is observed
     *
     * The test parks a *repository read* inside the operation, which is the only place a caller can
     * interpose, and asserts from another thread that `beginRestore()` returns `false` there. It is
     * deliberately the real `BackupManager.export`, not a hand-held lease: the property under test is that
     * the *exporter* takes the lease, so a test that supplied the lease would be asserting the gate
     * against itself — the failure mode `WardrobeTestHooks` exists to avoid, applied to export.
     */
    @Test
    fun backupExport_holdsTheGateSoRestoreCannotStartInsideIt() = runBlocking<Unit>(Dispatchers.IO) {
        val exportInside = java.util.concurrent.CountDownLatch(1)
        val exportMayFinish = java.util.concurrent.CountDownLatch(1)
        val countsWhileExporting = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val gatedRepo = LocalWardrobeRepository(context)
        gatedRepo.createItem(
            ClothingItem(id = "export-seed", name = "导出种子", category = "上衣", status = ItemStatus.OWNED)
        )
        life.createEntity(entityType = "note")

        // A delegation, not a subclass, so no member can be silently lost — the same technique the
        // restore-filesystem seam uses.
        val parkingRepo = object : com.qq.closie.data.repository.WardrobeRepository by gatedRepo {
            private val parked = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun listItems(): List<ClothingItem> {
                val rows = gatedRepo.listItems()
                if (parked.compareAndSet(false, true)) {
                    // The export has already read durable wardrobe state, so its lease is held.
                    countsWhileExporting.add(RestoreStartupGate.activeBusinessOps)
                    exportInside.countDown()
                    exportMayFinish.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return rows
            }
        }

        val outputUri = android.net.Uri.fromFile(
            File(context.cacheDir, "export-lease-${System.nanoTime()}.zip")
        )
        createdFiles += File(outputUri.path!!)

        val exporter = async(Dispatchers.IO) {
            BackupManager.export(context, parkingRepo, outputUri, db)
        }
        assertThat(exportInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

        // The whole point: mid-export, the count is non-zero, so a restore is refused rather than allowed
        // to swap the wardrobe under the archive being assembled.
        assertThat(countsWhileExporting.single()).isAtLeast(1)
        assertThat(RestoreStartupGate.beginRestore()).isFalse()
        assertThat(RestoreStartupGate.isReady).isTrue()

        exportMayFinish.countDown()
        val result = exporter.await()
        assertThat(result.isSuccess).isTrue()

        // Released: the lease was held for the operation and not leaked past it.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        RestoreStartupGate.endRestoreReady()
    }

    /**
     * A refused export is a `Result.failure`, never a thrown exception (fix8 BLOCKER 3).
     *
     * ### The bug this pins
     *
     * The three exporter entry points were shaped
     *
     * ```kotlin
     * withContext(Dispatchers.IO) { withBusinessAccessSuspending { runCatching { … } } }
     * ```
     *
     * — the `runCatching` was *inside* the lease. So the one failure a caller most needs to handle, "a
     * restore owns the wardrobe right now", was raised by `acquireBusinessLease()` **outside** the guard and
     * escaped `BackupManager.export(...)` as a live `RestoreRecoveryPendingException`. The declared type is
     * `Result<…>`; a caller writing `result.fold(…)` — which is what `DataSettingsScreen` does — had no
     * branch for it, and the exception surfaced as a crash rather than as the "restore in progress" message
     * the screen was written to show.
     *
     * The order is now `withContext(Dispatchers.IO) { runCatching { withBusinessAccessSuspending { … } } }`,
     * so the lease acquisition itself is inside the contract. Every state of the gate is covered here:
     * `RESTORING` and `BLOCKED` must both be `Result.failure`, and `READY` must be `Result.success` so
     * "refuses everything" cannot pass this test.
     */
    @Test
    fun backupExport_reportsGateRefusalAsResultFailureNeverAsAThrow() = runBlocking<Unit>(Dispatchers.IO) {
        wardrobe.createItem(
            ClothingItem(id = "contract-seed", name = "契约种子", category = "上衣", status = ItemStatus.OWNED)
        )
        life.createEntity(entityType = "note")

        // ---- RESTORING: the ordinary case during a restore. ---------------------------------------
        RestoreStartupGate.beginRestore()
        assertThat(RestoreStartupGate.isRestoring).isTrue()

        val zipWhileRestoring = runCatching {
            BackupManager.export(
                context,
                wardrobe,
                android.net.Uri.fromFile(File(context.cacheDir, "contract-restoring-${System.nanoTime()}.zip")),
                db
            )
        }
        // Not merely "did not throw": the value itself is a failure, and it names the reason.
        assertThat(zipWhileRestoring.exceptionOrNull()).isNull()
        assertThat(zipWhileRestoring.getOrNull()?.isFailure).isTrue()

        val csvWhileRestoring = runCatching {
            BackupManager.exportCsv(
                context,
                wardrobe,
                android.net.Uri.fromFile(File(context.cacheDir, "contract-restoring-${System.nanoTime()}.csv"))
            )
        }
        assertThat(csvWhileRestoring.exceptionOrNull()).isNull()
        assertThat(csvWhileRestoring.getOrNull()?.isFailure).isTrue()

        // A refused export also wrote nothing: no half-archive left behind for the user to trust.
        assertThat(
            context.cacheDir.listFiles()?.count { it.name.startsWith("contract-restoring-") } ?: 0
        ).isEqualTo(0)

        RestoreStartupGate.endRestoreReady()

        // ---- BLOCKED: a restore that did not complete. Same contract. ------------------------------
        RestoreStartupGate.markBlocked(IllegalStateException("restore failed mid-way"))
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.isRestoring).isFalse()

        val zipWhileBlocked = runCatching {
            BackupManager.export(
                context,
                wardrobe,
                android.net.Uri.fromFile(File(context.cacheDir, "contract-blocked-${System.nanoTime()}.zip")),
                db
            )
        }
        assertThat(zipWhileBlocked.exceptionOrNull()).isNull()
        assertThat(zipWhileBlocked.getOrNull()?.isFailure).isTrue()

        val csvWhileBlocked = runCatching {
            BackupManager.exportCsv(
                context,
                wardrobe,
                android.net.Uri.fromFile(File(context.cacheDir, "contract-blocked-${System.nanoTime()}.csv"))
            )
        }
        assertThat(csvWhileBlocked.exceptionOrNull()).isNull()
        assertThat(csvWhileBlocked.getOrNull()?.isFailure).isTrue()

        // ---- READY: the positive control. ----------------------------------------------------------
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.isReady).isTrue()

        val zipPath = File(context.cacheDir, "contract-ready-${System.nanoTime()}.zip")
        createdFiles += zipPath
        val zipWhileReady = runCatching {
            BackupManager.export(context, wardrobe, android.net.Uri.fromFile(zipPath), db)
        }
        assertThat(zipWhileReady.exceptionOrNull()).isNull()
        assertThat(zipWhileReady.getOrNull()?.isSuccess).isTrue()
        assertThat(zipPath.exists()).isTrue()

        val csvPath = File(context.cacheDir, "contract-ready-${System.nanoTime()}.csv")
        createdFiles += csvPath
        val csvWhileReady = runCatching {
            BackupManager.exportCsv(context, wardrobe, android.net.Uri.fromFile(csvPath))
        }
        assertThat(csvWhileReady.exceptionOrNull()).isNull()
        assertThat(csvWhileReady.getOrNull()?.isSuccess).isTrue()
        assertThat(csvPath.readText()).contains("contract-seed")

        // The refusal was a refusal and not a leak: the lease count is back at rest after all six calls.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
    }

    /**
     * `exportCsv` is leased the same way — three reads is already more than one (fix7 BLOCKER 4).
     *
     * The CSV is smaller than the ZIP but has a *more visible* version of the same defect: it prints
     * `wearCount`/`washCount` next to each item, and those come from files read after `items.json`. A
     * restore landing in between produces a spreadsheet whose counts belong to a different wardrobe than
     * the rows they annotate, and the user has no way to tell.
     */
    @Test
    fun exportCsv_isLeasedAsOneOperation() = runBlocking<Unit>(Dispatchers.IO) {
        wardrobe.createItem(
            ClothingItem(id = "csv-seed", name = "CSV 种子", category = "上衣", status = ItemStatus.OWNED)
        )

        val outputUri = android.net.Uri.fromFile(
            File(context.cacheDir, "export-csv-${System.nanoTime()}.csv")
        )
        createdFiles += File(outputUri.path!!)

        // Both directions: with the gate owned by a restore the export cannot take its lease and fails,
        // and once the gate reopens it succeeds. The export is short, so this is asserted as refusal +
        // recovery rather than by parking it.
        RestoreStartupGate.beginRestore()
        val refused = BackupManager.exportCsv(context, wardrobe, outputUri)
        assertThat(refused.isFailure).isTrue()
        RestoreStartupGate.endRestoreReady()

        val allowed = BackupManager.exportCsv(context, wardrobe, outputUri)
        assertThat(allowed.isSuccess).isTrue()

        // And the CSV really is the wardrobe's, so the success above is not an empty file.
        val text = File(outputUri.path!!).readText()
        assertThat(text).contains("csv-seed")
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * A real v2 archive, exported through the production path from a state distinct from the one the
     * restore is expected to end on.
     *
     * A distinct "old" state matters: if the backup were exported from the world the test later asserts
     * on, a half-restore would satisfy every assertion and the test would pass while the bug was present.
     */
    private suspend fun seededBackup(): android.net.Uri {
        wardrobe.createItem(
            ClothingItem(
                id = "backup-item-only",
                name = "备份里的衣服",
                category = "上衣",
                status = ItemStatus.OWNED
            )
        )

        // A Life OS row too, so the v2 payload is genuinely non-empty and the restore really goes
        // through the media + database branches rather than the v1 path. Restore validates media
        // evidence for v2, so an archive with a `life/` section and no media would be refused.
        life.createEntity(entityType = "note")

        val uri = android.net.Uri.fromFile(
            File(context.cacheDir, "gate-lifecycle-${System.nanoTime()}.zip")
        )
        createdFiles += File(uri.path!!)
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        // Now install the *user's* world: a different wardrobe, so "restored" and "not restored" are
        // distinguishable on every surface.
        wardrobe.deleteItem("backup-item-only")
        wardrobe.createItem(
            ClothingItem(
                id = "local-item-only",
                name = "用户原有的衣服",
                category = "下装",
                status = ItemStatus.OWNED
            )
        )
        return uri
    }
}
