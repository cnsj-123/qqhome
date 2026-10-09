package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.qq.closie.life.plan.PlanItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for 计划.
 *
 * "Today" is resolved as a *range* `[startOfDay, endOfDay)` computed by the caller from the device
 * time zone, not as a `date(dueAt/1000,'unixepoch')` SQL expression: SQLite's `unixepoch` modifier
 * uses UTC, so it would silently shift a plan due at 00:30 into the previous day for anyone east of
 * Greenwich. Passing explicit epoch bounds keeps the time-zone decision in Kotlin, where the device
 * zone is actually known, and makes the query trivially testable.
 */
@Dao
interface PlanDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: PlanItemEntity)

    @Update
    suspend fun update(item: PlanItemEntity)

    @Query("DELETE FROM plan_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM plan_items WHERE id = :id")
    suspend fun getById(id: String): PlanItemEntity?

    @Query("SELECT * FROM plan_items WHERE id = :id")
    fun observeById(id: String): Flow<PlanItemEntity?>

    @Query("SELECT * FROM plan_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlanItemEntity>>

    /** 今天: due inside the given window and not yet done. */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL AND dueAt IS NOT NULL " +
            "AND dueAt >= :startOfDay AND dueAt < :endOfDay " +
            "ORDER BY dueAt ASC, sortOrder ASC, createdAt ASC"
    )
    fun observeDueBetween(startOfDay: Long, endOfDay: Long): Flow<List<PlanItemEntity>>

    /**
     * 接下来: anything still open that is not today's problem — a later due date, or no due date
     * at all. Undated plans belong here rather than in a hidden bucket; "someday" is a real plan.
     *
     * Note that `dueAt >= :endOfDay` is *not* the whole story any more: plans that fell due before
     * today and are still open must also be reachable, or they disappear from the app entirely the
     * moment midnight passes. They are surfaced separately by [observeOverdue] so the page can give
     * them their own neutral section instead of silently dropping them — see that query.
     */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL AND (dueAt IS NULL OR dueAt >= :endOfDay) " +
            "ORDER BY dueAt IS NULL ASC, dueAt ASC, sortOrder ASC, createdAt ASC"
    )
    fun observeUpcoming(endOfDay: Long): Flow<List<PlanItemEntity>>

    /**
     * 此前 — open plans whose due date is already behind us.
     *
     * This is the fix for the "my overdue task vanished" bug: before this query existed,
     * [observeUpcoming]'s `dueAt >= :endOfDay` bound excluded them and nothing else selected them,
     * so an unfinished plan due yesterday was simply gone from 计划 and from the home page. The only
     * way to find it again was to already know it existed.
     *
     * Intentionally *neutral* naming and ordering: 此前, not 逾期, and soonest-due first rather than
     * sorted to shout. Life OS does not do overdue-red — a late intention is still an intention, and
     * a section that colours it as a failure is the guilt mechanic this app exists to avoid.
     */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL AND dueAt IS NOT NULL AND dueAt < :startOfDay " +
            "ORDER BY dueAt ASC, sortOrder ASC, createdAt ASC"
    )
    fun observeOverdue(startOfDay: Long): Flow<List<PlanItemEntity>>

    /** 接下来 including past-due open plans, for the home page's short preview list. */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL AND dueAt IS NOT NULL AND dueAt < :startOfDay " +
            "ORDER BY dueAt ASC, sortOrder ASC, createdAt ASC LIMIT :limit"
    )
    fun observeOverdueLimited(startOfDay: Long, limit: Int): Flow<List<PlanItemEntity>>

    /** Today's window, ignoring completion — used by the 今天 section header count. */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE dueAt IS NOT NULL AND dueAt >= :startOfDay AND dueAt < :endOfDay " +
            "ORDER BY dueAt ASC, sortOrder ASC, createdAt ASC"
    )
    fun observeTodayIncludingDone(startOfDay: Long, endOfDay: Long): Flow<List<PlanItemEntity>>

    @Query("SELECT * FROM plan_items WHERE completedAt IS NOT NULL ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<PlanItemEntity>>

    @Query("SELECT * FROM plan_items WHERE completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT :limit")
    fun observeCompletedLimited(limit: Int): Flow<List<PlanItemEntity>>

    /**
     * Every open plan, ordered the way the home page's 接下来 block wants to show them.
     *
     * One query rather than a `combine` of the 此前 and 接下来 flows, because composing those two
     * **cannot** produce the correct list: `observeUpcoming`'s predicate is
     * `dueAt IS NULL OR dueAt >= :endOfDay`, so a plan due *today* — the single most important thing
     * on a home screen — matches neither it nor `observeOverdue` (which requires `dueAt < startOfDay`).
     * Today's open items fell through the gap and simply did not appear.
     *
     * The ordering is the product rule, and it is deliberately one expression rather than three
     * concatenated lists: `dueAt IS NULL ASC` sorts dated items first and undated ones last (SQLite
     * orders 0 before 1), then `dueAt ASC` puts the most overdue first and runs forward through today
     * and into the future. So the sequence reads 过去 → 今天 → 未来 → 无日期, which is exactly the
     * order of urgency, and an undated intention never crowds out a dated one.
     */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL " +
            "ORDER BY dueAt IS NULL ASC, dueAt ASC, sortOrder ASC, createdAt ASC LIMIT :limit"
    )
    fun observeOpenLimited(limit: Int): Flow<List<PlanItemEntity>>

    /** Home page "接下来" source: the soonest open items, dated first, undated last. */
    @Query(
        "SELECT * FROM plan_items " +
            "WHERE completedAt IS NULL AND (dueAt IS NULL OR dueAt >= :endOfDay) " +
            "ORDER BY dueAt IS NULL ASC, dueAt ASC, sortOrder ASC, createdAt ASC LIMIT :limit"
    )
    fun observeUpcomingLimited(endOfDay: Long, limit: Int): Flow<List<PlanItemEntity>>
    @Query("SELECT COUNT(*) FROM plan_items WHERE completedAt IS NULL")
    suspend fun countOpen(): Int

    @Query("SELECT COUNT(*) FROM plan_items WHERE completedAt IS NULL")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM plan_items WHERE completedAt IS NULL AND dueAt IS NOT NULL AND dueAt >= :startOfDay AND dueAt < :endOfDay")
    fun observeTodayOpenCount(startOfDay: Long, endOfDay: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM plan_items")
    suspend fun count(): Int

    // ---- Backup (v2) --------------------------------------------------------------------------

    @Query("SELECT * FROM plan_items")
    suspend fun getAllOnce(): List<PlanItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlanItemEntity>)

    @Query("DELETE FROM plan_items")
    suspend fun deleteAll()
}
