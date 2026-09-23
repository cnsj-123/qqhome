package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qq.closie.life.core.LifeRelationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LifeRelationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(relation: LifeRelationEntity)

    @Query("DELETE FROM life_relations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM life_relations WHERE fromEntityId = :fromId AND toEntityId = :toId AND relationType = :type")
    suspend fun delete(fromId: String, toId: String, type: String): Int

    @Query("SELECT * FROM life_relations WHERE fromEntityId = :entityId ORDER BY createdAt DESC")
    suspend fun getOutgoing(entityId: String): List<LifeRelationEntity>

    @Query("SELECT * FROM life_relations WHERE fromEntityId = :entityId AND relationType = :type ORDER BY createdAt DESC")
    suspend fun getOutgoingByType(entityId: String, type: String): List<LifeRelationEntity>

    @Query("SELECT * FROM life_relations WHERE toEntityId = :entityId ORDER BY createdAt DESC")
    suspend fun getIncoming(entityId: String): List<LifeRelationEntity>

    @Query("SELECT * FROM life_relations WHERE toEntityId = :entityId AND relationType = :type ORDER BY createdAt DESC")
    suspend fun getIncomingByType(entityId: String, type: String): List<LifeRelationEntity>

    @Query("SELECT * FROM life_relations WHERE fromEntityId = :entityId ORDER BY createdAt DESC")
    fun observeOutgoing(entityId: String): Flow<List<LifeRelationEntity>>

    @Query("SELECT * FROM life_relations WHERE toEntityId = :entityId ORDER BY createdAt DESC")
    fun observeIncoming(entityId: String): Flow<List<LifeRelationEntity>>

    @Query("SELECT COUNT(*) FROM life_relations WHERE fromEntityId = :fromId AND toEntityId = :toId AND relationType = :type")
    suspend fun exists(fromId: String, toId: String, type: String): Int
}
