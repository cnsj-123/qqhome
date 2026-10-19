package com.qq.closie.life.finance

import com.qq.closie.data.backup.LifeBackupApplier
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceExpectedTasksV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    @Test fun unknownPlanPartialReceiptExplicitEndingAndRestoreDoNotInventMoneyOrReopenTask()=runTest {
        val a=account();payment(a.id,20_000)
        suspend fun event()=finance.snapshot().v4!!.events.single()
        val start=event()
        val id=finance.expectations.save(token(),start.id,start.updatedAt,FinanceFlowRole.REBATE,null,"合成待办")
        var task=FinanceExpectedTaskProjection.rows(finance.snapshot()).single()
        assertNull(task.targetMinor);assertNull(task.remainingMinor);assertFalse(task.ended)
        assertEquals(1,finance.snapshot().entries.size)
        var e=event()
        finance.expectations.save(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,4_000,"合成预计",id)
        e=event()
        finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=1_000,description="合成实际返利",occurredAt=3_000,
            event=FinanceEventInput(existingEventId=e.id,role=FinanceFlowRole.REBATE,expectedEventUpdatedAt=e.updatedAt))
        task=FinanceExpectedTaskProjection.rows(finance.snapshot()).single()
        assertEquals(1_000L,task.receivedMinor);assertEquals(3_000L,task.remainingMinor)
        val before=finance.snapshot();e=event()
        finance.expectations.end(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,"明确放弃剩余")
        val ended=finance.snapshot()
        assertEquals(before.entries,ended.entries)
        assertTrue(ended.v16!!.obligationRows.isEmpty())
        task=FinanceExpectedTaskProjection.rows(ended).single()
        assertTrue(task.ended);assertEquals(4_000L,task.targetMinor);assertEquals(1_000L,task.receivedMinor)
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        assertTrue(FinanceExpectedTaskProjection.rows(finance.snapshot()).single().ended)
        assertEquals(2,finance.snapshot().entries.size)
        assertEquals(19_000L,FinanceCostProjection.eventTotal(finance.snapshot(),start.id).confirmedMinor)
    }
    @Test fun duplicateTargetAndStalePlanEditAreRejectedAtomically()=runTest {
        val a=account();payment(a.id)
        val event=finance.snapshot().v4!!.events.single()
        val plan=finance.expectations.save(token(),event.id,event.updatedAt,FinanceFlowRole.CASHBACK,100,"")
        val current=finance.snapshot();val updated=current.v4!!.events.single()
        assertTrue(runCatching {finance.expectations.save(token(),event.id,event.updatedAt,FinanceFlowRole.CASHBACK,200,"",plan)}.isFailure)
        assertTrue(runCatching {finance.expectations.save(token(),event.id,updated.updatedAt,FinanceFlowRole.CASHBACK,200,"")}.isFailure)
        assertEquals(current,finance.snapshot())
    }
    @Test fun closedCumulativeTargetsWithEqualClockArePresentedSeparatelyAndNeverAdded()=runTest {
        val a=account();payment(a.id)
        suspend fun event()=finance.snapshot().v4!!.events.single()
        var e=event()
        finance.expectations.save(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,10_000,"首个累计目标")
        e=event();finance.expectations.end(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,"首轮结束")
        e=event();finance.expectations.save(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,15_000,"后续累计目标，含先前到账")
        e=event();finance.expectations.end(token(),e.id,e.updatedAt,FinanceFlowRole.REBATE,"后续结束")
        val row=FinanceExpectedTaskProjection.rows(finance.snapshot()).single()
        assertTrue(row.ended);assertTrue(row.historicalTargetsSeparate);assertNull(row.targetMinor)
        assertEquals(setOf(10_000L,15_000L),row.plans.map {it.amountMinor}.toSet())
        assertEquals(1,row.plans.map {it.createdAt}.distinct().size)
        assertEquals(1,finance.snapshot().entries.size)
    }
}
