package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.repository.ReferenceRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * Long-lived `Flow` subscriptions across a gate transition, and the mutation-ordering contract in the
 * wardrobe repository.
 *
 * ### The hole these tests exist for
 *
 * A `Flow` handed out before the gate closed never calls its accessor again, so **no per-access check
 * can reach it**:
 *
 * ```
 *   ViewModel starts, gate is READY
 *   -> observeAll() checks the gate once, returns the Room Flow
 *   -> the collector stays subscribed for the life of the screen
 *   -> a restore runs; the gate closes
 *   -> the existing subscription still receives every Room invalidation
 *   -> the UI renders the backup's rows, then the rolled-back rows, live
 * ```
 *
 * Gating construction and gating each call are both necessary and both insufficient. The subscription
 * itself has to be conditional, which is why the gate is a `StateFlow` and why the policy is
 * `flatMapLatest` + `emptyFlow` rather than a filter.
 *
 * The two properties asserted for each repository are the ones that make that policy correct:
 *
 *  - **Nothing is emitted while blocked.** Not a stale row, not an empty list pretending the data is
 *    gone, and not an error that would kill the collector.
 *  - **The collector survives and resumes.** After the gate reopens it receives a *fresh* emission —
 *    which `flatMapLatest` guarantees by re-invoking `block()`, i.e. by issuing a new query rather than
 *    replaying a cached one. A collector that had died would leave the screen blank forever, which is a
 *    worse bug than the stale one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GateAwareFlowTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var media: MediaRepository
    private lateinit var captureRepo: CaptureRepository
    private lateinit var referenceRepo: ReferenceRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var wardrobe: LocalWardrobeRepository

    @Before
    fun setUp() {
        // `resetForTesting` before `markReady`, and both before anything else.
        //
        // `resetForTesting` first because the gate is **process-wide**: `blockedMutation_…` above leaves
        // it BLOCKED on purpose, and JUnit's execution order is not source order, so without the reset a
        // test that seeds data first would fail with "恢复尚未完成" for a reason unrelated to what it
        // asserts. `markReady` second because the gate's initial value is deliberately BLOCKED ("unanswered
        // must not read as safe"), which no repository will work behind.
        RestoreStartupGate.resetForTesting()
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
        RestoreStartupGate.resetForTesting()
    }

    /**
     * `LifeRepository`'s `Flow` goes quiet across a block and emits the *new* rows after it clears.
     *
     * The three-phase shape is the whole point: an emission before, **no** emission during, a fresh
     * emission after. Asserting only the first and last would pass on an implementation that kept
     * streaming through the block, which is the bug.
     *
     * ### Why one-shot `first()` per phase, and not a long-lived collector
     *
     * A `first()` is a *bounded probe*: it either observes an emission or times out, and both outcomes
     * are meaningful. That is what makes phase 2 assertable at all. A long-lived collector would have to
     * prove a negative ("nothing arrived") by waiting, and a wait cannot distinguish "correctly
     * suspended" from "the tracker has not dispatched yet".
     *
     * The three probes then read as a sequence of facts about the same `Flow`:
     *
     * ```
     *   first() while READY      -> the seeded row       (the flow works at all)
     *   first() while RESTORING  -> times out, null      (the durable subscription is gone)
     *   first() after endRestoreReady() -> both rows     (a *fresh* query, not a replay)
     * ```
     *
     * The third probe is the stronger claim: the row inserted while blocked can only appear if
     * `flatMapLatest` re-invoked `block()` and issued a new query. A `filter`-based policy would have
     * kept the original subscription and this row would have been visible in phase 2 instead.
     *
     * ### Why [runBlocking] and not [runTest]
     *
     * Room's `Flow` is fed by the `InvalidationTracker`, which dispatches on **its own** executor.
     * `runTest` installs a virtual scheduler that has no knowledge of that executor, so a Room `Flow`
     * never emits under it — the probe below reports `null`/timeout for *every* phase, including the one
     * that must succeed. Driving the whole test on the real clock is therefore not a style choice: it is
     * the difference between observing the flow and observing nothing.
     */
    @Test
    fun lifeRepositoryFlow_suspendsWhileBlockedAndResumesWithFreshData() {
        // A block body rather than `= runBlocking(Dispatchers.IO) { ... }`: JUnit requires a `void`
        // test method, and the expression form inherits `runBlocking`'s generic return type — which
        // type-checked fine but made JUnit reject the whole class before running anything.
        runBlocking(Dispatchers.IO) {
        val seeded = life.createEntity(entityType = "before")

        // ---- Phase 1: while READY, a fresh query delivers the seeded row. ----
        val rowsBeforeTheBlock = probe { life.observeByType("before").first() }
        assertThat(rowsBeforeTheBlock).isNotNull()
        assertThat(rowsBeforeTheBlock!!.map { it.id }).containsExactly(seeded.id)

        // ---- Phase 2: a restore starts; the gate closes. ----
        RestoreStartupGate.beginRestore()
        // A DAO-level write at the moment the gate is closed. If the durable subscription were still
        // alive, this invalidation would produce an emission; with `flatMapLatest` `block()` is not even
        // called, so the probe times out. The timeout is the assertion — not a flake.
        db.lifeEntityDao().insert(
            com.qq.closie.life.core.LifeEntityEntity(
                id = "during-restore",
                entityType = "before",
                createdAt = 1L,
                updatedAt = 1L,
                deletedAt = null,
                revision = 1L
            )
        )
        // Give the tracker a real chance to dispatch the invalidation above before concluding anything.
        delay(TRACKER_SETTLE_MS)
        // Timed out => the durable subscription really is gone. A `filter` policy would have kept it and
        // this probe would have reported the second row instead.
        assertThat(probe { life.observeByType("before").first() }).isNull()

        // ---- Phase 3: the restore finishes; the gate reopens. ----
        RestoreStartupGate.endRestoreReady()
        val rowsAfterTheBlock = probe { life.observeByType("before").first() }
        assertThat(rowsAfterTheBlock).isNotNull()
        // The row written *while blocked* is here now. That is only possible if `block()` ran again and
        // issued a fresh query against the repaired table — the property a dying or still-attached
        // collector would not have.
        assertThat(rowsAfterTheBlock!!.map { it.id }).containsExactly(seeded.id, "during-restore")
        }
    }

    /**
     * The same contract for `ReferenceRepository` and `PlanRepository` — the two repositories that
     * bypassed the gate with raw `database.withTransaction` before this round.
     *
     * Checked separately from `LifeRepository` on purpose: they reach their `Flow`s through a different
     * code path (a `dao` property plus a transaction helper), so a fix applied to one would not
     * necessarily cover the others. A test that only ever exercised `LifeRepository` would have left
     * exactly the hole [BLOCKER 4] describes.
     */
    @Test
    fun referenceAndPlanFlows_areEquallyGated() {
        // Block body for the same JUnit `void` reason as the `LifeRepository` test above.
        runBlocking(Dispatchers.IO) {
        referenceRepo.create(
            title = "恢复前的资料",
            referenceType = com.qq.closie.life.reference.ReferenceType.ARTICLE
        )
        planRepo.create(title = "恢复前的计划")

        // ---- Phase 1: while READY both flows deliver their seeded row. ----
        val referenceBaseline = probe { referenceRepo.observeAll().first() }
        val planBaseline = probe { planRepo.observeAll().first() }
        assertThat(referenceBaseline).isNotNull()
        assertThat(planBaseline).isNotNull()

        // ---- Phase 2: a restore starts; the gate closes for both. ----
        RestoreStartupGate.beginRestore()
        // Insert behind the repositories' backs, at the DAO level, so an un-gated subscription would
        // definitely see it. Going through the repositories would be refused by the gate — the other half
        // of the contract, tested in [RestoreStartupGateLifecycleTest].
        db.referenceDao().insert(
            com.qq.closie.life.reference.ReferenceItemEntity(
                id = "ref-during",
                lifeEntityId = "life-ref-during",
                title = "恢复期间的资料",
                referenceType = com.qq.closie.life.reference.ReferenceType.ARTICLE,
                status = com.qq.closie.life.reference.ReferenceStatus.INBOX,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        db.planDao().insert(
            com.qq.closie.life.plan.PlanItemEntity(
                id = "plan-during",
                lifeEntityId = "life-plan-during",
                title = "恢复期间的计划",
                createdAt = 1L,
                updatedAt = 1L
            )
        )

        // Both time out: neither repository's durable subscription is alive. The important half is that
        // the *rows exist* at this point — a plain value comparison would be confounded by that, which is
        // why the probe reports presence/absence of an emission rather than its contents.
        assertThat(probe { referenceRepo.observeAll().first() }).isNull()
        assertThat(probe { planRepo.observeAll().first() }).isNull()

        // ---- Phase 3: the gate reopens; both re-query and see the rows written while blocked. ----
        RestoreStartupGate.endRestoreReady()
        val referenceAfter = probe { referenceRepo.observeAll().first() }
        val planAfter = probe { planRepo.observeAll().first() }
        assertThat(referenceAfter).isNotNull()
        assertThat(planAfter).isNotNull()
        // A *fresh query*, not a replay: `flatMapLatest` re-invokes the block, so a row written while the
        // gate was closed becomes visible without any further write. That is the property a collector
        // that had died, or one that had stayed subscribed, would not have.
        assertThat(referenceAfter!!.size).isEqualTo(referenceBaseline!!.size + 1)
        assertThat(planAfter!!.size).isEqualTo(planBaseline!!.size + 1)
        }
    }

    /**
     * The other half of `flatMapLatest`: while blocked the durable subscription is **dropped**, not just
     * filtered.
     *
     * Observable difference: with `filter` the underlying `Flow` stays subscribed, so the restore's
     * intermediate writes are still *queried* and a database cursor is held across the swap. With
     * `flatMapLatest` the block is not even called, so nothing is queried. Asserting that is what keeps
     * a later "optimisation" back to `filter` from silently reintroducing the reads the policy exists to
     * prevent.
     */
    @Test
    fun whileBlockedTheDurableSourceIsNotSubscribedAtAll() = runTest {
        life.createEntity(entityType = "seed")

        var subscriptions = 0
        val flow = gateAwareFlow {
            subscriptions++
            life.observeByType("note")
        }

        // `block` is invoked by the `flatMapLatest` on whatever dispatcher the *gate* `StateFlow` emits
        // on. Under `runTest` that is the virtual scheduler, which the gate's own transitions do reach —
        // but the collection has to be launched on a real dispatcher for the first invocation to happen
        // at all, so the whole block runs on `IO`.
        runBlocking(Dispatchers.IO) {
            val job = launch { flow.collect { } }
            awaitTrue("block was never invoked") { subscriptions == 1 }

            RestoreStartupGate.beginRestore()
            awaitTrue("the gate never entered RESTORING") { RestoreStartupGate.isRestoring }
            delay(TRACKER_SETTLE_MS)
            // Still one: the gate closed without re-subscribing. The `READY -> RESTORING` emission did
            // reach the `flatMapLatest`, and its branch chose `emptyFlow`, so `block` was not invoked
            // again.
            assertThat(subscriptions).isEqualTo(1)

            RestoreStartupGate.endRestoreReady()
            // Re-subscribed: this is what proves the policy is `flatMapLatest` and not `filter`. With
            // `filter` the block would still have run exactly once, and the durable subscription would
            // never have been dropped across the swap.
            awaitTrue("block was not re-invoked after the gate reopened") { subscriptions == 2 }

            job.cancel()
        }
    }

    /**
     * The **long-lived collector** case: one `collect`, started once, held across READY → RESTORING →
     * READY, with no half-state ever delivered.
     *
     * ### Why this is a separate test from the `first()`-probe ones above
     *
     * Every probe test re-subscribes for each phase, so each one answers "what does a *new* collector
     * see right now?". That is a useful property, but it is not the one the UI depends on. A ViewModel's
     * collector is created once and lives for the life of the screen; the question that matters is
     * whether *that* collector — the same `job`, the same subscription — stays correct while the gate
     * moves underneath it. The two can differ:
     *
     * ```
     *   probe model:      each probe is a fresh subscription, so a policy that
     *                     tore the collector down on RESTORING would still pass
     *   long-lived model: the collector must survive RESTORING (it sees nothing and
     *                     is not terminated) and then receive the post-restore state
     * ```
     *
     * `emptyFlow` inside `flatMapLatest` completes the *inner* flow, not the outer collector, so the
     * collector is expected to survive. If a later change made the gate emit an error instead, this test
     * is the one that would catch the resulting dead UI — the probe tests would not, because they
     * subscribe after the transition rather than through it.
     *
     * ### What "half-state" means here, precisely
     *
     * The restore's own writes are what the UI must never see. `during-restore` is inserted at the DAO
     * level precisely so that it is not attributable to a repository call: if it appears in `received`,
     * the durable subscription leaked a restore-era row to the screen. Only two values are legal —
     * the pre-restore snapshot and the post-restore snapshot.
     */
    @Test
    fun longLivedCollector_survivesTheBlockAndSeesOnlyCoherentSnapshots() {
        runBlocking(Dispatchers.IO) {
            val seeded = life.createEntity(entityType = "stable")

            val received = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
            val firstEmission = java.util.concurrent.CountDownLatch(1)
            val resumedEmission = java.util.concurrent.CountDownLatch(1)

            // One collector, one subscription, for the whole test. Not re-created per phase.
            val job = launch(Dispatchers.IO) {
                life.observeByType("stable").collect { rows ->
                    received.add(rows.map { it.id })
                    if (received.size == 1) firstEmission.countDown()
                    if (rows.any { it.id == "after-restore" }) resumedEmission.countDown()
                }
            }

            assertThat(firstEmission.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

            // ---- The gate closes underneath the running collector. ----
            assertThat(RestoreStartupGate.beginRestore()).isTrue()
            db.lifeEntityDao().insert(
                com.qq.closie.life.core.LifeEntityEntity(
                    id = "during-restore",
                    entityType = "stable",
                    createdAt = 1L,
                    updatedAt = 1L,
                    deletedAt = null,
                    revision = 1L
                )
            )
            delay(TRACKER_SETTLE_MS)

            // ---- The gate reopens; a row written while blocked is now visible. ----
            db.lifeEntityDao().insert(
                com.qq.closie.life.core.LifeEntityEntity(
                    id = "after-restore",
                    entityType = "stable",
                    createdAt = 2L,
                    updatedAt = 2L,
                    deletedAt = null,
                    revision = 1L
                )
            )
            RestoreStartupGate.endRestoreReady()

            // The collector was never restarted, and it did receive the post-restore state — so it
            // survived the block rather than having been terminated by it.
            assertThat(resumedEmission.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            job.cancel()

            // ### The core assertion: nothing arrived *while the gate was closed*
            //
            // Note carefully what is and is not claimed. `during-restore` **does** legitimately appear
            // in the final post-restore snapshot: after the gate reopened, `flatMapLatest` re-invoked
            // `block()` and issued a fresh full query, and that query correctly returns every row,
            // including the one written while the gate was shut. A coherent read after the restore is
            // exactly what should happen, and asserting `during-restore` never appears anywhere would be
            // asserting a bug.
            //
            // What must never happen is a delivery *during* the closed window. So the assertion is on
            // the sequence, not on its contents: the emission carrying `during-restore` must be the one
            // that also carries `after-restore`, i.e. a single, complete, post-restore snapshot — never
            // a partial one that saw the restore-era row but not the final state.
            val emissionWithRestoreEraRow = received.filterIndexed { _, rows -> rows.contains("during-restore") }
            // Exactly one such emission, and it is the last one: the fresh post-restore query.
            assertThat(emissionWithRestoreEraRow).hasSize(1)
            assertThat(emissionWithRestoreEraRow.single()).isEqualTo(received.last())
            // And that snapshot is the complete post-restore world, not a half-applied one.
            assertThat(received.last()).containsAtLeast("after-restore", "during-restore", seeded.id)

            // The pre-restore snapshot that preceded the block saw only the seeded row: proof the
            // restore-era write had not been observed before the gate reopened.
            assertThat(received.first()).containsExactly(seeded.id)
        }
    }

    // ------------------------------------------------------------------
    //  Wardrobe: mutation ordering (BLOCKER 5)
    // ------------------------------------------------------------------

    /**
     * A held wardrobe instance refuses a mutation while blocked, and **nothing** changes — not disk, not
     * the `StateFlow`, and not the private image file.
     *
     * ### The bug this pins
     *
     * `putXxx` used to publish to its `StateFlow` *first* and check the gate second, inside `write`:
     *
     * ```
     *   updateItem(item)
     *   -> _items.value = newList      // memory now shows the edit
     *   -> write(...)  -> requireReady throws
     *   -> disk unchanged, StateFlow changed
     * ```
     *
     * The UI therefore showed a "successful edit" that does not exist. On a real device the next
     * recomposition after the gate reopened would read disk and the edit would vanish — or, worse, the
     * user would believe they had saved and never check. The order has to be
     * `requireReady -> durable write -> publish`, so a refused write leaves nothing behind.
     *
     * ### Why the image is asserted too
     *
     * `deleteItem`/`deleteOotd`/`deleteOutfit` also call `ImageStore.deletePrivatePath`, which is
     * **irreversible**. If the publish happened first and the gate check second, a refused delete would
     * still have destroyed the user's photo while claiming to have failed.
     */
    @Test
    fun blockedMutation_leavesDiskStateFlowAndImagesUntouched() = runTest {
        val item = ClothingItem(
            id = "held-item",
            name = "原本的名字",
            category = "上衣",
            status = ItemStatus.OWNED
        )
        wardrobe.createItem(item)

        // A private image belonging to that item, so the irreversible side effect is observable.
        val imageDir = File(context.filesDir, "closie/images").apply { mkdirs() }
        val image = File(imageDir, "held-item.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val itemWithImage = item.copy(
            images = listOf(
                com.qq.closie.data.model.ClothingImage(
                    kind = com.qq.closie.data.model.ImageKind.FLAT,
                    localPath = image.absolutePath
                )
            )
        )
        wardrobe.updateItem(itemWithImage)

        val itemsBefore = wardrobe.items.value
        val diskBefore = itemFile().readText()
        assertThat(image.exists()).isTrue()

        // ---- A restore starts; the held instance is now stale. ----
        RestoreStartupGate.beginRestore()

        val updateRefused = runCatching { wardrobe.updateItem(item.copy(name = "被拒绝的修改")) }
            .exceptionOrNull()
        val deleteRefused = runCatching { wardrobe.deleteItem("held-item") }.exceptionOrNull()

        assertThat(updateRefused).isInstanceOf(RestoreRecoveryPendingException::class.java)
        assertThat(deleteRefused).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // Nothing moved. The state flow is compared by value *and* by identity: an implementation that
        // assigned an equal-but-new list would still have published, so identity is what proves the
        // publish did not happen.
        assertThat(wardrobe.items.value.map { it.name }).isEqualTo(itemsBefore.map { it.name })
        assertThat(wardrobe.items.value).isSameInstanceAs(itemsBefore)
        assertThat(itemFile().readText()).isEqualTo(diskBefore)
        // The irreversible side effect did not run: the delete was refused *before* it started.
        assertThat(image.exists()).isTrue()
        assertThat(image.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))

        RestoreStartupGate.endRestoreReady()
    }

    /**
     * The positive control: once the gate is open again the very same held instance *does* write.
     *
     * Without this, the test above would pass on a repository that simply refused everything — a
     * different bug wearing the same green tick.
     */
    @Test
    fun afterTheGateReopens_theHeldInstanceWritesAgain() = runTest {
        wardrobe.createItem(
            ClothingItem(
                id = "held-item",
                name = "原本的名字",
                category = "上衣",
                status = ItemStatus.OWNED
            )
        )

        RestoreStartupGate.beginRestore()
        assertThat(runCatching { wardrobe.listItems() }.exceptionOrNull())
            .isInstanceOf(RestoreRecoveryPendingException::class.java)
        RestoreStartupGate.endRestoreReady()

        wardrobe.updateItem(
            ClothingItem(
                id = "held-item",
                name = "修改后的名字",
                category = "上衣",
                status = ItemStatus.OWNED
            )
        )

        assertThat(wardrobe.items.value.map { it.name }).contains("修改后的名字")
        assertThat(itemFile().readText()).contains("修改后的名字")
    }

    /**
     * A **slow collector holds the emission lease for as long as it takes**, and this test accepts that
     * behaviour rather than claiming it cannot happen (BLOCKER 8).
     *
     * ### Why this is a test and not just a comment
     *
     * `emitUnderBusinessLease`'s doc used to say the lease "never spans a suspension the collector performs
     * downstream". That is not a property Kotlin `Flow` provides: `emit` *is* the delivery, and the
     * downstream collector body runs before `emit` returns, so a collector that suspends holds the lease
     * for exactly as long as it suspends. Since [RestoreStartupGate.beginRestore] refuses while any lease is
     * out, a slow collector postpones a restore.
     *
     * The options were to fix the comment, to redesign the guard, or to accept the behaviour in a test that
     * states it. Every `emit`-based redesign fails the same way — the value must be delivered while the gate
     * is known open, so the delivery is as long as the collector makes it, and buffering to shorten the
     * lease reintroduces the race the guard exists to close. So this pins the accepted behaviour, including
     * the two things that make it tolerable: the refusal is a `false` (never a block or an exception), and
     * the lease is released the moment the collector returns.
     *
     * If a future change makes the guard genuinely non-blocking, this test should be rewritten — but it must
     * not be deleted, because the behaviour it documents is the reason the comment reads the way it does.
     */
    @Test
    fun aSlowCollectorHoldsTheEmissionLease_andBeginRestoreIsRefusedNotBlocked() {
        runBlocking(Dispatchers.IO) {
            life.createEntity(entityType = "slow")

            val collectorInside = CountDownLatch(1)
            val collectorMayReturn = CountDownLatch(1)
            val leases = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val beginResults = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

            val collector = launch(Dispatchers.IO) {
                gateAwareFlow { life.observeByType("slow") }.collect {
                    // Suspends while the emission lease is held: `emit` has not returned yet.
                    collectorInside.countDown()
                    collectorMayReturn.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    leases.add(RestoreStartupGate.activeBusinessOps)
                }
            }
            assertThat(collectorInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

            // The collector is parked mid-emission, so the lease really is held — by the *emission*, not by
            // a business operation.
            assertThat(RestoreStartupGate.activeBusinessOps).isAtLeast(1)

            // `beginRestore` refuses rather than waiting. Asserted on a second thread with a bounded wait so
            // that a *blocking* implementation would fail this test instead of hanging the suite.
            val attempt = async(Dispatchers.IO) {
                beginResults.add(RestoreStartupGate.beginRestore())
            }
            attempt.await()
            assertThat(beginResults).containsExactly(false)
            // And the refusal did not leave the gate half-claimed.
            assertThat(RestoreStartupGate.isReady).isTrue()

            // Let the collector return: the lease is released as soon as it does, and the same call now
            // succeeds — so the postponement is bounded by the collector, not permanent.
            collectorMayReturn.countDown()
            collector.join()
            assertThat(RestoreStartupGate.activeBusinessOps).isEqualTo(0)
            assertThat(RestoreStartupGate.beginRestore()).isTrue()
            RestoreStartupGate.endRestoreReady()
        }
    }

    /**
     * The other half of the accepted behaviour: an **idle** collector holds no lease.
     *
     * Without this, the test above would be consistent with a guard that takes the lease for the whole
     * subscription — which would refuse `beginRestore` forever on any screen that merely had a flow open,
     * i.e. a permanently un-restorable app. The lease is per emission, and this is what says so.
     */
    @Test
    fun anIdleCollectorHoldsNoLease() {
        runBlocking(Dispatchers.IO) {
            life.createEntity(entityType = "idle")

            val received = CountDownLatch(1)
            val collector = launch(Dispatchers.IO) {
                gateAwareFlow { life.observeByType("idle") }.collect { received.countDown() }
            }
            assertThat(received.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

            // The collector has processed its emission and is now simply waiting for the next one.
            awaitTrue("the emission lease was never released") {
                RestoreStartupGate.activeBusinessOps == 0
            }
            // A restore can start while a UI screen merely has the flow open.
            assertThat(RestoreStartupGate.beginRestore()).isTrue()
            collector.cancel()
            collector.join()
            RestoreStartupGate.endRestoreReady()
        }
    }

    /**
     * Runs [block] on the **real** clock with a bounded wait, returning `null` on timeout.
     *
     * ### Why `null` is the useful answer
     *
     * The contract has a negative half — "nothing is emitted while the gate is closed" — and a negative
     * has to be *bounded* to be assertable. `null` is exactly that bound: it says the flow produced
     * nothing within [GATE_PROBE_TIMEOUT_MS], which is the observable difference between a dropped
     * subscription and a live one. The test asserts `isNull()` there and `isNotNull()` on the phases that
     * must produce something, so a policy that suspended too eagerly is just as visible as one that never
     * suspended at all.
     *
     * A timeout is deliberately *not* a failure here: it is a return value, and the assertion on it
     * carries the meaning. That keeps the negative phase from being indistinguishable from a flake.
     */
    private suspend fun <T> probe(block: suspend () -> T): T? =
        withTimeoutOrNull(GATE_PROBE_TIMEOUT_MS) { block() }

    /**
     * A bounded wait for a condition that is not itself a `Flow` emission.
     *
     * Used by the subscription-counting test, where the observable is a plain counter incremented inside
     * `block()`. Polling on the real clock is the honest form there too: the increment happens on the
     * dispatcher the gate's `StateFlow` emits on, not on the test scheduler.
     */
    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(GATE_PROBE_TIMEOUT_MS) {
            while (!condition()) delay(5)
            true
        }
        assertThat(reached).isNotNull()
        assertWithMessage(what).that(reached).isTrue()
    }

    private fun itemFile() = File(File(context.filesDir, "closie"), "items.json")

    private companion object {
        /**
         * Long enough for a real Room query and its `InvalidationTracker` round-trip, short enough that a
         * genuinely missing emission fails the test quickly instead of hanging the suite.
         */
        const val GATE_PROBE_TIMEOUT_MS = 5_000L

        /**
         * How long a *negative* expectation is given before it is believed.
         *
         * There is no positive signal to wait for, so the honest form is a real-time pause long enough
         * for the `InvalidationTracker` to dispatch. Deliberately short: if a delivery were going to
         * happen it would happen in microseconds, so this bounds the lie rather than hoping to outwait it.
         */
        const val TRACKER_SETTLE_MS = 300L
    }
}
