package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureStatus
import kotlinx.coroutines.flow.Flow

/**
 * Enum parameters ([CaptureStatus]) are safe here because [com.qq.closie.life.data.database.LifeDatabase]
 * declares the converters at database scope, so Room applies them to query bind arguments too —
 * not just to entity fields.
 */
@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: CaptureItemEntity)

    @Update
    suspend fun update(item: CaptureItemEntity)

    @Delete
    suspend fun delete(item: CaptureItemEntity)

    @Query("SELECT * FROM capture_items WHERE id = :id")
    suspend fun getById(id: String): CaptureItemEntity?

    @Query("SELECT * FROM capture_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CaptureItemEntity>>

    @Query("SELECT * FROM capture_items WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: CaptureStatus): Flow<List<CaptureItemEntity>>

    @Query("SELECT * FROM capture_items WHERE status IN ('NEW','PROCESSING','NEEDS_REVIEW') ORDER BY createdAt DESC")
    fun observePending(): Flow<List<CaptureItemEntity>>

    @Query(
        "UPDATE capture_items SET status = :status, updatedAt = :timestamp, " +
            "errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun updateStatus(
        id: String,
        status: CaptureStatus,
        timestamp: Long,
        errorMessage: String? = null
    ): Int

    @Query("SELECT COUNT(*) FROM capture_items")
    suspend fun count(): Int

    /**
     * Case-insensitive search over the user-visible parts of a capture (v0.3.0).
     *
     * The 记录 page gained a search field, and it searches the same four things a user would
     * remember about a record: the title they gave it, the text that was captured, their own note,
     * and the URL. `ESCAPE '\'` lets [com.qq.closie.life.repository.CaptureRepository.search] pass a
     * literal `%` or `_` from user input without it turning into a wildcard.
     */
    @Query(
        """
        SELECT * FROM capture_items
        WHERE IFNULL(displayTitle, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(rawText, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(note, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(sourceUrl, '') LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt DESC
        """
    )
    fun search(query: String): Flow<List<CaptureItemEntity>>

    /** Newest captures, for the home page's 最近 block. */
    @Query("SELECT * FROM capture_items ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureItemEntity>>

    // ---- Backup (v2) --------------------------------------------------------------------------

    @Query("SELECT * FROM capture_items")
    suspend fun getAllOnce(): List<CaptureItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CaptureItemEntity>)

    @Query("DELETE FROM capture_items")
    suspend fun deleteAll()
}
