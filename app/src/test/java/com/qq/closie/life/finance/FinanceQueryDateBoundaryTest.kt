package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceQueryDateBoundaryTest : FinanceRoomTest() {
    private suspend fun unknown(vararg ids: String) = db.financeV16Dao().putEntryTiming(ids.map {
        FinanceEntryTimingEntity(it, cashPrecision = FinanceTimePrecision.UNKNOWN,
            postedSource = "收付日期未记录", createdAt = clock, updatedAt = clock)
    })

    @Test fun boundedCashNeverUsesAnUnknownDatesPlaceholderButRetainsOtherFilters() = runTest {
        val a = account(); val other = account("其他账户")
        val known = finance.saveEntry(accountId = a.id, direction = FinanceDirection.OUTFLOW,
            amountMinor = 100, description = "target known", occurredAt = 2_000)
        val inside = finance.saveEntry(accountId = a.id, direction = FinanceDirection.INFLOW,
            amountMinor = 200, description = "target inside", occurredAt = 2_000)
        val outside = finance.saveEntry(accountId = a.id, direction = FinanceDirection.INFLOW,
            amountMinor = 300, description = "target outside", occurredAt = 90_000)
        val deniedAccount = finance.saveEntry(accountId = other.id, direction = FinanceDirection.INFLOW,
            amountMinor = 400, description = "target other account", occurredAt = 2_000)
        unknown(inside.id, outside.id, deniedAccount.id)
        val snapshot = finance.snapshot()
        val spec = FinanceQuerySpec(mode = FinanceQueryMode.CASH_MOVEMENT, startInclusive = 1_000, endExclusive = 3_000,
            accountIds = setOf(a.id), text = "target")
        val period = FinanceQueryEngine.execute(snapshot, spec)
        assertEquals(listOf(known.id), period.cash.map { it.id })
        assertEquals(setOf(inside.id, outside.id), period.undatedCash.map { it.id }.toSet())
        assertEquals(PersonalCostTotal(100, 0), period.totals.getValue("CNY"))
        val all = FinanceQueryEngine.execute(snapshot, spec.copy(startInclusive = null, endExclusive = null))
        assertEquals(setOf(known.id, inside.id, outside.id), all.cash.map { it.id }.toSet())
        assertTrue(all.undatedCash.isEmpty()); assertEquals(PersonalCostTotal(-400, 0), all.totals.getValue("CNY"))
    }

    @Test fun anEconomicOccurrenceRemainsDatedWhenOnlyItsPaymentCashDateIsUnknown() = runTest {
        val a = account(); val payment = payment(a.id, 1_000)
        unknown(payment.id)
        val snapshot = finance.snapshot()
        val result = FinanceQueryEngine.execute(snapshot, FinanceQuerySpec(startInclusive = 1_000, endExclusive = 3_000))
        assertEquals(1, result.costs.size); assertTrue(result.undatedCosts.isEmpty())
        assertEquals(PersonalCostTotal(1_000, 0), result.totals.getValue("CNY"))
        val cash = FinanceQueryEngine.execute(snapshot, FinanceQuerySpec(mode = FinanceQueryMode.CASH_MOVEMENT,
            startInclusive = 1_000, endExclusive = 3_000))
        assertTrue(cash.cash.isEmpty()); assertEquals(listOf(payment.id), cash.undatedCash.map { it.id })
    }

    @Test fun aRefundWithAnUnknownCashDateCannotReduceAnArbitraryMonthsCost() = runTest {
        val a = account(); val payment = payment(a.id, 1_000); val event = eventId(payment.id)
        val refund = finance.saveEntry(accountId = a.id, direction = FinanceDirection.INFLOW, amountMinor = 200,
            description = "真实退款但日期待确认", occurredAt = 4_000,
            event = FinanceEventInput(role = FinanceFlowRole.REFUND, existingEventId = event))
        unknown(refund.id)
        val snapshot = finance.snapshot()
        val result = FinanceQueryEngine.execute(snapshot, FinanceQuerySpec(startInclusive = 1_000, endExclusive = 5_000))
        assertEquals(PersonalCostTotal(1_000, 0), result.totals.getValue("CNY"))
        assertEquals(listOf(-200L), result.undatedCosts.map { it.amountMinor })
        assertEquals(PersonalCostTotal(800, 0), FinanceQueryEngine.execute(snapshot, FinanceQuerySpec()).totals.getValue("CNY"))
        finance.workspace.saveBudget(finance.economics.token(), "期间预算", "CNY", 2_000, null, 1_000, 5_000)
        val budget=FinanceInsightsProjection.budgets(finance.snapshot()).single()
        assertEquals(PersonalCostTotal(1_000,0),budget.used);assertEquals(1,budget.unknownDateCount)
    }

    @Test fun purposeLimitedQueriesDoNotDiscloseUnscopedUndatedRowsOrTheirCounts() = runTest {
        val a = account(); val allowed = payment(a.id, 100); val private = payment(a.id, 200)
        unknown(private.id)
        val firstGrant = finance.workspace.grant(finance.economics.token(), FinanceReadPurpose.LOCAL_QUERY,
            "local-conditions", emptySet(), setOf(allowed.id), true, true, true, 5_000)
        val spec = FinanceQuerySpec(mode = FinanceQueryMode.CASH_MOVEMENT, startInclusive = 1_000, endExclusive = 3_000)
        val restricted = FinancePurposeRead.query(finance.snapshot(), spec, firstGrant, 1_000)
        assertEquals(listOf(allowed.id), restricted.cash.map { it.id }); assertTrue(restricted.undatedCash.isEmpty())
        val explicitGrant = finance.workspace.grant(finance.economics.token(), FinanceReadPurpose.LOCAL_QUERY,
            "local-conditions", emptySet(), setOf(private.id), true, true, true, 5_000)
        val explicit = FinancePurposeRead.query(finance.snapshot(), spec, explicitGrant, 1_000)
        assertTrue(explicit.cash.isEmpty()); assertEquals(listOf(private.id), explicit.undatedCash.map { it.id })
        assertTrue(explicit.totals.isEmpty())
    }

    @Test fun savedNatureQueriesRoundTripWithAnExplicitVersionAndKeepLegacyViewsReadable() = runTest {
        val a = account(); payment(a.id, 1_000)
        val split = payment(a.id, 2_000, nature = FinanceNature.SPLIT, share = 500)
        val spec = FinanceQuerySpec(version = 2, nature = FinanceNature.SPLIT)
        val saved = finance.workspace.saveQuery(finance.economics.token(), "我的分摊", spec)
        val snapshot = finance.snapshot(); val row = snapshot.v16!!.savedQueryRows.single { it.id == saved }
        assertEquals(2, row.specVersion); assertEquals(spec, finance.workspace.queryFrom(row))
        val result = FinanceQueryEngine.execute(snapshot, spec)
        assertEquals(setOf(eventId(split.id)), result.costs.mapNotNull { it.eventId }.toSet())
        assertEquals(PersonalCostTotal(500, 0), result.totals.getValue("CNY"))
        val legacy = FinanceQuerySpec()
        assertEquals(legacy, FinanceQuerySpec.decode(com.google.gson.Gson().toJson(legacy)))
        assertTrue(runCatching { FinanceQuerySpec.decode(row.specJson.replace("SPLIT", "FUTURE_NATURE")) }.isFailure)
        assertTrue(runCatching { finance.workspace.queryFrom(row.copy(specVersion = 1)) }.isFailure)
        FinanceIntegrity.validate(snapshot)
    }
    @Test fun anUnlinkedOrdinaryCostCannotAcquireAnEconomicDateFromACashPlaceholder() = runTest {
        val a=account()
        val ordinary=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,
            amountMinor=600,description="无独立经济日期",occurredAt=2_000)
        unknown(ordinary.id)
        finance.workspace.saveBudget(finance.economics.token(),"期间预算","CNY",2_000,null,1_000,3_000)
        val snapshot=finance.snapshot()
        val result=FinanceQueryEngine.execute(snapshot,FinanceQuerySpec(startInclusive=1_000,endExclusive=3_000))
        assertTrue(result.costs.isEmpty());assertTrue(result.totals.isEmpty())
        assertEquals(listOf(ordinary.id),result.undatedCosts.map {it.entryId})
        val budget=FinanceInsightsProjection.budgets(snapshot).single()
        assertEquals(PersonalCostTotal(0,0),budget.used);assertEquals(1,budget.unknownDateCount)
        assertEquals(PersonalCostTotal(600,0),FinanceQueryEngine.execute(snapshot,FinanceQuerySpec()).totals.getValue("CNY"))
    }
    @Test fun anEventGrantDoesNotRevealAnotherEventsUndatedRefundOrItsCount() = runTest {
        val a=account();val first=payment(a.id,1_000);val second=payment(a.id,2_000)
        val firstEvent=eventId(first.id);val secondEvent=eventId(second.id)
        val allowed=finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=100,description="允许事项的退款",occurredAt=4_000,
            event=FinanceEventInput(role=FinanceFlowRole.REFUND,existingEventId=firstEvent))
        val denied=finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=200,description="私有事项的退款",occurredAt=4_000,
            event=FinanceEventInput(role=FinanceFlowRole.REFUND,existingEventId=secondEvent))
        unknown(allowed.id,denied.id)
        val grant=finance.workspace.grant(finance.economics.token(),FinanceReadPurpose.LOCAL_QUERY,"local-conditions",
            setOf(firstEvent),emptySet(),true,true,true,5_000)
        val result=FinancePurposeRead.query(finance.snapshot(),FinanceQuerySpec(startInclusive=1_000,endExclusive=5_000),grant,1_000)
        assertEquals(setOf(firstEvent),result.costs.mapNotNull {it.eventId}.toSet())
        assertEquals(listOf(allowed.id),result.undatedCosts.map {it.entryId})
        assertEquals(PersonalCostTotal(1_000,0),result.totals.getValue("CNY"))
        assertTrue(result.undatedCash.isEmpty())
    }
}
