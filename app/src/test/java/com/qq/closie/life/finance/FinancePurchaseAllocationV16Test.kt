package com.qq.closie.life.finance

import com.qq.closie.life.shopping.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinancePurchaseAllocationV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    @Test fun removingPurchaseAttributionRequiresExplicitReleaseOfRefundsAndNeverChangesCashOrEventCost()=runTest {
        val a=account();val paid=payment(a.id,10_000)
        val event=eventId(paid.id);val link=finance.snapshot().v4!!.activeLinks.single()
        finance.economics.confirmEconomic(token(),FinanceEconomicInput(eventId=event,description="合成购买",currencyCode="CNY",occurredAt=2_000,
            valueMinor=10_000,valueBasis="合成确认",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput("fund","self",10_000,link.id,"实际付款")),
            shares=listOf(FinanceShareInput("share",10_000,"self","self","fund",FinanceShareRelation.PERSONAL_CONFIRMED))))
        val purchase=finance.shopping.save(token(),ShoppingPurchaseInput(title="合成商品",currencyCode="CNY",occurredAt=2_000,
            lines=listOf(ShoppingLineInput("line","合成明细",1,10_000))))
        val allocation=finance.economics.allocatePurchase(token(),"line",event,10_000,10_000,1)
        finance.economics.recordAdjustment(token(),event,2,FinanceFlowRole.REFUND,FinanceCashInput(a.id,2_000,3_000),2_000,"line")
        val before=finance.snapshot()
        assertTrue(runCatching {finance.economics.removePurchaseAllocation(token(),allocation,1,3,false)}.isFailure)
        assertEquals(before,finance.snapshot())
        assertTrue(runCatching {finance.economics.editPurchaseAllocation(token(),allocation,1,3,20_000,10_000,true)}.isFailure)
        assertEquals(before,finance.snapshot())
        finance.economics.removePurchaseAllocation(token(),allocation,1,3,true)
        val after=finance.snapshot()
        assertEquals(before.entries,after.entries);assertEquals(2,after.entries.size)
        assertEquals(8_000L,FinanceCostProjection.eventTotal(after,event).confirmedMinor)
        assertNotNull(after.v16!!.purchaseAllocationRows.single().voidedAt)
        assertNull(after.v16!!.costAdjustmentRows.single().lineId)
        assertTrue(ShoppingCostProjection.lines(after,purchase).single().incomplete)
        assertTrue(runCatching {finance.economics.removePurchaseAllocation(token(),allocation,1,3,true)}.isFailure)
        assertEquals(after,finance.snapshot())
    }
    @Test fun anotherEventOnSameLineCannotCoverThisEventsRefundAfterCorrection()=runTest {
        val a=account()
        suspend fun economic(suffix:String):String {
            val entry=payment(a.id,10_000);val event=eventId(entry.id)
            val link=finance.snapshot().v4!!.activeLinks.single {it.entryId==entry.id}
            finance.economics.confirmEconomic(token(),FinanceEconomicInput(eventId=event,description="合成"+suffix,currencyCode="CNY",occurredAt=2_000,
                valueMinor=10_000,valueBasis="合成确认",categoryId=null,allocationsComplete=true,
                funding=listOf(FinanceFundingInput("fund$suffix","self",10_000,link.id,"实际付款")),
                shares=listOf(FinanceShareInput("share$suffix",10_000,"self","self","fund$suffix",FinanceShareRelation.PERSONAL_CONFIRMED))))
            return event
        }
        val first=economic("A");val second=economic("B")
        finance.shopping.save(token(),ShoppingPurchaseInput(title="共用合成商品",currencyCode="CNY",occurredAt=2_000,
            lines=listOf(ShoppingLineInput("line","共用明细",1,20_000))))
        val allocation=finance.economics.allocatePurchase(token(),"line",first,10_000,10_000,1)
        finance.economics.allocatePurchase(token(),"line",second,10_000,10_000,1)
        finance.economics.recordAdjustment(token(),first,2,FinanceFlowRole.REFUND,FinanceCashInput(a.id,8_000,3_000),8_000,"line")
        val before=finance.snapshot()
        assertTrue(runCatching {finance.economics.editPurchaseAllocation(token(),allocation,1,3,10_000,0,false)}.isFailure)
        assertEquals(before,finance.snapshot())
        finance.economics.editPurchaseAllocation(token(),allocation,1,3,10_000,0,true)
        val after=finance.snapshot()
        assertEquals(before.entries,after.entries)
        assertNull(after.v16!!.costAdjustmentRows.single().lineId)
        assertEquals(2_000L,FinanceCostProjection.eventTotal(after,first).confirmedMinor)
        assertEquals(10_000L,FinanceCostProjection.eventTotal(after,second).confirmedMinor)
    }
}
