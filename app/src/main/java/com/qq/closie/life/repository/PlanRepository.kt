package com.qq.closie.life.repository

import android.annotation.SuppressLint
import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.dao.PlanDao
import com.qq.closie.life.plan.PlanEntityType
import com.qq.closie.life.plan.PlanItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import com.qq.closie.data.backup.gateAwareFlow

/**
 * The 计划 repository.
 *
 * Design notes worth stating out loud, because they are what keep this module from turning into a
 * habit tracker:
 *
 *  - **Completion is a timestamp, not a score.** [complete] stamps `completedAt`; [uncomplete]
 *    clears it. Nothing counts streaks, nothing accumulates points, nothing turns a missed day into
 *    a red badge. The user writes down an intention and Life OS remembers it.
 *  - **An undated plan is a real plan.** It lands in 接下来 rather than being hidden or rejected.
 *  - **"Today" is a device-time-zone window**, computed in Kotlin ([dayWindow]) and passed to SQL as
 *    explicit epoch bounds. Letting SQLite do `unixepoch(dueAt/1000)` would evaluate in UTC and
 *    quietly move a 00:30 plan to the previous day for anyone east of Greenwich.
 */
class PlanRepository(
    private val database: LifeDatabase,
    private val lifeRepository: LifeRepository,
) {

    /**
     * Every query in this repository reaches the database through this property, which is why the startup
     * gate is consulted **here** and not at the top of each method — see [RestoreStartupGate.gated].
     *
     * A getter rather than a `val` initialiser is the whole point: it is re-evaluated on *every* access,
     * so a repository instance constructed while the gate was READY stops working the moment the gate
     * closes. A constructor-time check cannot do that — the object already exists.
     */
    private val dao: PlanDao get() = database.planDao()

    /**
     * The same gate for the paths that bypass [dao] — the transaction bodies below.
     *
     * ### Why the `dao` getter alone was not enough
     *
     * The comment above says every query in this repository reaches the database through [dao]. For this
     * class that was not true: four methods opened `database.withTransaction { … }` directly, so the
     * transaction boundary — and its reads — were reached without passing through the gated property.
     *
     * A transaction boundary **is** a durable access. Opening one while a restore is swapping the same
     * database is exactly the write the gate exists to refuse, and it does not become safe because the
     * statements inside it consult a gated getter later: by then the transaction is open and its reads
     * have already happened.
     *
     * ### Three checks, because the window is not only at the start
     *
     * ```
     *   1. before the transaction opens   -> don't even start one while the gate is closed
     *   2. first line inside it           -> the gate may have closed while we were scheduling
     *   3. last line before it returns    -> if a restore began mid-transaction, fail *now*
     * ```
     *
     * The third is the one that is easy to omit and the one that matters most. A restore can start at
     * any moment, including while a long transaction is running; without the final check that
     * transaction would **commit** after the snapshot had been taken and the archive applied, silently
     * reintroducing the row the restore had just removed. Throwing instead rolls the transaction back,
     * so the restore's view of the database stays true.
     *
     * The `requireReady` calls throw [com.qq.closie.data.backup.RestoreRecoveryPendingException], the
     * same refusal the property getter produces — a caller cannot tell, and should not be able to tell,
     * which of the two chokepoints stopped it.
     */
    private suspend fun <T> gateCheckedTransaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending {
            database.withTransaction { block() }
        }

    // ------------------------------------------------------------------
    //  Create
    // ------------------------------------------------------------------

    /**
     * Creates a plan together with its backing LifeEntity, in one transaction — the same rule
     * [ReferenceRepository.create] follows, for the same reason: a plan that is not a LifeEntity
     * cannot be tagged or related, and would be a second-class object in its own app.
     */
    suspend fun create(
        title: String,
        note: String? = null,
        dueAt: Long? = null,
        sortOrder: Int = 0,
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis()
    ): PlanItemEntity = gateCheckedTransaction {
        val entity = lifeRepository.createEntity(entityType = PlanEntityType.PLAN, timestamp = now)
        val item = PlanItemEntity(
            id = id,
            lifeEntityId = entity.id,
            title = title.trim(),
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            dueAt = dueAt,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            sortOrder = sortOrder
        )
        dao.insert(item)
        item
    }

    /**
     * True when [id] already exists. Used by the editor to avoid inserting a second row when the
     * user taps 保存 twice before the first insert has committed.
     */
    suspend fun exists(id: String): Boolean =
        RestoreStartupGate.withBusinessAccessSuspending { dao.getById(id) != null }

    // ------------------------------------------------------------------
    //  Read
    // ------------------------------------------------------------------

    suspend fun getById(id: String): PlanItemEntity? =
        RestoreStartupGate.withBusinessAccessSuspending { dao.getById(id) }

    fun observeById(id: String): Flow<PlanItemEntity?> = gateAwareFlow { dao.observeById(id) }

    fun observeAll(): Flow<List<PlanItemEntity>> = gateAwareFlow { dao.observeAll() }

    fun observeCompleted(limit: Int? = null): Flow<List<PlanItemEntity>> =
        gateAwareFlow { if (limit == null) dao.observeCompleted() else dao.observeCompletedLimited(limit) }

    /** 今天 — open plans falling inside the given day window. */
    fun observeToday(zone: ZoneId = ZoneId.systemDefault()): Flow<List<PlanItemEntity>> {
        val (start, end) = dayWindow(zone)
        return gateAwareFlow { dao.observeDueBetween(start, end) }
    }

    /** 今天 including finished ones, so a completed item can still show under its own header. */
    fun observeTodayIncludingDone(zone: ZoneId = ZoneId.systemDefault()): Flow<List<PlanItemEntity>> {
        val (start, end) = dayWindow(zone)
        return gateAwareFlow { dao.observeTodayIncludingDone(start, end) }
    }

    /** 接下来 — open plans due later, plus everything undated ("someday" is legitimate). */
    fun observeUpcoming(zone: ZoneId = ZoneId.systemDefault()): Flow<List<PlanItemEntity>> {
        val (_, end) = dayWindow(zone)
        return gateAwareFlow { dao.observeUpcoming(end) }
    }

    /**
     * 此前 — open plans that fell due before today and were never completed.
     *
     * Without this the page had a hole: an unfinished item due yesterday satisfied neither
     * [observeToday] (its date is not today) nor [observeUpcoming] (`dueAt >= endOfDay` excluded
     * it), so it was simply absent — not hidden behind a filter, not collapsed, absent. The user's
     * intention had been deleted by the passage of time, which is the one thing a plan list must
     * never do.
     */
    fun observeOverdue(zone: ZoneId = ZoneId.systemDefault()): Flow<List<PlanItemEntity>> {
        val (start, _) = dayWindow(zone)
        return gateAwareFlow { dao.observeOverdue(start) }
    }

    /** The home page's 接下来 source: at most a couple of items, never a task dashboard. */
    fun observeUpcomingLimited(
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Flow<List<PlanItemEntity>> {
        val (_, end) = dayWindow(zone)
        return gateAwareFlow { dao.observeUpcomingLimited(end, limit) }
    }

    /**
     * Every open plan, soonest-first, for the home page's 接下来 block.
     *
     * Replaces the old pair of [observeUpcomingLimited] + [observeOverdueLimited] combined in the
     * ViewModel. That composition could not show a plan due *today*: `observeUpcoming` starts at
     * `endOfDay` and `observeOverdue` stops at `startOfDay`, so today's items matched neither and
     * vanished from the home screen — the one place a user is most likely to look.
     *
     * One query over "all open plans" has no such gap by construction, and needs no zone arithmetic
     * at all: the ordering (`NULL` last, then `dueAt ASC`) does the grouping, so today lands between
     * the past and the future on its own.
     */
    fun observeOpenLimited(limit: Int): Flow<List<PlanItemEntity>> = gateAwareFlow { dao.observeOpenLimited(limit) }

    /**
     * Past-due open plans for the home page's preview.
     *
     * The home block is a *preview* of 计划, and a preview that omitted everything overdue would
     * report "nothing coming up" to a user whose most pressing item is a week late.
     */
    fun observeOverdueLimited(
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Flow<List<PlanItemEntity>> {
        val (start, _) = dayWindow(zone)
        return gateAwareFlow { dao.observeOverdueLimited(start, limit) }
    }

    suspend fun countOpen(): Int = RestoreStartupGate.withBusinessAccessSuspending { dao.countOpen() }

    fun observeOpenCount(): Flow<Int> = gateAwareFlow { dao.observeOpenCount() }

    fun observeTodayOpenCount(zone: ZoneId = ZoneId.systemDefault()): Flow<Int> {
        val (start, end) = dayWindow(zone)
        return gateAwareFlow { dao.observeTodayOpenCount(start, end) }
    }

    suspend fun count(): Int = RestoreStartupGate.withBusinessAccessSuspending { dao.count() }

    // ------------------------------------------------------------------
    //  Edit
    // ------------------------------------------------------------------

    /**
     * Field-level edit. A null argument means "leave alone" — except [note] and [dueAt], which the
     * caller clears through the dedicated [clearDueAt] path so that "remove the date" and "I did not
     * touch the date" stay distinguishable.
     */
    suspend fun update(
        id: String,
        title: String? = null,
        note: String? = null,
        dueAt: Long? = null,
        sortOrder: Int? = null,
        now: Long = System.currentTimeMillis()
    ): PlanItemEntity? = gateCheckedTransaction {
        val current = dao.getById(id) ?: return@gateCheckedTransaction null
        val updated = current.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: current.title,
            note = if (note != null) note.trim().takeIf { it.isNotEmpty() } else current.note,
            dueAt = dueAt ?: current.dueAt,
            sortOrder = sortOrder ?: current.sortOrder,
            updatedAt = now
        )
        dao.update(updated)
        touchEntity(current.lifeEntityId, now)
        updated
    }

    /**
     * Removes the due date, moving the plan to 接下来.
     *
     * Runs in a transaction and bumps the LifeEntity revision, like every other mutation here.
     * Clearing a date *is* an edit — it changes which section the plan appears in — so leaving the
     * backing entity untouched would make "rescheduled" indistinguishable from "never scheduled"
     * to anything reading the graph.
     */
    suspend fun clearDueAt(id: String, now: Long = System.currentTimeMillis()): Boolean =
        gateCheckedTransaction {
            val current = dao.getById(id) ?: return@gateCheckedTransaction false
            if (current.dueAt == null) return@gateCheckedTransaction true
            dao.update(current.copy(dueAt = null, updatedAt = now))
            touchEntity(current.lifeEntityId, now)
            true
        }

    // ------------------------------------------------------------------
    //  Complete / uncomplete
    // ------------------------------------------------------------------

    suspend fun complete(id: String, now: Long = System.currentTimeMillis()): Boolean =
        RestoreStartupGate.withBusinessAccessSuspending {
            val current = dao.getById(id) ?: return@withBusinessAccessSuspending false
            if (current.completedAt != null) return@withBusinessAccessSuspending true
            dao.update(current.copy(completedAt = now, updatedAt = now))
            touchEntity(current.lifeEntityId, now)
            true
        }

    /**
     * Undo. Clears `completedAt` but keeps `dueAt` — un-completing something that was due today
     * should put it back under 今天, not silently reschedule it.
     */
    suspend fun uncomplete(id: String, now: Long = System.currentTimeMillis()): Boolean =
        RestoreStartupGate.withBusinessAccessSuspending {
            val current = dao.getById(id) ?: return@withBusinessAccessSuspending false
            if (current.completedAt == null) return@withBusinessAccessSuspending true
            dao.update(current.copy(completedAt = null, updatedAt = now))
            touchEntity(current.lifeEntityId, now)
            true
        }

    // ------------------------------------------------------------------
    //  Delete
    // ------------------------------------------------------------------

    /** Soft-deletes the LifeEntity and drops the typed row — same policy as references. */
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()): Boolean =
        gateCheckedTransaction {
            val current = dao.getById(id) ?: return@gateCheckedTransaction false
            lifeRepository.softDeleteEntity(current.lifeEntityId, now)
            dao.deleteById(id)
            true
        }

    private suspend fun touchEntity(lifeEntityId: String, now: Long) {
        lifeRepository.getEntity(lifeEntityId)?.let { lifeRepository.updateEntity(it, now) }
    }

    companion object {
        /**
         * `[startOfDay, endOfDay)` in epoch millis for [zone].
         *
         * Exclusive upper bound so a plan due at exactly 00:00 tomorrow belongs to tomorrow, and
         * computed from [LocalDate] rather than by adding 86_400_000 ms — a day is not always
         * 24 hours long, and DST would otherwise shift the boundary.
         */
        fun dayWindow(zone: ZoneId = ZoneId.systemDefault(), date: LocalDate = LocalDate.now(zone)): Pair<Long, Long> {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return start to end
        }

        /**
         * Formats a due timestamp as a short 月/日 label; used by the plan rows.
         *
         * Past dates are labelled with the date alone — deliberately *not* "· 已过期". The plan row
         * already sits under 此前, which says everything a user needs to know, and stamping 已过期
         * onto it is the overdue-guilt the spec forbids. The date itself is enough: it tells the
         * user when they meant to do it, which is the useful part.
         */
        @SuppressLint("DefaultLocale")
        fun dueLabel(dueAt: Long?, zone: ZoneId = ZoneId.systemDefault()): String? {
            if (dueAt == null) return null
            val date = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            return when {
                date == today -> "今天"
                date == today.plusDays(1) -> "明天"
                else -> "${date.monthValue}月${date.dayOfMonth}日"
            }
        }
    }
}
