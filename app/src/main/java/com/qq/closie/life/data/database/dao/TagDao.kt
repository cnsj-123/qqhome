package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: EntityTagCrossRef): Long

    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getByNormalizedName(normalizedName: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("""SELECT * FROM tags
              INNER JOIN entity_tag_cross_ref ON tags.id = entity_tag_cross_ref.tagId
              WHERE entity_tag_cross_ref.entityId = :entityId ORDER BY name ASC""")
    fun observeTagsForEntity(entityId: String): Flow<List<TagEntity>>

    @Query("""SELECT * FROM tags
              INNER JOIN entity_tag_cross_ref ON tags.id = entity_tag_cross_ref.tagId
              WHERE entity_tag_cross_ref.entityId = :entityId""")
    suspend fun getTagsForEntity(entityId: String): List<TagEntity>

    @Query("DELETE FROM entity_tag_cross_ref WHERE entityId = :entityId AND tagId = :tagId")
    suspend fun deleteCrossRef(entityId: String, tagId: String): Int

    @Query("SELECT COUNT(*) FROM entity_tag_cross_ref WHERE entityId = :entityId AND tagId = :tagId")
    suspend fun crossRefExists(entityId: String, tagId: String): Int

    // ---- Backup (v2) --------------------------------------------------------------------------

    @Query("SELECT * FROM tags")
    suspend fun getAllOnce(): List<TagEntity>

    @Query("SELECT * FROM entity_tag_cross_ref")
    suspend fun getAllCrossRefsOnce(): List<EntityTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCrossRefs(refs: List<EntityTagCrossRef>)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Query("DELETE FROM entity_tag_cross_ref")
    suspend fun deleteAllCrossRefs()
}
