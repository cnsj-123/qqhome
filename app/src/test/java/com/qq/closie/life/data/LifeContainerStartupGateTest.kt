package com.qq.closie.life.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.qq.closie.data.backup.RestoreRecoveryPendingException
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Life OS half of the startup gate — the hole that let a half-restored data set be read.
 *
 * ### The bug this file pins
 *
 * Recovery is run from `ClosieApplication.onCreate`, and it is allowed to fail: it must not take the
 * process down. When it *does* fail, the gate stays blocked and
 * [com.qq.closie.data.repository.LocalWardrobeRepository] refuses to open — that half was already
 * covered. The Life OS side was not. [LifeContainer] held a `lifeDatabase` and handed out
 * `lifeRepository`, `mediaRepository`, `captureRepository`, `referenceRepository`, `planRepository`,
 * `mediaStoreImporter` and `referenceImporter` with **no gate at all**.
 *
 * So the protected path was the wardrobe and the unprotected path was everything in Life OS — which is
 * the whole point of the three-surface protocol, and also where the backup's rows live. A restore that
 * was killed inside its database transaction leaves the Closet reverted and the database holding the
 * backup's rows; the wardrobe repository would correctly refuse, and then the very next screen to touch
 * 记录 or 资料库 would read the backup's life-graph and render it as the user's.
 *
 * ### The two halves that must stay asymmetric
 *
 *  - **Business accessors must fail closed.** Each is gated *before* it constructs anything, because a
 *    repository that is built and then checked has already read the database in its own `init`.
 *  - **`lifeDatabase` must stay ungated.** Recovery itself runs while the gate is BLOCKED — that is what
 *    a barrier means — and it obtains the live database through that accessor to replay the snapshot.
 *    Gating it would deadlock recovery against itself. It is safe because a `LifeDatabase` handle reads
 *    nothing until a query runs, and the only query during recovery *is* the repair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifeContainerStartupGateTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase

    @Before
    fun setUp() {
        // Deliberately the gate's real initial state (BLOCKED), not a forced READY: the whole subject
        // of this file is what happens before recovery has resolved anything. Marking it ready here
        // would test nothing.
        RestoreStartupGate.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        RestoreStartupGate.resetForTesting()
    }

    private fun container(): LifeContainer = LifeContainer.createForTesting(context, db)

    // ------------------------------------------------------------------
    //  The raw database accessor is the one thing that must NOT be gated
    // ------------------------------------------------------------------

    /**
     * Recovery reads the database through this accessor while the gate is BLOCKED, so it must not throw.
     *
     * This is the deadlock guard. If `lifeDatabase` were gated, `RestoreRecoveryManager.recoverOnStartup`
     * — which runs *because* the gate is blocked — could not obtain the database it needs to complete the
     * very recovery that would unblock the gate. The app would be permanently unusable after any
     * interrupted restore, with no way out but clearing app data.
     */
    @Test
    fun lifeDatabase_isReachableWhileTheGateIsBlocked() = runTest {
        assertThat(RestoreStartupGate.isReady).isFalse()

        val container = container()

        // Must not throw…
        val database = container.lifeDatabase
        assertThat(database).isSameInstanceAs(db)

        // …and must be a *usable* handle, because recovery runs a real transaction through it. A handle
        // that merely exists but cannot query would deadlock just as thoroughly — so this is proven by
        // actually querying, not by inspecting the handle.
        //
        // Deliberately **not** asserted via `database.openHelper.databaseName`: this test builds an
        // in-memory database, and `Room.inMemoryDatabaseBuilder` leaves `databaseName` null by design.
        // Asserting it non-null was a false assertion that could only ever pass against a file-backed
        // database — so it said nothing about the in-memory case it was actually running. What matters is
        // that the handle works, and that is what is checked here: a read, and a write transaction of the
        // exact shape `LifeBackupApplier.restoreSnapshot` uses.
        assertThat(database.lifeEntityDao().countOnce()).isEqualTo(0)
        database.withTransaction {
            // Idempotent and harmless: it observes the empty database recovery would be repairing.
            assertThat(database.lifeEntityDao().getAllOnce()).isEmpty()
        }
    }

    // ------------------------------------------------------------------
    //  Every business accessor fails closed
    // ------------------------------------------------------------------

    /**
     * Each Life OS business accessor refuses while the gate is blocked.
     *
     * Asserted one by one rather than representative-ly, because the original defect was exactly a
     * *partial* application of the check: [LocalWardrobeRepository] was gated and these were not. A test
     * that sampled one accessor would have agreed with the buggy code.
     *
     * The accessor is wrapped in a lambda so the failure is observed at *call* time — these are `by lazy`,
     * so the gate runs on first access, which is when a screen would touch it.
     */
    @Test
    fun everyBusinessAccessor_refusesWhileTheGateIsBlocked() {
        val container = container()

        val accessors: List<Pair<String, () -> Any>> = listOf(
            "lifeRepository" to { container.lifeRepository },
            "mediaRepository" to { container.mediaRepository },
            "captureRepository" to { container.captureRepository },
            "referenceRepository" to { container.referenceRepository },
            "planRepository" to { container.planRepository },
            "mediaStoreImporter" to { container.mediaStoreImporter },
            "referenceImporter" to { container.referenceImporter }
        )

        accessors.forEach { (name, accessor) ->
            val failure = runCatching { accessor() }.exceptionOrNull()
            // The name is passed to `assertWithMessage` deliberately: every one of these accessors must
            // refuse, and a bare "expected instance of … but was null" would not say *which* one leaked.
            assertWithMessage(name).that(failure).isInstanceOf(RestoreRecoveryPendingException::class.java)
            assertWithMessage(name).that(failure).hasMessageThat().contains("恢复尚未完成")
        }
    }

    /**
     * An **already-constructed** repository must stop working the moment the gate closes.
     *
     * This is the case a construction-time check cannot cover, and it is the reason the gate is consulted
     * per call inside the repositories:
     *
     * ```
     *   app starts, recovery passes, gate = READY
     *   -> a ViewModel obtains lifeRepository and keeps it
     *   -> the user starts a restore from Settings; Phase A fails and the compensation fails too
     *   -> the gate closes — but the ViewModel's instance was built hours ago
     * ```
     *
     * If the gate were only checked in a constructor (or in `LifeContainer`'s `by lazy`), that instance
     * would keep reading and writing a half-restored database, and a later successful recovery would
     * replay the pre-restore snapshot over whatever it had written.
     *
     * The instance here is obtained *before* the gate closes, so the test can only pass if the check is
     * re-evaluated on use.
     */
    @Test
    fun alreadyHeldRepository_refusesOnceTheGateClosesAfterItWasConstructed() = runTest {
        val container = container()
        RestoreStartupGate.markReady()

        // Constructed while READY — exactly the ordinary, dangerous case.
        val repo = container.lifeRepository
        val created = repo.createEntity(entityType = "NOTE")
        assertThat(repo.getEntity(created.id)).isNotNull()

        // An in-process restore whose compensation could not finish. The gate closes *now*, long after
        // the instance above was built and verified working.
        RestoreStartupGate.markRestoreUnfinished(
            IllegalStateException("injected: 恢复补偿未完成")
        )
        assertThat(RestoreStartupGate.isReady).isFalse()

        // Reads refuse…
        val readFailure = runCatching { repo.getEntity(created.id) }.exceptionOrNull()
        assertThat(readFailure).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // …writes refuse…
        val writeFailure = runCatching { repo.createEntity(entityType = "NOTE") }.exceptionOrNull()
        assertThat(writeFailure).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // …and the transaction boundary refuses too, because a caller could otherwise use it to wrap
        // several DAO writes and bypass the per-call check entirely.
        val txFailure = runCatching { repo.withTransaction { } }.exceptionOrNull()
        assertThat(txFailure).isInstanceOf(RestoreRecoveryPendingException::class.java)

        // The business accessor on the container is still closed, and cannot be reopened in-process:
        // the only thing that may resolve this is a fresh process running the startup barrier.
        RestoreStartupGate.markReady()
        assertThat(RestoreStartupGate.isReady).isFalse()
        assertThat(RestoreStartupGate.isRestoreUnfinished).isTrue()
    }

    /**
     * `webMetadataReader` is deliberately *not* gated, and this is the positive control for the rule.
     *
     * It has no database, no filesystem and no in-memory state that can be inconsistent — it fetches a
     * page's title for link capture. Gating it would protect nothing and would add a way for the capture
     * flow to fail for a reason that does not apply to it. Asserting this keeps a future "gate everything
     * in the container" sweep from over-applying the rule.
     */
    @Test
    fun webMetadataReader_isNotGated_becauseItTouchesNoUserData() {
        val container = container()
        // Must not throw despite the blocked gate.
        assertThat(container.webMetadataReader).isNotNull()
    }

    // ------------------------------------------------------------------
    //  Once recovery resolves the gate, the accessors open
    // ------------------------------------------------------------------

    /**
     * The positive control: a READY gate means every accessor works.
     *
     * Without this, every test above could pass simply by the accessors always throwing, and the app
     * would be permanently unusable. It also proves the gate is a real *gate* and not a one-way latch.
     */
    @Test
    fun everyBusinessAccessor_opensOnceTheGateIsReady() {
        val container = container()
        RestoreStartupGate.markReady()

        assertThat(container.lifeRepository).isNotNull()
        assertThat(container.mediaRepository).isNotNull()
        assertThat(container.captureRepository).isNotNull()
        assertThat(container.referenceRepository).isNotNull()
        assertThat(container.planRepository).isNotNull()
        assertThat(container.mediaStoreImporter).isNotNull()
        assertThat(container.referenceImporter).isNotNull()
    }
}
