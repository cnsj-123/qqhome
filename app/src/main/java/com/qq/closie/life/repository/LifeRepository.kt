package com.qq.closie.life.repository

import androidx.room.withTransaction
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.dao.LifeEntityDao
import com.qq.closie.life.data.database.dao.LifeRelationDao
import com.qq.closie.life.data.database.dao.TagDao
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import com.qq.closie.data.backup.gateAwareFlow

/**
 * Repository for the Life entity graph: entities, relations, and tags.
 *
 * UI must never touch DAOs directly — this is the only gateway.
 *
 * revision semantics:
 *  - create  → revision = 1
 *  - meaningful update → revision + 1
 *  - soft delete → revision + 1
 *  - restore → revision + 1
 */
class LifeRepository(
    private val database: LifeDatabase,
) {
    /**
     * The DAO accessors are now **plain** private accessors: they no longer consult the gate themselves.
     *
     * ### Why the gate moved out of here
     *
     * A getter-level `gated { database.lifeEntityDao() }` answered "is the gate open *right now*?" and
     * delegated — which protects nothing that follows it. The dangerous window is not the getter, it is
     * the durable work *after* it:
     *
     * ```
     *   createEntity():
     *     entityDao            -> getter checks the gate: READY, fine
     *     entityDao.insert(e)  -> suspends on disk
     *                          ^ beginRestore() + snapshot + archive apply can all happen here
     *                          -> the row lands inside a restore that has already replayed its snapshot
     * ```
     *
     * The `requireReady` inside the getter runs once, before the suspension, and there is no second check
     * to catch a gate that closed while the insert was in flight. So the check is replaced by a **lease**
     * on the whole operation: every public method runs inside [RestoreStartupGate.withBusinessAccessSuspending],
     * and while a lease is out [RestoreStartupGate.beginRestore] cannot take ownership. That is a real
     * barrier in both directions, which a per-getter check can never be.
     *
     * Keeping the accessors ungated is what makes that possible: with the gate consulted here as well, an
     * inner call reached from inside an already-held lease would *re-check* the (still `READY`) gate and
     * pass, while the same call made directly — with no lease — would also pass, which is exactly the case
     * we must stop. One gate, at the operation boundary.
     */
    private val entityDao: LifeEntityDao get() = database.lifeEntityDao()
    private val relationDao: LifeRelationDao get() = database.lifeRelationDao()
    private val tagDao: TagDao get() = database.tagDao()

    // ------------------------------------------------------------------
    //  Entity CRUD
    // ------------------------------------------------------------------

    suspend fun createEntity(
        entityType: String,
        timestamp: Long = System.currentTimeMillis(),
    ): LifeEntityEntity = RestoreStartupGate.withBusinessAccessSuspending {
        val entity = LifeEntityEntity(
            id = UUID.randomUUID().toString(),
            entityType = entityType,
            createdAt = timestamp,
            updatedAt = timestamp,
            deletedAt = null,
            revision = 1L,
        )
        entityDao.insert(entity)
        entity
    }

    suspend fun getEntity(id: String): LifeEntityEntity? =
        RestoreStartupGate.withBusinessAccessSuspending { entityDao.getById(id) }

    fun observeByType(type: String): Flow<List<LifeEntityEntity>> = gateAwareFlow { entityDao.observeByType(type) }

    fun observeRecent(limit: Int = 20): Flow<List<LifeEntityEntity>> = gateAwareFlow { entityDao.observeRecent(limit) }

    /**
     * Meaningful update: bumps revision and updatedAt. The caller passes the updated entity
     * (with new field values already set); this helper only manages the audit columns.
     */
    suspend fun updateEntity(entity: LifeEntityEntity, timestamp: Long = System.currentTimeMillis()): LifeEntityEntity =
        RestoreStartupGate.withBusinessAccessSuspending {
            val updated = entity.copy(
                updatedAt = timestamp,
                revision = entity.revision + 1L,
            )
            entityDao.update(updated)
            updated
        }

    suspend fun softDeleteEntity(id: String, timestamp: Long = System.currentTimeMillis()) =
        RestoreStartupGate.withBusinessAccessSuspending { entityDao.softDelete(id, timestamp) }

    suspend fun restoreEntity(id: String, timestamp: Long = System.currentTimeMillis()) =
        RestoreStartupGate.withBusinessAccessSuspending { entityDao.restore(id, timestamp) }

    suspend fun count(): Int = RestoreStartupGate.withBusinessAccessSuspending { entityDao.count() }

    // ------------------------------------------------------------------
    //  Relations
    // ------------------------------------------------------------------

    /**
     * Adds a directed relation. Duplicate (from→to, type) edges are prevented by the
     * unique index on life_relations.
     */
    suspend fun addRelation(
        fromEntityId: String,
        toEntityId: String,
        relationType: String,
        timestamp: Long = System.currentTimeMillis(),
    ): LifeRelationEntity = RestoreStartupGate.withBusinessAccessSuspending {
        val relation = LifeRelationEntity(
            id = UUID.randomUUID().toString(),
            fromEntityId = fromEntityId,
            toEntityId = toEntityId,
            relationType = relationType,
            createdAt = timestamp,
        )
        relationDao.insert(relation)
        relation
    }

    suspend fun removeRelation(id: String) =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.deleteById(id) }

    suspend fun removeRelation(fromId: String, toId: String, type: String) =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.delete(fromId, toId, type) }

    suspend fun relationExists(fromId: String, toId: String, type: String): Boolean =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.exists(fromId, toId, type) > 0 }

    suspend fun getOutgoing(entityId: String): List<LifeRelationEntity> =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.getOutgoing(entityId) }

    suspend fun getOutgoingByType(entityId: String, type: String): List<LifeRelationEntity> =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.getOutgoingByType(entityId, type) }

    suspend fun getIncoming(entityId: String): List<LifeRelationEntity> =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.getIncoming(entityId) }

    suspend fun getIncomingByType(entityId: String, type: String): List<LifeRelationEntity> =
        RestoreStartupGate.withBusinessAccessSuspending { relationDao.getIncomingByType(entityId, type) }

    fun observeOutgoing(entityId: String): Flow<List<LifeRelationEntity>> =
        gateAwareFlow { relationDao.observeOutgoing(entityId) }

    fun observeIncoming(entityId: String): Flow<List<LifeRelationEntity>> =
        gateAwareFlow { relationDao.observeIncoming(entityId) }

    // ------------------------------------------------------------------
    //  Tags
    // ------------------------------------------------------------------

    /**
     * `attachTagByName` below calls [createTag] and [attachTag], each of which takes its own lease. That
     * is safe because leases **nest by counting**: the inner acquisitions simply increment, and the
     * operation stays protected by whichever lease was taken first. What must not happen is two sibling
     * operations each taking and releasing a lease independently — see
     * [com.qq.closie.life.repository.ReferenceRepository] for the case where that mattered.
     */
    suspend fun createTag(
        name: String,
        timestamp: Long = System.currentTimeMillis(),
    ): TagEntity = RestoreStartupGate.withBusinessAccessSuspending {
        val normalized = name.trim().lowercase()
        // Return the existing tag when one with the same normalised name already exists.
        tagDao.getByNormalizedName(normalized)?.let { return@withBusinessAccessSuspending it }
        val tag = TagEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            normalizedName = normalized,
            createdAt = timestamp,
        )
        tagDao.insertTag(tag)
        tag
    }

    suspend fun attachTag(entityId: String, tagId: String) =
        RestoreStartupGate.withBusinessAccessSuspending {
            tagDao.insertCrossRef(EntityTagCrossRef(entityId = entityId, tagId = tagId))
        }

    suspend fun attachTagByName(entityId: String, name: String) =
        RestoreStartupGate.withBusinessAccessSuspending {
            // One outer lease for "create the tag if needed AND attach it": without it a restore could
            // start between the two, leaving a tag that exists and is attached to nothing — or worse, an
            // attachment written after the tag was reverted.
            val tag = createTag(name)
            attachTag(entityId, tag.id)
        }

    suspend fun detachTag(entityId: String, tagId: String) =
        RestoreStartupGate.withBusinessAccessSuspending { tagDao.deleteCrossRef(entityId, tagId) }

    suspend fun tagAttached(entityId: String, tagId: String): Boolean =
        RestoreStartupGate.withBusinessAccessSuspending { tagDao.crossRefExists(entityId, tagId) > 0 }

    fun observeTagsForEntity(entityId: String): Flow<List<TagEntity>> =
        gateAwareFlow { tagDao.observeTagsForEntity(entityId) }

    suspend fun getTagsForEntity(entityId: String): List<TagEntity> =
        RestoreStartupGate.withBusinessAccessSuspending { tagDao.getTagsForEntity(entityId) }

    // ------------------------------------------------------------------
    //  Transaction helper
    // ------------------------------------------------------------------

    /**
     * A lease-protected transaction boundary.
     *
     * ### Why the old three-check shape is gone
     *
     * The previous implementation did `gate -> transaction -> first-line check -> block -> final check ->
     * commit`, on the theory that a final check inside the transaction would catch a restore that began
     * mid-transaction and let Room roll it back. That reasons about the wrong side of the race: it makes
     * the *correction* depend on a check that can itself be scheduled after the restore's replayed rows,
     * and — more fundamentally — it accepted that a business transaction could overlap a restore at all.
     *
     * With a lease, the overlap cannot happen, so there is nothing to detect and nothing to roll back.
     * The lease is taken once for the whole boundary, and [RestoreStartupGate.beginRestore] is refused
     * for as long as it is held — including across the transaction's suspension points, which is the
     * property a `synchronized` block or a per-call check could not provide. The one check that remains
     * is the one the lease itself performs on acquisition: if a restore owns the gate, the transaction is
     * never opened.
     *
     * Nesting is fine: a repository method called from inside the block takes an inner lease, which is a
     * counter increment, not a second lock.
     */
    suspend fun <T> withTransaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending {
            database.withTransaction { block() }
        }
}
