package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the 资料库.
 *
 * Search is plain `LIKE` over the five searchable columns. That is deliberately the first version:
 * the dataset is a personal archive (hundreds to low thousands of rows), where a `LIKE` scan is
 * instant and an FTS4/FTS5 virtual table would add a second schema object, an external-content
 * sync and its own migration surface for no measurable gain. The queries are isolated here so
 * swapping in FTS later touches this file and nothing else.
 */
@Dao
interface ReferenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ReferenceItemEntity)

    @Update
    suspend fun update(item: ReferenceItemEntity)

    @Query("DELETE FROM reference_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM reference_items WHERE id = :id")
    suspend fun getById(id: String): ReferenceItemEntity?

    @Query("SELECT * FROM reference_items WHERE id = :id")
    fun observeById(id: String): Flow<ReferenceItemEntity?>

    @Query("SELECT * FROM reference_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ReferenceItemEntity>>

    @Query("SELECT * FROM reference_items WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: ReferenceStatus): Flow<List<ReferenceItemEntity>>

    /**
     * 阅读 module source: anything the user marked as reading material, plus anything saved as an
     * article — both are things you read. Newest first.
     */
    @Query(
        "SELECT * FROM reference_items " +
            "WHERE referenceType IN ('READING','ARTICLE') " +
            "ORDER BY createdAt DESC"
    )
    fun observeReading(): Flow<List<ReferenceItemEntity>>

    @Query("SELECT * FROM reference_items ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ReferenceItemEntity>>

    @Query("SELECT * FROM reference_items WHERE originalCaptureId = :captureId LIMIT 1")
    suspend fun findByOriginalCaptureId(captureId: String): ReferenceItemEntity?

    @Query("SELECT COUNT(*) FROM reference_items")
    suspend fun count(): Int

    /** Inbox size, used by the home page's "待整理" line. */
    @Query("SELECT COUNT(*) FROM reference_items WHERE status = 'INBOX'")
    suspend fun countInbox(): Int

    @Query("SELECT COUNT(*) FROM reference_items WHERE status = 'INBOX'")
    fun observeInboxCount(): Flow<Int>

    @Query("UPDATE reference_items SET status = :status, organizedAt = :organizedAt, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: ReferenceStatus,
        organizedAt: Long?,
        timestamp: Long
    ): Int

    /**
     * Case-insensitive search across title, summary, extracted text, source name and URL.
     *
     * `LIKE` is case-insensitive for ASCII in SQLite by default, which covers URLs and Latin titles;
     * Chinese has no case, so `%q%` matching behaves correctly for CJK too. The query argument is
     * escaped by [com.qq.closie.life.repository.ReferenceRepository.search] before it gets here.
     */
    @Query(
        """
        SELECT * FROM reference_items
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(summary, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(ocrText, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(sourceName, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR IFNULL(sourceUrl, '') LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt DESC
        """
    )
    fun search(query: String): Flow<List<ReferenceItemEntity>>

    @Query(
        """
        SELECT * FROM reference_items
        WHERE status = :status
          AND (title LIKE '%' || :query || '%' ESCAPE '\'
            OR IFNULL(summary, '') LIKE '%' || :query || '%' ESCAPE '\'
            OR IFNULL(ocrText, '') LIKE '%' || :query || '%' ESCAPE '\'
            OR IFNULL(sourceName, '') LIKE '%' || :query || '%' ESCAPE '\'
            OR IFNULL(sourceUrl, '') LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY createdAt DESC
        """
    )
    fun searchByStatus(query: String, status: ReferenceStatus): Flow<List<ReferenceItemEntity>>

    /** Unarchived items only — what 阅读 shows, since archived material is filed away. */
    @Query(
        "SELECT * FROM reference_items " +
            "WHERE referenceType IN ('READING','ARTICLE') AND status != 'ARCHIVED' " +
            "ORDER BY createdAt DESC"
    )
    fun observeActiveReading(): Flow<List<ReferenceItemEntity>>

    /** Distinct types present, so the type filter only offers what actually exists. */
    @Query("SELECT DISTINCT referenceType FROM reference_items")
    suspend fun distinctTypes(): List<ReferenceType>

    // ---- Backup (v2) --------------------------------------------------------------------------

    @Query("SELECT * FROM reference_items")
    suspend fun getAllOnce(): List<ReferenceItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReferenceItemEntity>)

    @Query("DELETE FROM reference_items")
    suspend fun deleteAll()
}
