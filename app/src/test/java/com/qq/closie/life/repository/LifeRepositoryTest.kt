package com.qq.closie.life.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.12.2 tops out at API 34, while the app targets 35. Without an explicit sdk the
// runner reads targetSdkVersion from the merged manifest and aborts with
// "Package targetSdkVersion=35 > maxSdkVersion=34". Pinning 34 keeps the JVM suite runnable
// without bumping the Robolectric version; nothing under test depends on API 35 behaviour.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifeRepositoryTest {

    private lateinit var db: LifeDatabase
    private lateinit var repo: LifeRepository

    @Before
    fun setUp() {
        // This file asserts repository semantics, not the restore barrier, so it resolves the gate as a
        // precondition. The gate starts BLOCKED by design and every repository re-checks it per call, so
        // without this each test would fail with "恢复尚未完成" for a reason unrelated to what it asserts.
        // `resetForTesting` first: the gate is process-wide, and a barrier test may have closed it for
        // the rest of this JVM.
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LifeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        RestoreStartupGate.resetForTesting()
    }

    // ------------------------------------------------------------------
    //  Entity
    // ------------------------------------------------------------------

    @Test
    fun createEntity_startsAtRevisionOneAndIsQueryable() = runTest {
        val created = repo.createEntity("wardrobe_item", timestamp = 1_000L)

        assertThat(created.revision).isEqualTo(1L)
        assertThat(created.deletedAt).isNull()

        val loaded = repo.getEntity(created.id)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.entityType).isEqualTo("wardrobe_item")
        assertThat(loaded.revision).isEqualTo(1L)
    }

    @Test
    fun updateEntity_incrementsRevision() = runTest {
        val created = repo.createEntity("journal", timestamp = 1_000L)

        val updated = repo.updateEntity(created, timestamp = 2_000L)
        assertThat(updated.revision).isEqualTo(2L)
        assertThat(updated.updatedAt).isEqualTo(2_000L)

        assertThat(repo.getEntity(created.id)!!.revision).isEqualTo(2L)

        repo.updateEntity(repo.getEntity(created.id)!!, timestamp = 3_000L)
        assertThat(repo.getEntity(created.id)!!.revision).isEqualTo(3L)
    }

    @Test
    fun softDelete_marksDeletedAndBumpsRevision() = runTest {
        val created = repo.createEntity("plant", timestamp = 1_000L)
        repo.updateEntity(created, timestamp = 2_000L) // revision 2

        repo.softDeleteEntity(created.id, timestamp = 3_000L)

        val deleted = repo.getEntity(created.id)!!
        assertThat(deleted.deletedAt).isEqualTo(3_000L)
        assertThat(deleted.revision).isEqualTo(3L)
    }

    @Test
    fun restore_clearsDeletedAtAndBumpsRevision() = runTest {
        val created = repo.createEntity("plant", timestamp = 1_000L)
        repo.softDeleteEntity(created.id, timestamp = 2_000L)

        repo.restoreEntity(created.id, timestamp = 3_000L)

        val restored = repo.getEntity(created.id)!!
        assertThat(restored.deletedAt).isNull()
        assertThat(restored.revision).isEqualTo(3L)
    }

    @Test
    fun observeByType_excludesSoftDeleted() = runTest {
        val a = repo.createEntity("trip", timestamp = 1_000L)
        val b = repo.createEntity("trip", timestamp = 2_000L)
        repo.createEntity("journal", timestamp = 3_000L)
        repo.softDeleteEntity(b.id, timestamp = 4_000L)

        // `first()` takes the initial emission. Room re-emits on every write, so a single
        // emission is enough to assert soft-deleted rows are excluded.
        val trips = repo.observeByType("trip").first()

        assertThat(trips.map { it.id }).containsExactly(a.id)
    }

    // ------------------------------------------------------------------
    //  Relations
    // ------------------------------------------------------------------

    @Test
    fun addRelation_isQueryableBothDirections() = runTest {
        val from = repo.createEntity("outfit", timestamp = 1_000L)
        val to = repo.createEntity("wardrobe_item", timestamp = 2_000L)

        val relation = repo.addRelation(from.id, to.id, "CONTAINS", timestamp = 3_000L)

        assertThat(repo.relationExists(from.id, to.id, "CONTAINS")).isTrue()
        assertThat(repo.getOutgoing(from.id).map { it.id }).containsExactly(relation.id)
        assertThat(repo.getIncoming(to.id).map { it.id }).containsExactly(relation.id)
        assertThat(repo.getOutgoingByType(from.id, "CONTAINS")).hasSize(1)
        assertThat(repo.getIncomingByType(to.id, "CONTAINS")).hasSize(1)
        assertThat(repo.getOutgoingByType(from.id, "WORN_WITH")).isEmpty()
    }

    @Test
    fun addRelation_duplicateIsRejected() = runTest {
        val from = repo.createEntity("outfit", timestamp = 1_000L)
        val to = repo.createEntity("wardrobe_item", timestamp = 2_000L)
        repo.addRelation(from.id, to.id, "CONTAINS", timestamp = 3_000L)

        val error = runCatching {
            repo.addRelation(from.id, to.id, "CONTAINS", timestamp = 4_000L)
        }.exceptionOrNull()

        assertThat(error).isNotNull()
        assertThat(repo.getOutgoing(from.id)).hasSize(1)
    }

    @Test
    fun removeRelation_byEndpointsDeletesOnlyThatEdge() = runTest {
        val from = repo.createEntity("outfit", timestamp = 1_000L)
        val to1 = repo.createEntity("wardrobe_item", timestamp = 2_000L)
        val to2 = repo.createEntity("wardrobe_item", timestamp = 3_000L)
        repo.addRelation(from.id, to1.id, "CONTAINS", timestamp = 4_000L)
        repo.addRelation(from.id, to2.id, "CONTAINS", timestamp = 5_000L)

        repo.removeRelation(from.id, to1.id, "CONTAINS")

        assertThat(repo.getOutgoing(from.id)).hasSize(1)
        assertThat(repo.relationExists(from.id, to1.id, "CONTAINS")).isFalse()
        assertThat(repo.relationExists(from.id, to2.id, "CONTAINS")).isTrue()
    }

    // ------------------------------------------------------------------
    //  Tags
    // ------------------------------------------------------------------

    @Test
    fun createTag_normalizesNameAndIsUnique() = runTest {
        val first = repo.createTag("  Summer  ", timestamp = 1_000L)
        val second = repo.createTag("summer", timestamp = 2_000L)

        assertThat(first.normalizedName).isEqualTo("summer")
        assertThat(first.name).isEqualTo("Summer")
        assertThat(second.id).isEqualTo(first.id)
    }

    @Test
    fun attachAndDetachTag_updatesEntityTags() = runTest {
        val entity = repo.createEntity("wardrobe_item", timestamp = 1_000L)
        val tag = repo.createTag("Linen", timestamp = 2_000L)

        repo.attachTag(entity.id, tag.id)
        assertThat(repo.tagAttached(entity.id, tag.id)).isTrue()
        assertThat(repo.getTagsForEntity(entity.id).map { it.id }).containsExactly(tag.id)

        repo.detachTag(entity.id, tag.id)
        assertThat(repo.tagAttached(entity.id, tag.id)).isFalse()
        assertThat(repo.getTagsForEntity(entity.id)).isEmpty()
    }

    @Test
    fun attachTagByName_createsTagWhenMissing() = runTest {
        val entity = repo.createEntity("wardrobe_item", timestamp = 1_000L)

        repo.attachTagByName(entity.id, "Vintage")

        val tags = repo.getTagsForEntity(entity.id)
        assertThat(tags).hasSize(1)
        assertThat(tags.first().normalizedName).isEqualTo("vintage")
    }
}
