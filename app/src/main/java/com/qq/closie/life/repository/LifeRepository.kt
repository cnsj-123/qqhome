package com.qq.closie.life.repository

import androidx.room.withTransaction
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.dao.LifeEntityDao
import com.qq.closie.life.data.database.dao.LifeRelationDao
import com.qq.closie.life.data.database.dao.TagDao
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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
    private val entityDao: LifeEntityDao = database.lifeEntityDao()
    private val relationDao: LifeRelationDao = database.lifeRelationDao()
    private val tagDao: TagDao = database.tagDao()

    // ------------------------------------------------------------------
    //  Entity CRUD
    // ------------------------------------------------------------------

    suspend fun createEntity(
        entityType: String,
        timestamp: Long = System.currentTimeMillis(),
    ): LifeEntityEntity {
        val entity = LifeEntityEntity(
            id = UUID.randomUUID().toString(),
            entityType = entityType,
            createdAt = timestamp,
            updatedAt = timestamp,
            deletedAt = null,
            revision = 1L,
        )
        entityDao.insert(entity)
        return entity
    }

    suspend fun getEntity(id: String): LifeEntityEntity? = entityDao.getById(id)

    fun observeEntity(id: String): Flow<LifeEntityEntity?> = entityDao.observeById(id)

    fun observeByType(type: String): Flow<List<LifeEntityEntity>> = entityDao.observeByType(type)

    fun observeRecent(limit: Int = 20): Flow<List<LifeEntityEntity>> = entityDao.observeRecent(limit)

    /**
     * Meaningful update: bumps revision and updatedAt. The caller passes the updated entity
     * (with new field values already set); this helper only manages the audit columns.
     */
    suspend fun updateEntity(entity: LifeEntityEntity, timestamp: Long = System.currentTimeMillis()): LifeEntityEntity {
        val updated = entity.copy(
            updatedAt = timestamp,
            revision = entity.revision + 1L,
        )
        entityDao.update(updated)
        return updated
    }

    suspend fun softDeleteEntity(id: String, timestamp: Long = System.currentTimeMillis()) {
        entityDao.softDelete(id, timestamp)
    }

    suspend fun restoreEntity(id: String, timestamp: Long = System.currentTimeMillis()) {
        entityDao.restore(id, timestamp)
    }

    suspend fun count(): Int = entityDao.count()

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
    ): LifeRelationEntity {
        val relation = LifeRelationEntity(
            id = UUID.randomUUID().toString(),
            fromEntityId = fromEntityId,
            toEntityId = toEntityId,
            relationType = relationType,
            createdAt = timestamp,
        )
        relationDao.insert(relation)
        return relation
    }

    suspend fun removeRelation(id: String) {
        relationDao.deleteById(id)
    }

    suspend fun removeRelation(fromId: String, toId: String, type: String) {
        relationDao.delete(fromId, toId, type)
    }

    suspend fun relationExists(fromId: String, toId: String, type: String): Boolean =
        relationDao.exists(fromId, toId, type) > 0

    suspend fun getOutgoing(entityId: String): List<LifeRelationEntity> =
        relationDao.getOutgoing(entityId)

    suspend fun getOutgoingByType(entityId: String, type: String): List<LifeRelationEntity> =
        relationDao.getOutgoingByType(entityId, type)

    suspend fun getIncoming(entityId: String): List<LifeRelationEntity> =
        relationDao.getIncoming(entityId)

    suspend fun getIncomingByType(entityId: String, type: String): List<LifeRelationEntity> =
        relationDao.getIncomingByType(entityId, type)

    fun observeOutgoing(entityId: String): Flow<List<LifeRelationEntity>> =
        relationDao.observeOutgoing(entityId)

    fun observeIncoming(entityId: String): Flow<List<LifeRelationEntity>> =
        relationDao.observeIncoming(entityId)

    // ------------------------------------------------------------------
    //  Tags
    // ------------------------------------------------------------------

    suspend fun createTag(
        name: String,
        timestamp: Long = System.currentTimeMillis(),
    ): TagEntity {
        val normalized = name.trim().lowercase()
        // Return the existing tag when one with the same normalised name already exists.
        tagDao.getByNormalizedName(normalized)?.let { return it }
        val tag = TagEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            normalizedName = normalized,
            createdAt = timestamp,
        )
        tagDao.insertTag(tag)
        return tag
    }

    suspend fun attachTag(entityId: String, tagId: String) {
        tagDao.insertCrossRef(EntityTagCrossRef(entityId = entityId, tagId = tagId))
    }

    suspend fun attachTagByName(entityId: String, name: String) {
        val tag = createTag(name)
        attachTag(entityId, tag.id)
    }

    suspend fun detachTag(entityId: String, tagId: String) {
        tagDao.deleteCrossRef(entityId, tagId)
    }

    suspend fun tagAttached(entityId: String, tagId: String): Boolean =
        tagDao.crossRefExists(entityId, tagId) > 0

    fun observeAllTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    fun observeTagsForEntity(entityId: String): Flow<List<TagEntity>> =
        tagDao.observeTagsForEntity(entityId)

    suspend fun getTagsForEntity(entityId: String): List<TagEntity> =
        tagDao.getTagsForEntity(entityId)

    // ------------------------------------------------------------------
    //  Transaction helper
    // ------------------------------------------------------------------

    suspend fun <T> withTransaction(block: suspend () -> T): T {
        return database.withTransaction(block)
    }
}
