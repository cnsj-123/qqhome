package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.ui.lifeos.calendar.CalendarDayUi
import com.qq.closie.ui.lifeos.calendar.CalendarIntentUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Only canonical plans with explicit Home display consent enter this projection. */
class PlanHomeStateProvider(
    private val base: HomeStateProvider,
    private val plans: PlanRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) }
) : HomeStateProvider {
    private data class Read(val plans:List<PlanItemEntity> = emptyList(),val loading:Boolean=false,val failed:Boolean=false)
    override fun stateFor(date:LocalDate):Flow<HomeUiState> = combine(base.stateFor(date),
        plans.observeHomeVisible().map {Read(it)}.onStart {emit(Read(loading=true))}.catch {emit(Read(failed=true))},
        flow { while(true) {emit(today());delay(60_000)} }.distinctUntilChanged()
    ) {state,read,todayDate->
        // Historical Home is not a replay of today's incomplete intentions. Plans have no historical
        // status journal, so never invent an earlier open/completed state from the current row.
        val open=read.plans.filter {it.completedAt==null}
        val upcoming=if(date.isBefore(todayDate)) emptyList() else open.filter {plan->
            plan.dueAt?.let { !Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isBefore(date) } ?: true
        }
        state.copy(futureIntents=upcoming.take(3).map {plan->FutureIntentUi(plan.id,
            plan.dueAt?.let {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()},plan.title,plan.note)},
            calendarDays=state.calendarDays.toMutableMap().apply {
                open.filter {it.dueAt!=null}.groupBy {Instant.ofEpochMilli(it.dueAt!!).atZone(zone).toLocalDate()}
                    .forEach {(day,rows)->put(day,(get(day)?:CalendarDayUi(day)).let {old->
                        old.copy(intents=old.intents+rows.map {CalendarIntentUi(it.id,it.title,"计划")})
                    })
            }).sourceStatus("计划",read.loading,read.failed)
    }
}
