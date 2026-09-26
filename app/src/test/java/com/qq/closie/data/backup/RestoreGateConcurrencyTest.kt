package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.model.WearEvent
import com.qq.closie.data.model.WearSource
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.data.repository.WardrobeSnapshot
import com.qq.closie.data.repository.WardrobeTestHooks
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The **lease contract** — the half of the gate that a `requireReady()` check cannot express.
 *
 * ### What is actually under test
 *
 * Every test in this class pins one edge of a single sentence: *a business operation and a restore
 * cannot overlap, in either direction.* The previous design could only assert the second direction
 * ("no new work starts while a restore runs") because it had no way to know work was already in
 * flight. The lease is what makes the first direction ("no restore starts while work is in flight")
 * enforceable, and these are the cases where the missing direction loses data:
 *
 * ```
 *   operation holds no lease:
 *     requireReady() -> ok
 *     ... restore begins, swaps the tree the operation is writing into ...
 *     operation's write lands in the restored tree
 *
 *   restore holds no exclusion:
 *     beginRestore() -> ok
 *     ... an import that started a moment ago is still copying 40 MB ...
 *     the import commits a row for a file the restore just replaced
 * ```
 *
 * ### Why real threads and a real latch, not `runTest` with a virtual scheduler
 *
 * The property under test is *atomicity of a compare-and-set between two threads*. A virtual scheduler
 * runs coroutines to completion one at a time in a deterministic order, which means it cannot produce
 * the interleaving this is about — a test written that way passes whether or not the CAS exists. So the
 * racing tests drive real `Dispatchers.IO` threads and use a [CyclicBarrier] to release them together,
 * and the counting tests read [RestoreStartupGate.activeBusinessOps] directly.
 *
 * The deterministic tests (a refusal, a nested lease) use `runTest` because there is nothing to race —
 * and saying so explicitly keeps a flaky-looking test from being added "just in case".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreGateConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var media: MediaRepository
    private lateinit var capture: CaptureRepository

    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        media = MediaRepository(db)
        capture = CaptureRepository(db)
        // No shared `LocalWardrobeRepository` field: the two wardrobe tests each construct their own with
        // the seam they need, because a shared instance would carry one test's hook into another.
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        createdFiles.forEach { runCatching { it.deleteRecursively() } }
        RestoreStartupGate.resetForTesting()
    }

    // ==================================================================
    //  1. Lease accounting: re-entrancy and balance
    // ==================================================================

    /**
     * Nested leases count up and back down to exactly zero.
     *
     * This is the precondition of the barrier, not a detail: [RestoreStartupGate.beginRestore] refuses
     * while `activeBusinessOps > 0`, so an implementation that under-counted on release would leave the
     * gate permanently unable to start a restore — the app would look "fine" and silently refuse every
     * restore until it was restarted. Re-entrancy is required because repository methods legitimately
     * call one another (a repository transaction wrapping repository writes).
     */
    @Test
    fun nestedLeases_countUpAndBackToZero() = runTest {
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)

        RestoreStartupGate.withBusinessAccess {
            assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(1)
            RestoreStartupGate.withBusinessAccess {
                assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(2)
                RestoreStartupGate.withBusinessAccess {
                    assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(3)
                }
                assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(2)
            }
            assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(1)
        }

        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
    }

    /**
     * A lease is released even when the body throws — including a `CancellationException`.
     *
     * A leaked lease is not a visible bug: it looks exactly like "the gate works", except that from then
     * on every restore is refused with "已有恢复正在进行" and the user can never restore again without
     * restarting. The `finally` is the whole reason the helper exists, so it is asserted directly rather
     * than inferred from a higher-level test.
     */
    @Test
    fun leaseIsReleasedWhenTheBodyThrows() = runTest {
        runCatching {
            RestoreStartupGate.withBusinessAccess { throw IllegalStateException("boom") }
        }
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)

        runCatching {
            RestoreStartupGate.withBusinessAccessSuspending {
                delay(1)
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)

        // And the gate is genuinely usable afterwards — a restore can start.
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
    }

    // ==================================================================
    //  2. beginRestore vs an in-flight business operation
    // ==================================================================

    /**
     * A business lease held by another thread blocks `beginRestore`, which reports "no" rather than
     * waiting — and the block lifts the moment the lease is released.
     *
     * This is direction one, and it is the direction the previous design could not express at all. The
     * refusal must be a *return value* (`false`), not a block: the holder may legitimately take an
     * unbounded time (a large media import), and waiting would hold the restore thread for it.
     */
    @Test
    fun beginRestore_isRefusedWhileABusinessLeaseIsHeld() = runBlocking<Unit>(Dispatchers.IO) {
        val insideLease = CountDownLatch(1)
        val releaseLease = CountDownLatch(1)
        val beginAttempted = CountDownLatch(1)
        val beginResult = AtomicInteger(-1)

        val holder = async(Dispatchers.IO) {
            RestoreStartupGate.withBusinessAccess {
                insideLease.countDown()
                // Hold until the other thread has tried (and failed) to take ownership.
                releaseLease.await(5, TimeUnit.SECONDS)
            }
        }
        insideLease.await(5, TimeUnit.SECONDS)
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(1)

        val restorer = async(Dispatchers.IO) {
            RestoreStartupGate.markReady()
            beginAttempted.countDown()
            beginResult.set(if (RestoreStartupGate.beginRestore()) 1 else 0)
        }
        beginAttempted.await(5, TimeUnit.SECONDS)
        restorer.await()
        assertThat(beginResult.get()).isEqualTo(0)
        // Still READY: a refused beginRestore must not have moved the status to RESTORING.
        assertThat(RestoreStartupGate.isReady).isTrue()

        // The refusal was a *refusal*, not a wait: release the lease and the same call now succeeds.
        releaseLease.countDown()
        holder.await()
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        assertThat(RestoreStartupGate.isRestoring).isTrue()
    }

    /**
     * While `RESTORING`, a business lease cannot be acquired — the other direction, and the one that
     * closes the TOCTOU window.
     *
     * The refusal is immediate and typed, because the caller is business code that has no business
     * running during a restore. A second, sharper point: the *nested* acquisition inside an already-held
     * lease must also be refused when the gate closes, since a repository method calling another
     * repository method is the normal case.
     */
    @Test
    fun leasesCannotBeAcquiredWhileRestoring() = runTest {
        assertThat(RestoreStartupGate.beginRestore()).isTrue()

        val first = runCatching { RestoreStartupGate.withBusinessAccess { 1 } }.exceptionOrNull()
        assertThat(first).isInstanceOf(RestoreRecoveryPendingException::class.java)

        val suspending = runCatching {
            RestoreStartupGate.withBusinessAccessSuspending { 1 }
        }.exceptionOrNull()
        assertThat(suspending).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // Never acquired, so never leaked.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)

        RestoreStartupGate.endRestoreReady()
        assertThat(RestoreStartupGate.withBusinessAccess { 1 }).isEqualTo(1)
    }

    /**
     * A sticky `BLOCKED` gate refuses a lease *and* refuses ownership, permanently.
     *
     * `markRestoreUnfinished` is the one-way door: after a compensation failure nothing in this process
     * may claim the data is readable. Both halves are asserted because a design that only blocked
     * *reads* would let the user immediately start another restore over unrepaired evidence.
     */
    @Test
    fun stickyBlock_refusesBothLeasesAndOwnership() = runTest {
        RestoreStartupGate.beginRestore()
        RestoreStartupGate.markRestoreUnfinished(IllegalStateException("compensation failed"))

        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()
        assertThat(RestoreStartupGate.isReady).isFalse()

        assertThat(runCatching { RestoreStartupGate.withBusinessAccess { 1 } }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(RestoreStartupGate.beginRestore()).isFalse()

        // A late `markReady` from a stray recovery pass must not reopen it.
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()
    }

    // ==================================================================
    //  3. The race itself: exactly one winner
    // ==================================================================

    /**
     * Many threads call `beginRestore` behind a barrier; **exactly one** wins.
     *
     * The assertion is not "at least one succeeded" but "exactly one did", because the defect this
     * prevents is two concurrent restores interleaving their markers and snapshots in
     * `.life_restore_intent.json` — where the loser's compensation reverts the winner's data. A test
     * that only checked "someone got in" would pass on a design with no exclusion whatsoever.
     */
    @Test
    fun concurrentBeginRestore_hasExactlyOneWinner() = runBlocking<Unit>(Dispatchers.IO) {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val winners = AtomicInteger(0)
        val losers = AtomicInteger(0)

        val jobs = (0 until threads).map {
            async(Dispatchers.IO) {
                barrier.await(5, TimeUnit.SECONDS)
                if (RestoreStartupGate.beginRestore()) winners.incrementAndGet() else losers.incrementAndGet()
            }
        }
        jobs.awaitAll()

        assertThat(winners.get()).isEqualTo(1)
        assertThat(losers.get()).isEqualTo(threads - 1)
        assertThat(RestoreStartupGate.isRestoring).isTrue()
    }

    /**
     * Many threads take a lease and attempt `beginRestore` concurrently; the restore may only win if it
     * wins *before every* lease, and if any lease is outstanding at that instant it must lose.
     *
     * This is the interleaving that the plain "one winner" test cannot reach: it combines both
     * directions in one race. The invariant asserted at the end is the complementary one — **it is never
     * true that a restore is running while a lease is held**, which is exactly the sentence the whole
     * lease design exists to make true.
     */
    @Test
    fun beginRestore_neverSucceedsWhileAnyLeaseIsHeld() = runBlocking<Unit>(Dispatchers.IO) {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val violations = AtomicInteger(0)
        val restoreWon = AtomicInteger(0)

        val jobs = (0 until threads).mapIndexed { index, _ ->
            async(Dispatchers.IO) {
                barrier.await(5, TimeUnit.SECONDS)
                if (index == 0) {
                    // The would-be restorer.
                    if (RestoreStartupGate.beginRestore()) {
                        restoreWon.incrementAndGet()
                        // If it won, no lease may be obtainable, and none may be counted.
                        if (RestoreStartupGate.activeBusinessOps != 0) violations.incrementAndGet()
                        if (runCatching { RestoreStartupGate.withBusinessAccess { 1 } }.isSuccess) {
                            violations.incrementAndGet()
                        }
                    }
                } else {
                    // A would-be business operation. It either takes the lease (and then the restore
                    // must have lost) or is refused (the restore won first) — never both.
                    val acquired = runCatching {
                        RestoreStartupGate.withBusinessAccess {
                            if (RestoreStartupGate.isRestoring) violations.incrementAndGet()
                            Thread.sleep(2)
                        }
                    }.isSuccess
                    if (acquired && RestoreStartupGate.isRestoring) violations.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertThat(violations.get()).isEqualTo(0)
        assertThat(restoreWon.get()).isAtMost(1)
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
    }

    /**
     * `RestoreStartupGate.activeBusinessOps` returns to zero after many concurrent, throwing and nested
     * leases — the accounting equivalent of a soak test.
     *
     * A counter that drifts by one under contention would produce a gate that eventually refuses every
     * restore forever, with no failing operation to point at. Hammering it is the only cheap way to catch
     * a non-atomic increment or a lost release.
     */
    @Test
    fun businessOpCount_returnsToZeroUnderConcurrentContention() = runBlocking<Unit>(Dispatchers.IO) {
        val threads = 16
        val iterations = 200
        val barrier = CyclicBarrier(threads)

        val jobs = (0 until threads).map {
            async(Dispatchers.IO) {
                barrier.await(5, TimeUnit.SECONDS)
                repeat(iterations) { i ->
                    runCatching {
                        RestoreStartupGate.withBusinessAccess {
                            if (i % 3 == 0) {
                                // Nested, to exercise the re-entrant path.
                                RestoreStartupGate.withBusinessAccess { Unit }
                            }
                            if (i % 7 == 0) throw IllegalStateException("injected")
                        }
                    }
                }
            }
        }
        jobs.awaitAll()

        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
    }

    // ==================================================================
    //  4. Real business sequences cannot overlap a restore
    // ==================================================================

    /**
     * A wardrobe write that is already inside its durable sequence makes `beginRestore` refuse — proved
     * with a real `LocalWardrobeRepository`, parked inside its own durable operation.
     *
     * ### What was wrong with the previous version of this test
     *
     * It opened the lease itself and then called `createItem` inside it:
     *
     * ```
     *   val writer = async { RestoreStartupGate.withBusinessAccess { wardrobe.createItem(...); held.countDown() } }
     *   ...
     *   assertThat(RestoreStartupGate.beginRestore()).isFalse()
     * ```
     *
     * and its doc claimed the property was "proved with a real `LocalWardrobeRepository`, not a synthetic
     * lease". It was the opposite. The lease that made `beginRestore` fail was the **test's** lease; the
     * repository's own lease was an invisible nested increment on top of it. The test would have kept
     * passing if `createItem` stopped taking a lease at all — which is precisely the regression it exists
     * to catch. The property under test was written into the test.
     *
     * ### The real seam
     *
     * [WardrobeTestHooks.afterDurableWrite] is invoked by `createItem` **after** its durable write and
     * **while it still holds its own lease**. So the thing parked below is the production operation, and
     * the only lease that exists is the one the repository took:
     *
     * ```
     *   wardrobe.createItem(...)                  <- production call, production lease
     *     requireReady()
     *     write("items.json", ...)                <- durable
     *     testHooks.afterDurableWrite()           <- parks here, lease still held
     *   (another thread) beginRestore() -> false
     * ```
     *
     * Nothing in this test opens a lease. If the repository stopped taking one, `beginRestore` would
     * succeed while the write was parked and `isFalse()` below would fail — which is what makes the
     * assertion meaningful.
     */
    @Test
    fun wardrobeWrite_inFlight_blocksBeginRestore() = runBlocking<Unit>(Dispatchers.IO) {
        // A repository wired to the seam. `LocalWardrobeRepository`'s own `init` reads the five files, so
        // it is constructed first; the parked write below is a *second* call on that instance.
        val writesEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val hooked = LocalWardrobeRepository(
            context,
            testHooks = object : WardrobeTestHooks {
                override fun afterDurableWrite() {
                    writesEntered.countDown()
                    // Held until the asserting thread has had its say. Deliberately a real latch rather
                    // than a delay: the point is to freeze the production call at a known point, not to
                    // hope a sleep is long enough.
                    releaseWrite.await(5, TimeUnit.SECONDS)
                }
            }
        )

        hooked.createItem(
            ClothingItem(
                id = "concurrent-item",
                name = "并发写入的前一项",
                category = "上衣",
                status = ItemStatus.OWNED
            )
        )

        // The production call parks *inside* its lease.
        val writer = async(Dispatchers.IO) {
            hooked.createItem(
                ClothingItem(
                    id = "in-flight-item",
                    name = "写入进行中",
                    category = "上衣",
                    status = ItemStatus.OWNED
                )
            )
        }
        assertThat(writesEntered.await(5, TimeUnit.SECONDS)).isTrue()

        // The write has already been committed to disk and its lease is still out, so a restore must not
        // be able to take ownership. No lease is held by this test: the only one in existence is the
        // repository's own.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(1)
        assertThat(RestoreStartupGate.beginRestore()).isFalse()

        releaseWrite.countDown()
        writer.await()

        // Released: the balance is back to zero and the same call now succeeds, so the refusal above was
        // about the in-flight operation and not about a permanently wedged gate.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()

        // And the write really did land — the hook does not stand in for the durable work.
        RestoreStartupGate.endRestoreReady()
        assertThat(hooked.listItems().map { it.id }).containsAtLeast("concurrent-item", "in-flight-item")
    }

    /**
     * The **whole-generation** case for the recovery republish, asserted by collecting the one canonical
     * flow (fix8 BLOCKER 2).
     *
     * ### What was wrong before, and why a stronger implementation was the fix rather than a weaker test
     *
     * An earlier revision documented the defect honestly — five `MutableStateFlow.value =` assignments are
     * five stores, so a collector resumed between two of them can compose a wardrobe from two generations —
     * and then tested a *weaker* property: that generations never go backwards, shown by collecting three
     * flows separately and comparing their orderings. That test passed, but it passed on a design that
     * still permitted the mixture, and it could not have failed: three independent collectors have three
     * independent resume points, so "the wear collector saw the change after the items collector did" is
     * not something an ordering assertion can even observe reliably, let alone forbid.
     *
     * The requirement was therefore kept and the *implementation* changed to meet it: the five lists live
     * in one [com.qq.closie.data.repository.WardrobeSnapshot] behind one `MutableStateFlow`, published at
     * [com.qq.closie.data.repository.WardrobeRepository.snapshot]. This test collects **that flow**, once,
     * and asserts the only thing that matters:
     *
     * > every emission is a whole generation 1 or a whole generation 2 — never a mixture.
     *
     * This is the assertion the previous version could not make, because it never held a single emission
     * that contained more than one surface.
     *
     * ### How the two generations are made distinguishable
     *
     * Generation 1 is created by the constructor's initial read: one item ("gen-1-item") and no wear
     * events. Generation 2 is written to disk directly by the test, then published by the exact call a
     * completed restore makes while the gate is still closed. The two are distinguishable on *both*
     * surfaces, so a mixture has a shape the assertions below can name: `(gen-2 items, 0 wear)` or
     * `(gen-1 items, 1 wear)`.
     */
    @Test
    fun recoveryReload_neverShowsACrossGenerationWardrobeToALongLivedObserver() = runBlocking<Unit>(Dispatchers.IO) {
        val hooked = LocalWardrobeRepository(context, testHooks = null)

        hooked.createItem(
            ClothingItem(id = "gen-1-item", name = "第一代", category = "上衣", status = ItemStatus.OWNED)
        )

        // Generation 2 on disk, written behind the repository's back: an item that did not exist in
        // generation 1, plus a wear event that did not exist either. Both must appear together.
        val wearDir = File(context.filesDir, "closie")
        File(wearDir, "items.json").writeText(
            BackupValidator.gson.toJson(
                listOf(ClothingItem(id = "gen-2-item", name = "第二代", category = "上衣", status = ItemStatus.OWNED))
            )
        )
        File(wearDir, "wear.json").writeText(
            BackupValidator.gson.toJson(
                listOf(
                    WearEvent(
                        id = "gen-2-wear",
                        itemId = "gen-2-item",
                        date = "2026-01-01",
                        source = WearSource.MANUAL,
                        ootdId = null,
                        note = ""
                    )
                )
            )
        )
        File(wearDir, "ootds.json").writeText("[]")
        File(wearDir, "outfits.json").writeText("[]")

        // ONE collector on ONE flow. Every emission is a complete wardrobe value, so the cross-surface
        // comparison is now a property of a single observed object rather than a hope about two
        // collectors' relative timing.
        val seen = java.util.Collections.synchronizedList(mutableListOf<WardrobeSnapshot>())
        val started = CountDownLatch(1)
        val collector = launch(Dispatchers.IO) {
            hooked.snapshot.collect { snap ->
                started.countDown()
                seen.add(snap)
            }
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        delay(TRACKER_SETTLE_MS)

        // The exact call a completed restore makes while the gate is still closed.
        RestoreStartupGate.beginRestore()
        hooked.reloadFromDiskForRecoveryChecked()
        RestoreStartupGate.endRestoreReady()

        delay(TRACKER_SETTLE_MS)
        collector.cancel()
        collector.join()

        assertThat(seen).isNotEmpty()

        // ---- The whole assertion, stated once. ------------------------------------------------------
        //
        // Generation 1 is exactly (items = [gen-1-item], wear = []). Generation 2 is exactly
        // (items = [gen-2-item], wear = [gen-2-wear]). Anything else is a mixture, and a mixture is
        // what this test forbids — not "a mixture is unlikely", not "a mixture did not happen this
        // run", but "no observed emission is a mixture".
        val gen1 = seen.filter { it.items.map { i -> i.id } == listOf("gen-1-item") && it.wearEvents.isEmpty() }
        val gen2 = seen.filter { it.items.map { i -> i.id } == listOf("gen-2-item") && it.wearEvents.map { w -> w.id } == listOf("gen-2-wear") }
        val mixed = seen - gen1.toSet() - gen2.toSet()

        assertThat(mixed.map { snap -> snap.items.map { it.id } to snap.wearEvents.map { it.id } })
            .isEmpty()

        // Both generations were actually observed, so "no mixtures" is not passing on an empty sample.
        assertThat(gen1).isNotEmpty()
        assertThat(gen2).isNotEmpty()

        // And the reload really was what published generation 2 — the test is not merely observing the
        // state it started in.
        assertThat(seen.last().items.map { it.id }).isEqualTo(listOf("gen-2-item"))
        assertThat(hooked.publishedGeneration).isAtLeast(2L)

        // No emission ever carried an empty item list once the test had seeded one: that was the shape a
        // "read half the files, publish, then read the rest" implementation would have produced, and it is
        // the failure mode the checked read exists to prevent.
        assertThat(seen.map { it.items.map { i -> i.id } }).doesNotContain(emptyList<String>())
    }

    /**
     * A Life OS repository write refuses while a restore owns the gate, and the gate hands back cleanly.
     *
     * The complementary direction for the *other* half of the app. `LifeRepository` writes are suspending
     * and go through the lease-taking helpers, so they must be refused on `RESTORING` exactly as the
     * wardrobe's synchronous ones are.
     */
    @Test
    fun lifeOsWrite_isRefusedWhileRestoring_andSucceedsAfter() = runTest {
        val seeded = life.createEntity(entityType = "note")

        RestoreStartupGate.beginRestore()
        val refused = runCatching { life.createEntity(entityType = "note") }.exceptionOrNull()
        assertThat(refused).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // Nothing was written — but the *proof* must not go through the gated repository: `count()` takes
        // a business lease, so while `RESTORING` it throws `RestoreRecoveryPendingException` instead of
        // answering. A test that called it here would go red for the gate working correctly, and the only
        // way to "fix" it would be to weaken the gate the test above just asserted. So the read side of
        // this assertion uses the raw recovery-level DAO: it deliberately bypasses the business contract,
        // which is exactly what is wanted when the business path is supposed to be shut.
        assertThat(db.lifeEntityDao().countOnce()).isEqualTo(1)
        assertThat(db.lifeEntityDao().count()).isEqualTo(1)

        RestoreStartupGate.endRestoreReady()
        life.createEntity(entityType = "note")
        // ...and now the business path is back, so the business read is the right tool again.
        assertThat(life.count()).isEqualTo(2)
        assertThat(seeded.id).isNotEmpty()
    }

    /**
     * Capture + reference + media writes are all refused while restoring — the three repositories that
     * previously reached the database through raw `withTransaction`.
     *
     * Asserted together because they share the failure mode this round fixed: each opened its own
     * transaction with only a status check, so a restore could begin between the check and the commit.
     */
    @Test
    fun captureAndMediaWrites_areRefusedWhileRestoring() = runTest {
        RestoreStartupGate.beginRestore()

        assertThat(runCatching { capture.create(id = "refused-count", source = CaptureSource.CLIPBOARD) }
            .exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(runCatching { capture.count() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(runCatching { media.createMediaAsset(mediaType = MediaType.IMAGE) }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(runCatching { media.countAssets() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)

        RestoreStartupGate.endRestoreReady()
        assertThat(capture.count()).isEqualTo(0)
        assertThat(media.countAssets()).isEqualTo(0)
    }

    /**
     * The refused write leaves **no partial state** behind.
     *
     * A lease that refuses *after* the durable work began would be worse than no lease: the restore would
     * then roll over half-written data. So the assertion is not merely "it threw" but "the row is absent
     * and the gate is balanced afterwards".
     */
    @Test
    fun refusedCaptureWrite_leavesNoPartialRow() = runTest {
        val captureId = "refused-capture"
        RestoreStartupGate.beginRestore()

        assertThat(
            runCatching {
                capture.create(
                    id = captureId,
                    source = CaptureSource.GALLERY,
                    primaryMediaAssetId = null
                )
            }.exceptionOrNull()
        ).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // The DAO itself still has nothing, because the repository refused before reaching it.
        assertThat(db.captureDao().count()).isEqualTo(0)
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
    }

    /**
     * `withTransaction` on a Life OS repository takes the lease, so a transaction opened by business
     * code blocks `beginRestore` for its whole duration rather than only at its start.
     *
     * This is the specific hole the user called out (BLOCKER 5): a DAO getter that re-checks the gate
     * proves only that it was open at the instant of the getter, while the suspended write after it is
     * unprotected. A transaction is the clearest case, because it is durable by definition.
     */
    @Test
    fun repositoryTransaction_blocksBeginRestoreForItsWholeDuration() = runBlocking<Unit>(Dispatchers.IO) {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)

        val tx = async(Dispatchers.IO) {
            life.withTransaction {
                life.createEntity(entityType = "in-tx")
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        inside.await(5, TimeUnit.SECONDS)

        assertThat(RestoreStartupGate.beginRestore()).isFalse()

        release.countDown()
        tx.await()
        // The transaction's lease is fully released and the gate is startable again.
        assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
    }

    /**
     * An emission that was already in flight when the gate closed is dropped, not delivered.
     *
     * The race this pins is subtle and was introduced *by* the fix for the subscription churn:
     * `flatMapLatest` cancels the previous inner flow, but Room may deliver an already-computed emission
     * after `beginRestore` succeeded. Without `emitUnderBusinessLease` that emission reaches the UI as a
     * half-restored row. The test drives the collector and the gate from two real threads so the
     * interleaving is genuinely available, and asserts the delivered values never include the
     * restore-era row.
     */
    @Test
    fun inFlightEmission_racingBeginRestore_isNotDelivered() = runBlocking<Unit>(Dispatchers.IO) {
        val delivered = java.util.Collections.synchronizedList(mutableListOf<String>())
        val collectorStarted = CountDownLatch(1)

        val collector = launch(Dispatchers.IO) {
            gateAwareFlow { life.observeByType("race") }.collect { rows ->
                collectorStarted.countDown()
                rows.forEach { delivered.add(it.id) }
            }
        }
        // Ensure the durable subscription exists before mutating behind it.
        life.createEntity(entityType = "race")
        collectorStarted.await(5, TimeUnit.SECONDS)

        // Close the gate, then insert a restore-era row directly at the DAO level. If the in-flight
        // emission guard were missing, this invalidation could be delivered after `beginRestore`.
        assertThat(RestoreStartupGate.beginRestore()).isTrue()
        db.lifeEntityDao().insert(
            com.qq.closie.life.core.LifeEntityEntity(
                id = "race-era-row",
                entityType = "race",
                createdAt = 1L,
                updatedAt = 1L,
                deletedAt = null,
                revision = 1L
            )
        )
        delay(TRACKER_SETTLE_MS)

        collector.cancel()
        collector.join()

        assertThat(delivered).doesNotContain("race-era-row")

        // Positive control: once the gate reopens, the row *is* delivered — proving the assertion above
        // is about the race and not about a collector that simply never worked.
        RestoreStartupGate.endRestoreReady()
        val after = withTimeoutOrNull(GATE_PROBE_TIMEOUT_MS) { life.observeByType("race").first() }
        assertThat(after?.map { it.id }).contains("race-era-row")
    }

    private companion object {
        const val GATE_PROBE_TIMEOUT_MS = 5_000L
        const val TRACKER_SETTLE_MS = 300L
    }
}
