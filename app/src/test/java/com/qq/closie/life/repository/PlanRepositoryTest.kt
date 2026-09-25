package com.qq.closie.life.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.plan.PlanEntityType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for 计划's data layer.
 *
 * Two things get more attention here than raw CRUD would suggest:
 *
 *  1. **The day window.** "今天" is computed in Kotlin and handed to SQL as epoch bounds. Getting
 *     this wrong moves items a day either side for anyone not on UTC, and it is invisible in a test
 *     that runs in the default zone — hence the explicit, non-UTC zones below.
 *  2. **The absence of gamification.** §27 forbids points, streaks and penalties. That is a design
 *     constraint, and the closest thing to a test for it is asserting that completion is reversible
 *     and loses no information — there is no counter that "completing" inflates.
 *
 * Robolectric is pinned to API 34 because 4.12.2 does not support 35 (the app's targetSdk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanRepositoryTest {

    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var repo: PlanRepository

    /** A zone far from UTC, so a UTC-based day window would be off by hours, not minutes. */
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        repo = PlanRepository(db, life)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Epoch millis for a wall-clock time in [zone]. */
    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------
    //  Create — always a LifeEntity
    // ------------------------------------------------------------------

    @Test
    fun create_writesBackingLifeEntityOfTypePlan() = runTest {
        val plan = repo.create(title = "换季收衣服", now = 1_000L)

        val entity = life.getEntity(plan.lifeEntityId)
        assertThat(entity).isNotNull()
        assertThat(entity!!.entityType).isEqualTo(PlanEntityType.PLAN)
    }

    @Test
    fun create_normalizesTitleAndBlankNote() = runTest {
        val plan = repo.create(title = "  换季收衣服  ", note = "   ", now = 1_000L)

        assertThat(plan.title).isEqualTo("换季收衣服")
        assertThat(plan.note).isNull()
        assertThat(plan.completedAt).isNull()
    }

    @Test
    fun create_withoutDueDate_isAllowed() = runTest {
        // "Someday" is a legitimate plan. An app that rejects it just teaches people to lie to it.
        val plan = repo.create(title = "有空的时候看看那本书", now = 1_000L)

        assertThat(plan.dueAt).isNull()
        assertThat(repo.getById(plan.id)).isNotNull()
    }

    // ------------------------------------------------------------------
    //  Complete / uncomplete — reversible, no score
    // ------------------------------------------------------------------

    @Test
    fun complete_stampsTimeAndIsIdempotent() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)

        assertThat(repo.complete(plan.id, now = 2_000L)).isTrue()
        assertThat(repo.getById(plan.id)!!.completedAt).isEqualTo(2_000L)

        // Completing twice must not move the timestamp: the first completion is the one that
        // happened, and there is no counter for the second to inflate.
        assertThat(repo.complete(plan.id, now = 3_000L)).isTrue()
        assertThat(repo.getById(plan.id)!!.completedAt).isEqualTo(2_000L)
    }

    @Test
    fun uncomplete_clearsCompletedAtButKeepsDueAt() = runTest {
        // Un-completing something due today must put it back under 今天 rather than silently
        // rescheduling it — which is why dueAt is left alone here.
        val due = at(shanghai, 2026, 9, 24, 9, 0)
        val plan = repo.create(title = "x", dueAt = due, now = 1_000L)
        repo.complete(plan.id, now = 2_000L)

        assertThat(repo.uncomplete(plan.id, now = 3_000L)).isTrue()

        val reloaded = repo.getById(plan.id)!!
        assertThat(reloaded.completedAt).isNull()
        assertThat(reloaded.dueAt).isEqualTo(due)
    }

    @Test
    fun uncomplete_onAnOpenPlanIsANoOp() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)
        assertThat(repo.uncomplete(plan.id, now = 2_000L)).isTrue()
        assertThat(repo.getById(plan.id)!!.completedAt).isNull()
        assertThat(repo.getById(plan.id)!!.updatedAt).isEqualTo(1_000L)
    }

    @Test
    fun complete_bumpsLifeEntityRevision() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)
        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(1L)

        repo.complete(plan.id, now = 2_000L)

        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(2L)
    }

    // ------------------------------------------------------------------
    //  Edit
    // ------------------------------------------------------------------

    @Test
    fun update_appliesOnlyProvidedFields() = runTest {
        val due = at(shanghai, 2026, 9, 24, 9, 0)
        val plan = repo.create(title = "原标题", note = "原备注", dueAt = due, now = 1_000L)

        val updated = repo.update(id = plan.id, title = "新标题", now = 2_000L)!!

        assertThat(updated.title).isEqualTo("新标题")
        assertThat(updated.note).isEqualTo("原备注")
        assertThat(updated.dueAt).isEqualTo(due)
    }

    @Test
    fun update_canClearNoteByPassingEmptyString() = runTest {
        val plan = repo.create(title = "x", note = "要去掉我", now = 1_000L)

        val updated = repo.update(id = plan.id, note = "", now = 2_000L)!!

        assertThat(updated.note).isNull()
    }

    @Test
    fun clearDueAt_movesThePlanToSomeday() = runTest {
        // Removing a date is its own operation, so "I did not touch the date" and "take the date
        // away" stay distinguishable through the same update() signature.
        val due = at(shanghai, 2026, 9, 24, 9, 0)
        val plan = repo.create(title = "x", dueAt = due, now = 1_000L)

        assertThat(repo.clearDueAt(plan.id, now = 2_000L)).isTrue()

        assertThat(repo.getById(plan.id)!!.dueAt).isNull()
        // It must now appear in 接下来 rather than vanishing.
        assertThat(repo.observeUpcoming(shanghai).first().map { it.id }).contains(plan.id)
    }

    @Test
    fun update_unknownIdReturnsNull() = runTest {
        assertThat(repo.update(id = "nope", title = "x", now = 1_000L)).isNull()
    }

    // ------------------------------------------------------------------
    //  The day window — the part that breaks silently
    // ------------------------------------------------------------------

    @Test
    fun dayWindow_isHalfOpenAndZoneAware() {
        val date = LocalDate.of(2026, 9, 24)
        val (start, end) = PlanRepository.dayWindow(shanghai, date)

        // Midnight in Shanghai, which is 16:00 the previous day in UTC. SQLite's unixepoch() would
        // have computed the UTC boundary and put an 00:30 plan on the wrong day.
        assertThat(start).isEqualTo(at(shanghai, 2026, 9, 24, 0, 0))
        assertThat(end).isEqualTo(at(shanghai, 2026, 9, 25, 0, 0))
        assertThat(start).isNotEqualTo(at(ZoneId.of("UTC"), 2026, 9, 24, 0, 0))
    }

    @Test
    fun observeToday_includesBoundaryTimesAndExcludesTheNextDay() = runTest {
        // observeToday() reads the real clock, so "today" here must be the real today — a hardcoded
        // date would pass on one calendar day and fail on the next.
        val today = LocalDate.now(shanghai)
        val tomorrow = today.plusDays(1)

        // Exactly midnight — belongs to today (the window is inclusive at the start).
        val atMidnight = repo.create(title = "零点", dueAt = at(shanghai, today.year, today.monthValue, today.dayOfMonth, 0, 0), now = 1L)
        // 23:59 — still today.
        val atLastMinute = repo.create(
            title = "睡前",
            dueAt = at(shanghai, today.year, today.monthValue, today.dayOfMonth, 23, 59),
            now = 2L
        )
        // 00:00 tomorrow — must belong to tomorrow, which is what the exclusive upper bound buys.
        val atNextMidnight = repo.create(
            title = "明天零点",
            dueAt = at(shanghai, tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth, 0, 0),
            now = 3L
        )
        // Undated — never "today".
        repo.create(title = "某天", now = 4L)

        val todayIds = repo.observeToday(shanghai).first().map { it.id }

        assertThat(todayIds).containsExactly(atMidnight.id, atLastMinute.id)
        assertThat(todayIds).doesNotContain(atNextMidnight.id)
    }

    @Test
    fun observeToday_excludesCompletedPlans() = runTest {
        val today = LocalDate.now(shanghai)
        val (start, _) = PlanRepository.dayWindow(shanghai, today)
        val open = repo.create(title = "还没做", dueAt = start + 3_600_000, now = 1L)
        val done = repo.create(title = "做完了", dueAt = start + 3_600_000, now = 2L)
        repo.complete(done.id, now = 3L)

        assertThat(repo.observeToday(shanghai).first().map { it.id }).containsExactly(open.id)
    }

    @Test
    fun observeTodayIncludingDone_keepsFinishedItemsUnderTheirHeader() = runTest {
        val today = LocalDate.now(shanghai)
        val (start, _) = PlanRepository.dayWindow(shanghai, today)
        val open = repo.create(title = "还没做", dueAt = start + 3_600_000, now = 1L)
        val done = repo.create(title = "做完了", dueAt = start + 3_600_000, now = 2L)
        repo.complete(done.id, now = 3L)

        val ids = repo.observeTodayIncludingDone(shanghai).first().map { it.id }

        assertThat(ids).containsExactly(open.id, done.id)
    }

    @Test
    fun observeUpcoming_includesLaterAndUndatedButNotToday() = runTest {
        val today = LocalDate.now(shanghai)
        val (start, _) = PlanRepository.dayWindow(shanghai, today)

        val earlierToday = repo.create(title = "今天稍后", dueAt = start + 3_600_000, now = 1L)
        val nextWeek = repo.create(
            title = "下周",
            dueAt = at(shanghai, today.plusDays(7).year, today.plusDays(7).monthValue, today.plusDays(7).dayOfMonth, 9, 0),
            now = 2L
        )
        val someday = repo.create(title = "某天", now = 3L)
        val doneLater = repo.create(
            title = "已完成的未来项",
            dueAt = at(shanghai, today.plusDays(8).year, today.plusDays(8).monthValue, today.plusDays(8).dayOfMonth, 9, 0),
            now = 4L
        )
        repo.complete(doneLater.id, now = 5L)

        val ids = repo.observeUpcoming(shanghai).first().map { it.id }

        // 接下来 is "anything not inside today's window, not finished" — later dates and undated
        // plans both belong here, and today's items belong to 今天 instead.
        assertThat(ids).containsExactly(someday.id, nextWeek.id)
        assertThat(ids).doesNotContain(earlierToday.id)
        assertThat(ids).doesNotContain(doneLater.id)
    }

    @Test
    fun observeUpcomingLimited_returnsAtMostTheLimit() = runTest {
        repeat(5) { i ->
            repo.create(title = "稍后 $i", dueAt = at(shanghai, 2030, 1, 1 + i, 9, 0), now = i.toLong())
        }

        assertThat(repo.observeUpcomingLimited(3, shanghai).first()).hasSize(3)
    }

    @Test
    fun observeUpcomingLimited_prefersUndatedOverNothing() = runTest {
        // A home page that shows "接下来" must never come back empty just because nothing carries a
        // date — an undated plan is exactly what belongs there.
        repo.create(title = "没有日期", now = 1L)

        assertThat(repo.observeUpcomingLimited(3, shanghai).first().map { it.title })
            .containsExactly("没有日期")
    }

    // ------------------------------------------------------------------
    //  Counts
    // ------------------------------------------------------------------

    @Test
    fun counts_separateOpenFromCompleted() = runTest {
        assertThat(repo.count()).isEqualTo(0)
        assertThat(repo.countOpen()).isEqualTo(0)

        val a = repo.create(title = "a", now = 1L)
        repo.create(title = "b", now = 2L)
        repo.complete(a.id, now = 3L)

        assertThat(repo.count()).isEqualTo(2)
        assertThat(repo.countOpen()).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    //  Delete
    // ------------------------------------------------------------------

    @Test
    fun delete_softDeletesTheLifeEntity() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)

        assertThat(repo.delete(plan.id, now = 2_000L)).isTrue()

        assertThat(repo.getById(plan.id)).isNull()
        assertThat(life.getEntity(plan.lifeEntityId)!!.deletedAt).isEqualTo(2_000L)
    }

    @Test
    fun delete_unknownIdIsANoOp() = runTest {
        assertThat(repo.delete("nope", now = 1_000L)).isFalse()
    }

    // ------------------------------------------------------------------
    //  Label formatting
    // ------------------------------------------------------------------

    @Test
    fun dueLabel_rendersRelativeDaysThenDates() {
        // Relative to the real clock, because dueLabel itself compares against LocalDate.now(zone).
        // Hardcoding a date here would make the test pass today and fail next week.
        val today = LocalDate.now(shanghai)

        fun on(date: LocalDate, hour: Int = 9): Long =
            at(shanghai, date.year, date.monthValue, date.dayOfMonth, hour, 0)

        assertThat(PlanRepository.dueLabel(null, shanghai)).isNull()
        assertThat(PlanRepository.dueLabel(on(today), shanghai)).isEqualTo("今天")
        assertThat(PlanRepository.dueLabel(on(today.plusDays(1)), shanghai)).isEqualTo("明天")

        val later = today.plusDays(5)
        assertThat(PlanRepository.dueLabel(on(later), shanghai))
            .isEqualTo("${later.monthValue}月${later.dayOfMonth}日")

        // A past date renders as a plain date — deliberately NOT "已过期". bug #9: an overdue label
        // is an accusation, and this list is the user's own note-to-self, not a compliance tracker.
        // The plan still appears (in 此前); it is simply described, not judged.
        val past = today.minusDays(3)
        val pastLabel = PlanRepository.dueLabel(on(past), shanghai)
        assertThat(pastLabel).isEqualTo("${past.monthValue}月${past.dayOfMonth}日")
        assertThat(pastLabel).doesNotContain("过期")
        assertThat(pastLabel).doesNotContain("逾期")
    }

    // ------------------------------------------------------------------
    //  此前 — open items whose date has passed (bug #9)
    // ------------------------------------------------------------------

    /**
     * The hole this closes: `observeUpcoming` excludes anything before today's start, and
     * `observeToday` only matches today. A plan dated last week and never completed therefore
     * matched *neither* query and disappeared from the app entirely — not hidden, not counted,
     * just gone. The user had no way to see it or finish it, which made the list quietly untrustworthy.
     */
    @Test
    fun observeOverdue_returnsOpenPlansWhoseDateHasPassed() = runTest {
        val today = LocalDate.now(shanghai)
        fun on(daysFromToday: Long): Long =
            at(shanghai, today.plusDays(daysFromToday).year,
                today.plusDays(daysFromToday).monthValue,
                today.plusDays(daysFromToday).dayOfMonth, 9, 0)

        val lastWeek = repo.create(title = "把去年的电费单归档", dueAt = on(-7), now = 1_000L)
        val yesterday = repo.create(title = "交房租", dueAt = on(-1), now = 1_000L)
        repo.create(title = "今天的事", dueAt = on(0), now = 1_000L)
        repo.create(title = "以后的事", dueAt = on(5), now = 1_000L)
        repo.create(title = "没有日期", now = 1_000L)

        val overdue = repo.observeOverdue(shanghai).first()

        assertThat(overdue.map { it.id }).containsExactly(lastWeek.id, yesterday.id).inOrder()
    }

    @Test
    fun observeOverdue_excludesCompletedAndTodayAndFuture() = runTest {
        val today = LocalDate.now(shanghai)
        val past = today.minusDays(2)
        val pastAt = at(shanghai, past.year, past.monthValue, past.dayOfMonth, 9, 0)

        val done = repo.create(title = "已完成", dueAt = pastAt, now = 1_000L)
        repo.complete(done.id, now = 2_000L)
        repo.create(title = "今天", dueAt = at(shanghai, today.year, today.monthValue, today.dayOfMonth, 9, 0), now = 1_000L)
        val future = today.plusDays(3)
        repo.create(title = "以后", dueAt = at(shanghai, future.year, future.monthValue, future.dayOfMonth, 9, 0), now = 1_000L)

        assertThat(repo.observeOverdue(shanghai).first()).isEmpty()
    }

    @Test
    fun observeOverdueLimited_returnsAtMostTheLimit() = runTest {
        val today = LocalDate.now(shanghai)
        repeat(5) { i ->
            val d = today.minusDays((i + 1).toLong())
            repo.create(title = "逾期 $i", dueAt = at(shanghai, d.year, d.monthValue, d.dayOfMonth, 9, 0), now = 1_000L + i)
        }

        assertThat(repo.observeOverdueLimited(2, shanghai).first()).hasSize(2)
    }

    /**
     * clearDueAt must bump the LifeEntity revision, exactly like complete() and update().
     *
     * bug #13: it wrote the plan row but left the backing entity untouched, so anything watching the
     * life graph (sync, a future "recently changed" view) never learned the item had changed. The
     * revision is how the graph records "this changed at time T" — a write that skips it is a write
     * the rest of the system cannot see.
     */
    @Test
    fun clearDueAt_bumpsLifeEntityRevision() = runTest {
        val due = at(shanghai, 2026, 9, 24, 9, 0)
        val plan = repo.create(title = "x", dueAt = due, now = 1_000L)
        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(1L)

        repo.clearDueAt(plan.id, now = 2_000L)

        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(2L)
    }

    /**
     * And clearing a date that is already null is a no-op — no revision bump, because nothing
     * changed. Bumping here would make the graph's timestamps meaningless (every call would look
     * like a write).
     */
    @Test
    fun clearDueAt_onAnUndatedPlan_doesNotBumpRevision() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)
        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(1L)

        assertThat(repo.clearDueAt(plan.id, now = 2_000L)).isTrue()

        assertThat(life.getEntity(plan.lifeEntityId)!!.revision).isEqualTo(1L)
    }

    @Test
    fun exists_reportsWhetherAPlanIsPresent() = runTest {
        val plan = repo.create(title = "x", now = 1_000L)
        assertThat(repo.exists(plan.id)).isTrue()
        assertThat(repo.exists("nope")).isFalse()
    }

    // ------------------------------------------------------------------
    //  Home page query: every open plan, in urgency order
    // ------------------------------------------------------------------
    //
    // These replace the previous combine-of-two-flows approach, which could not show a plan due
    // *today*: `observeUpcoming` starts at endOfDay and `observeOverdue` stops at startOfDay, so
    // today's items satisfied neither predicate and vanished from the home screen — the one place
    // a user is most likely to look. Concatenating two of the three time buckets cannot produce the
    // third, so these tests assert on the single query that replaced them.

    private fun dayStart(offsetDays: Long): Long =
        java.time.LocalDate.now()
            .plusDays(offsetDays)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun home_containsPastOpenPlan() = runTest {
        repo.create(title = "上周就该做的", dueAt = dayStart(-7))
        val home = repo.observeOpenLimited(10).first()
        assertThat(home.map { it.title }).contains("上周就该做的")
    }

    @Test
    fun home_containsTodayOpenPlan() = runTest {
        // The regression: this one used to be missing entirely.
        repo.create(title = "今天要做的", dueAt = dayStart(0) + 9 * 60 * 60 * 1000)
        val home = repo.observeOpenLimited(10).first()
        assertThat(home.map { it.title }).contains("今天要做的")
    }

    @Test
    fun home_containsFutureOpenPlan() = runTest {
        repo.create(title = "下周要做的", dueAt = dayStart(7))
        val home = repo.observeOpenLimited(10).first()
        assertThat(home.map { it.title }).contains("下周要做的")
    }

    @Test
    fun home_placesUndatedLast() = runTest {
        repo.create(title = "没有日期", dueAt = null)
        repo.create(title = "很久以前", dueAt = dayStart(-30))
        repo.create(title = "今天", dueAt = dayStart(0) + 60_000)
        repo.create(title = "以后", dueAt = dayStart(30))

        val home = repo.observeOpenLimited(10).first()

        // 过去 → 今天 → 未来 → 无日期. An undated intention must never crowd out a dated one, and the
        // ordering is expressed in SQL (`dueAt IS NULL ASC`) rather than by the caller reassembling
        // three lists — which is what made the old version fragile.
        assertThat(home.map { it.title })
            .containsExactly("很久以前", "今天", "以后", "没有日期")
            .inOrder()
    }

    @Test
    fun home_excludesCompletedPlans() = runTest {
        val done = repo.create(title = "已完成", dueAt = dayStart(-1))
        repo.complete(done.id, now = 5_000L)
        repo.create(title = "未完成", dueAt = dayStart(1))

        val home = repo.observeOpenLimited(10).first()
        assertThat(home.map { it.title }).containsExactly("未完成")
    }

    @Test
    fun home_respectsLimit() = runTest {
        repo.create(title = "第一条", dueAt = dayStart(-3))
        repo.create(title = "第二条", dueAt = dayStart(-2))
        repo.create(title = "第三条", dueAt = dayStart(-1))

        val home = repo.observeOpenLimited(2).first()
        assertThat(home).hasSize(2)
        // Soonest first: the limit must truncate the *tail*, not an arbitrary two.
        assertThat(home.map { it.title }).containsExactly("第一条", "第二条").inOrder()
    }
}
