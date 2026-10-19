package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase
import java.util.UUID

data class FinanceExpectedTask(val event:FinanceEventEntity,val role:FinanceFlowRole,val plans:List<FinanceExpectedFlowEntity>,
    val targetMinor:Long?,val receivedMinor:Long,val remainingMinor:Long?,val ended:Boolean,val historicalTargetsSeparate:Boolean=false)

object FinanceExpectedTaskProjection {
    fun rows(s:FinanceSnapshot):List<FinanceExpectedTask> {
        val v=s.v4 ?: return emptyList()
        val events=v.events.filter {it.voidedAt==null}.associateBy {it.id}
        val entries=s.entries.filter {it.voidedAt==null && it.direction==FinanceDirection.INFLOW}.associateBy {it.id}
        return v.expected.filter {it.eventId in events && it.role in setOf(FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK)}
            .groupBy {it.eventId to it.role}.map {(key,plans)->
                val active=plans.filter {it.cancelledAt==null}
                val goals=active.ifEmpty {plans}
                // Old records have no round/order identity. Closed cumulative goals cannot be summed,
                // and a clock tie cannot justify guessing which was the latest round.
                val separate=active.isEmpty() && plans.size>1
                val target=if(separate || goals.any {it.amountMinor==null}) null else exactSum(goals.mapNotNull {it.amountMinor})
                val received=exactSum(v.activeLinks.filter {it.eventId==key.first && it.role==key.second && it.entryId in entries}.map {it.allocatedMinor})
                val remaining=if(active.isEmpty()) 0L else target?.let {maxOf(0L,Math.subtractExact(it,received))}
                FinanceExpectedTask(events.getValue(key.first),key.second,plans,target,received,remaining,active.isEmpty() || remaining==0L,separate)
            }.sortedByDescending {it.event.occurredAt}
    }
}

/** A plan is not an obligation and does not generate or adjust cash when edited or ended. */
class FinanceExpectedTaskRepository(private val db:LifeDatabase,finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    private val dao get()=db.financeIntakeDao()
    suspend fun save(token:FinanceCommandToken,eventId:String,eventVersion:Long,role:FinanceFlowRole,amount:Long?,note:String,planId:String?=null):String=
        commands.run(token,"EXPECTED_TASK_SAVE",listOf(eventId,eventVersion,role,amount,note,planId)) {
            val event=requireNotNull(dao.event(eventId));require(event.voidedAt==null && event.updatedAt==eventVersion)
            require(role in setOf(FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK) && (amount==null || amount>0) && note.length<=2_000)
            require(planId!=null || dao.activeExpectedForEvent(eventId).none {it.role==role}) {"此事项已有相同性质预计，请修改原计划，避免重复叠加目标"}
            val old=planId?.let {id->requireNotNull(dao.expected().singleOrNull {it.id==id && it.eventId==eventId && it.role==role && it.cancelledAt==null})}
            val now=clock();val result=old?.id ?: UUID.randomUUID().toString()
            val row=FinanceExpectedFlowEntity(result,eventId,role,amount,note,old?.createdAt ?: now)
            if(old==null) dao.insertExpected(row) else dao.updateExpected(row)
            dao.updateEvent(event.copy(updatedAt=maxOf(now,Math.addExact(event.updatedAt,1L))));result
        }
    suspend fun end(token:FinanceCommandToken,eventId:String,eventVersion:Long,role:FinanceFlowRole,reason:String):String=
        commands.run(token,"EXPECTED_TASK_END",listOf(eventId,eventVersion,role,reason)) {
            val event=requireNotNull(dao.event(eventId));require(event.voidedAt==null && event.updatedAt==eventVersion)
            require(role in setOf(FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK) && reason.isNotBlank() && reason.length<=1_000)
            val rows=dao.activeExpectedForEvent(eventId).filter {it.role==role};require(rows.isNotEmpty())
            val now=clock()
            rows.forEach {dao.updateExpected(it.copy(cancelledAt=now,note=it.note+"\n明确结束："+reason.trim()))}
            dao.updateEvent(event.copy(updatedAt=maxOf(now,Math.addExact(event.updatedAt,1L))));eventId
        }
}
