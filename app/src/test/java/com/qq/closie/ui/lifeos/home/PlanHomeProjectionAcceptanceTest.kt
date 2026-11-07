package com.qq.closie.ui.lifeos.home

import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.FinanceRoomTest
import com.qq.closie.life.repository.HomePhotoRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.PlanRepository
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanHomeProjectionAcceptanceTest : FinanceRoomTest() {
    private val today = LocalDate.of(2026, 10, 12)
    private val zone = ZoneId.systemDefault()
    private fun stamp(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun canonicalPlansReprojectAfterEditCompletionUndoAndRevocation() = runBlocking {
        val plans = PlanRepository(db, LifeRepository(db))
        val photos = HomePhotoRepository(db)
        val due = today.plusDays(5)
        val plan = plans.create("周末去旧书店", "看看植物随笔", stamp(due), now = stamp(today), homeVisible = true)
        val privatePlan = plans.create("私下计划", "私密备注", stamp(due), now = stamp(today))
        val undated = plans.create("日期还没有定", now = stamp(today), homeVisible = true)
        val past = plans.create("此前的计划", dueAt = stamp(today.minusDays(1)), now = stamp(today), homeVisible = true)
        val provider = PlanHomeStateProvider(MediaHomeStateProvider(FinanceHomeStateProvider(finance), photos),
            plans, zone) { today }
        val updates = Channel<HomeUiState>(Channel.CONFLATED)
        val observer = launch { provider.stateFor(today).collect { updates.send(it) } }
        suspend fun awaitState(check: (HomeUiState) -> Boolean): HomeUiState = withTimeout(10_000) {
            var state: HomeUiState
            do { state = updates.receive() } while (state.isLoading || state.loadingSources.isNotEmpty() || !check(state))
            state
        }
        try {
            val initial = awaitState { it.futureIntents.any { row -> row.sourceId == plan.id } }
            assertEquals(listOf(plan.id, undated.id), initial.futureIntents.map { it.sourceId })
            assertEquals("看看植物随笔", initial.futureIntents.first().note)
            assertNull(initial.futureIntents.last().expectedDate)
            assertEquals(plan.id, initial.calendarDays.getValue(due).intents.single().sourceId)
            assertTrue(initial.calendarDays.values.flatMap { it.intents }.none { it.sourceId in setOf(privatePlan.id, undated.id) })
            assertTrue(initial.futureIntents.none { it.sourceId == past.id })

            plans.update(plan.id, note = "再找一本散文")
            awaitState { it.futureIntents.firstOrNull()?.note == "再找一本散文" }
            plans.complete(plan.id)
            val completed = awaitState { it.futureIntents.none { row -> row.sourceId == plan.id } }
            assertFalse(completed.calendarDays.containsKey(due))
            plans.uncomplete(plan.id)
            awaitState { it.calendarDays[due]?.intents?.singleOrNull()?.sourceId == plan.id }
            plans.setHomeVisible(plan.id, false)
            val revoked = awaitState { it.futureIntents.none { row -> row.sourceId == plan.id } }
            assertFalse(revoked.calendarDays.containsKey(due))
            assertEquals("再找一本散文", plans.getById(plan.id)?.note)

            val pastHome = withTimeout(10_000) {
                provider.stateFor(today.minusDays(1)).first { it.loadingSources.isEmpty() && !it.isLoading }
            }
            assertTrue(pastHome.futureIntents.isEmpty())
            val todayAgain = withTimeout(10_000) {
                provider.stateFor(today).first { it.loadingSources.isEmpty() && !it.isLoading }
            }
            assertEquals(listOf(undated.id), todayAgain.futureIntents.map { it.sourceId })
        } finally { observer.cancelAndJoin() }
    }

    @Test fun failedFinanceSourceKeepsCanonicalPlanAndExplicitlySharedPhotoWriting() = runBlocking {
        val plans = PlanRepository(db, LifeRepository(db))
        val photos = HomePhotoRepository(db)
        val plan = plans.create("已分享计划", dueAt = stamp(today.plusDays(1)), homeVisible = true)
        val photo = photos.grantValidated("content://synthetic/home-acceptance", "image/png", null, stamp(today))
        photos.setCaption(photo, "真实分享的照片备注", null, RestoreStartupGate.state.value.restoreGeneration)
        val base = FinanceHomeStateProvider(finance, prepare = { _, _ -> error("Synthetic finance read failure") })
        val provider = PlanHomeStateProvider(MediaHomeStateProvider(base, photos), plans, zone) { today }
        val state = withTimeout(10_000) {
            provider.stateFor(today).first { it.failedSources.contains("财务") && it.loadingSources.isEmpty() }
        }
        assertFalse(state.loadFailed)
        assertTrue(state.todayFacts.isEmpty())
        assertEquals(plan.id, state.futureIntents.single().sourceId)
        assertEquals(photo, state.eligibleMedia.single().id)
        val day = state.calendarDays.getValue(today)
        assertEquals(photo, day.photos.single().id)
        assertEquals("真实分享的照片备注", day.timedContent.single().title)
    }
}
