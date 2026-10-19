package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceFilterStateV16Test:FinanceRoomTest() {
    @Test fun processStatePreservesIndependentRangeAndNegativeAmountBoundsWithoutFinancialTotals() {
        val filters=listOf(FinanceFilter("合成筛选",FinancePeriod.CUSTOM,FinanceDateRange(1_000,4_000),"合成区间",
            "account","category","tag",FinanceNature.SPLIT,-4_000,20_000,"CNY"),FinanceFilter(period=FinancePeriod.ALL,range=FinanceDateRange()))
        filters.forEach {assertEquals(it,FinanceFilterState.decode(FinanceFilterState.encode(it)))}
        val bundle=FinanceFilterState.encode(filters.first())
        assertFalse(bundle.containsKey("balance"));assertFalse(bundle.containsKey("totals"))
        bundle.putString("query","合".repeat(201))
        assertEquals(200,FinanceFilterState.decode(bundle).query.length)
    }
    @Test fun intervalDrilldownUsesExactOpenClosedBoundsAndKeepsPostingSeparateFromCash()=runTest {
        val a=account()
        suspend fun out(amount:Long,at:Long)=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,
            amountMinor=amount,description="合成区间",occurredAt=at)
        val first=out(100,1_000);val second=out(200,2_000);val unknown=out(300,3_000)
        fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
        finance.workspace.setPostingDate(token(),first.id,first.revision,3_000,FinanceTimePrecision.INSTANT,"合成确认")
        finance.workspace.setPostingDate(token(),second.id,second.revision,1_000,FinanceTimePrecision.INSTANT,"合成确认")
        val s=finance.snapshot();val all=FinanceAccountProjection.register(s,a.id)
        assertEquals(setOf(second.id,unknown.id),FinanceRegisterFilter(after=1_000,through=3_000).apply(all,s).map {it.entry.id}.toSet())
        val posted=FinanceRegisterFilter(after=1_000,through=3_000,posted=true).apply(all,s)
        assertEquals(listOf(first.id),posted.map {it.entry.id})
        assertEquals(all.single {it.entry.id==first.id},posted.single())
    }
    @Test fun actualAccountFilterKeepsEveryVisibleRunningBalanceIdenticalToCompleteRegister()=runTest {
        val a=account()
        payment(a.id,1_000)
        finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=400,description="合成到账",occurredAt=3_000,
            event=FinanceEventInput(role=FinanceFlowRole.OTHER))
        payment(a.id,200)
        val all=FinanceAccountProjection.register(finance.snapshot(),a.id)
        val visible=FinanceRegisterFilter(query="到账",direction="INFLOW").apply(all)
        assertEquals(1,visible.size)
        assertEquals(all.associateBy {it.entry.id}.getValue(visible.single().entry.id),visible.single())
        assertEquals(-800L,visible.single().balanceMinor)
        assertTrue(FinanceRegisterFilter(start="错误日期").apply(all).isEmpty())
        assertEquals(all,FinanceRegisterFilter().apply(all))
    }
}
