package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qq.closie.life.core.LifeEntityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LifeEntityDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: LifeEntityEntity)

    @Update
    suspend fun update(entity: LifeEntityEntity)

    @Query("SELECT * FROM life_entities WHERE id = :id")
    suspend fun getById(id: String): LifeEntityEntity?

    @Query("SELECT * FROM life_entities WHERE id = :id")
    fun observeById(id: String): Flow<LifeEntityEntity?>

    @Query("SELECT * FROM life_entities WHERE entityType = :type AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeByType(type: String): Flow<List<LifeEntityEntity>>

    @Query("SELECT * FROM life_entities WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LifeEntityEntity>>

    @Query("UPDATE life_entities SET deletedAt = :timestamp, updatedAt = :timestamp, revision = revision + 1 WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long): Int

    @Query("UPDATE life_entities SET deletedAt = NULL, updatedAt = :timestamp, revision = revision + 1 WHERE id = :id")
    suspend fun restore(id: String, timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM life_entities WHERE deletedAt IS NULL")
    suspend fun count(): Int

    // ---- Backup (v2) --------------------------------------------------------------------------
    // Bulk restore needs whole-table reads and writes. These are deliberately *not* `Flow` and not
    // filtered by `deletedAt`: a backup must round-trip soft-deleted rows too, otherwise restoring
    // would silently resurrect — or rather, silently un-delete — nothing and lose the tombstones
    // the sync layer relies on.

    @Query("SELECT * FROM life_entities")
    suspend fun getAllOnce(): List<LifeEntityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<LifeEntityEntity>)

    @Query("DELETE FROM life_entities")
    suspend fun deleteAll()

    /** Inside-transaction sanity read; see BackupManager.applyLifePayload. */
    @Query("SELECT COUNT(*) FROM life_entities")
    suspend fun countOnce(): Int
}
