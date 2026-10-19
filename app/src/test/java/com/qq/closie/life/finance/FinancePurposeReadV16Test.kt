package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinancePurposeReadV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    @Test fun excludedStatisticsAreStillFactsAndPurposesExpireIndependently()=runTest {
        val a=account()
        val p=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="合成已授权事实",occurredAt=2_000,
            event=FinanceEventInput(statPolicy=FinanceStatPolicy.EXCLUDE))
        val event=eventId(p.id)
        assertTrue(FinancePurposeRead.facts(finance.snapshot(),FinanceReadPurpose.HOME,"home-memory",1_000).isEmpty())
        val grant=finance.workspace.grant(token(),FinanceReadPurpose.HOME,"home-memory",setOf(event),emptySet(),true,false,false,3_000)
        var s=finance.snapshot()
        val fact=FinancePurposeRead.facts(s,FinanceReadPurpose.HOME,"home-memory",1_000).single()
        assertEquals(100L,fact.amountMinor);assertNull(fact.description);assertNull(fact.occurredAt)
        assertTrue(FinancePurposeRead.facts(s,FinanceReadPurpose.SHARE,"home-memory",1_000).isEmpty())
        assertTrue(FinancePurposeRead.facts(s,FinanceReadPurpose.HOME,"home-memory",3_000).isEmpty())
        finance.workspace.revoke(token(),grant);s=finance.snapshot()
        assertTrue(FinancePurposeRead.facts(s,FinanceReadPurpose.HOME,"home-memory",1_000).isEmpty())
    }
    @Test fun eventGrantDoesNotDiscloseWholeSharedCashEntry()=runTest {
        val a=account();val entry=payment(a.id,10_000)
        val s=finance.snapshot();val first=s.v4!!.events.single()
        val other=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=1,description="其他事项",occurredAt=4_000,event=FinanceEventInput())
        val second=eventId(other.id)
        finance.allocateEntry(entry.id,listOf(FinanceAllocation(first.id,FinanceFlowRole.PAYMENT,5_000),FinanceAllocation(second,FinanceFlowRole.PAYMENT,5_000)),entry.revision)
        val grant=finance.workspace.grant(token(),FinanceReadPurpose.LOCAL_QUERY,"local-conditions",setOf(first.id),emptySet(),true,true,true,5_000)
        val current=finance.snapshot()
        assertTrue(FinancePurposeRead.query(current,FinanceQuerySpec(mode=FinanceQueryMode.CASH_MOVEMENT),grant,1_000).cash.isEmpty())
        val result=FinancePurposeRead.query(current,FinanceQuerySpec(),grant,1_000)
        assertEquals(5_000L,result.totals.getValue("CNY").confirmedMinor)
        assertTrue(result.costs.all {it.eventId==first.id})
    }
    @Test fun unknownLegacyShareNeverBecomesConfirmedZeroOnHome()=runTest {
        val a=account();val p=payment(a.id,nature=FinanceNature.SPLIT,share=null)
        val event=eventId(p.id)
        finance.workspace.grant(token(),FinanceReadPurpose.HOME,"home-memory",setOf(event),emptySet(),true,true,true,5_000)
        val fact=FinancePurposeRead.facts(finance.snapshot(),FinanceReadPurpose.HOME,"home-memory",1_000).single()
        assertNull(fact.amountMinor);assertTrue(fact.incomplete)
    }
}
