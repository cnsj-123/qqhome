package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.shopping.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceInsightsV16Test {
    private lateinit var db:LifeDatabase
    private lateinit var cash:FinanceRepository
    private lateinit var economic:FinanceEconomicRepository
    @Before fun setup() {
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LifeDatabase::class.java).allowMainThreadQueries().build()
        cash=FinanceRepository(db){1_000};economic=cash.economics
    }
    @After fun close(){db.close();RestoreStartupGate.resetForTesting()}
    private suspend fun account(kind:FinanceAccountKind=FinanceAccountKind.BANK_CARD)=cash.saveAccount(name="合成账户",kind=kind)
    private suspend fun owned(a:FinanceAccountEntity,at:Long=1_100,stat:FinanceStatPolicy=FinanceStatPolicy.INCLUDE):String {
        val payment=cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=10_000,description="商品",occurredAt=at,event=FinanceEventInput())
        val s=cash.snapshot();val link=s.v4!!.activeLinks.single {it.entryId==payment.id}
        return economic.confirmEconomic(economic.token(),FinanceEconomicInput(eventId=link.eventId,description="商品",currencyCode="CNY",occurredAt=at,
            valueMinor=10_000,valueBasis="合成事实",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput("fund", "self",10_000,link.id,"实际付款")),
            shares=listOf(FinanceShareInput("share",10_000,"self","self","fund",FinanceShareRelation.PERSONAL_CONFIRMED)),statPolicy=stat))
    }
    @Test fun budgetsUseIndependentPolicyForBothLegacyAndEconomicFacts()=runTest {
        val a=account()
        cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=1_000,description="",occurredAt=1_100,
            event=FinanceEventInput(statPolicy=FinanceStatPolicy.EXCLUDE,budgetPolicy=FinanceBudgetPolicy.INCLUDE))
        val event=owned(a,stat=FinanceStatPolicy.EXCLUDE)
        cash.workspace.saveBudget(economic.token(),"合成预算","CNY",20_000,null,1_000,2_000)
        val s=cash.snapshot()
        assertTrue(FinanceCostProjection.rows(s).isEmpty())
        assertEquals(11_000L,FinanceInsightsProjection.budgets(s).single().used.confirmedMinor)
        assertEquals(10_000L,FinanceCostProjection.rows(s,true).filter {it.eventId==event}.sumOf {it.amountMinor ?: 0L})
    }
    @Test fun legacyPrincipalSettlementDoesNotCreateSecondCostOrDuplicateDebtOnConversion()=runTest {
        val a=account()
        val payment=cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="",occurredAt=1_100,event=FinanceEventInput())
        val s=cash.snapshot();val e=s.v4!!.events.single()
        val party=economic.saveParty(economic.token(),"合成债权人")
        val id=economic.createObligation(economic.token(),e.id,e.updatedAt,party,false,100)
        val debt=cash.snapshot().v16!!.obligationRows.single()
        economic.recordSettlement(economic.token(),id,debt.revision,FinanceCashInput(a.id,20,1_200))
        val before=cash.snapshot()
        assertEquals(100L,FinanceCostProjection.total(FinanceCostProjection.rows(before)).confirmedMinor)
        assertEquals(80L,FinanceObligationProjection.rows(before).single().openMinor)
        val link=before.v4!!.activeLinks.single {it.entryId==payment.id}
        assertTrue(runCatching {economic.confirmEconomic(economic.token(),FinanceEconomicInput(eventId=e.id,description="",currencyCode="CNY",occurredAt=1_100,
            valueMinor=100,valueBasis="转换",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput("external",party,100,basis="外部垫付")),
            shares=listOf(FinanceShareInput("debt",100,"self","self","external",FinanceShareRelation.REPAYABLE_CONFIRMED))))}.isFailure)
        assertEquals(before,cash.snapshot());assertEquals(e.id,link.eventId)
        assertTrue(FinanceInsightsProjection.diagnostics(before,40L*86_400_000).any {e.id in it.eventIds})
    }
    @Test fun purchasePartialRefundTimelinePreservesOriginalMonthAndRejectsOverAllocatedLine()=runTest {
        val a=account();val event=owned(a)
        val purchase=cash.shopping.save(economic.token(),ShoppingPurchaseInput(title="合成购买",currencyCode="CNY",occurredAt=1_100,
            lines=listOf(ShoppingLineInput("line","商品",1,10_000))))
        var revision=cash.snapshot().v16!!.economicRows.single().revision
        economic.allocatePurchase(economic.token(),"line",event,10_000,10_000,revision)
        revision=cash.snapshot().v16!!.economicRows.single().revision
        economic.recordAdjustment(economic.token(),event,revision,FinanceFlowRole.REFUND,FinanceCashInput(a.id,3_000,2_100),3_000,"line")
        val s=cash.snapshot();val cost=ShoppingCostProjection.lines(s,purchase).single()
        assertEquals(7_000L,cost.confirmedCostMinor);assertEquals(2,cost.points.size)
        assertEquals(10_000L,cost.points.first().amountMinor);assertEquals(-3_000L,cost.points.last().amountMinor)
        assertEquals(setOf(s.entries.single {it.direction==FinanceDirection.OUTFLOW}.id),cost.points.first().entryIds)
        assertEquals(setOf(s.entries.single {it.direction==FinanceDirection.INFLOW}.id),cost.points.last().entryIds)
        assertEquals(2,cost.points.map {it.sourceId}.distinct().size)
        assertEquals(10_000L,FinanceQueryEngine.execute(s,FinanceQuerySpec(startInclusive=1_000,endExclusive=2_000)).totals.getValue("CNY").confirmedMinor)
        assertTrue(runCatching {economic.recordAdjustment(economic.token(),event,s.v16!!.economicRows.single().revision,FinanceFlowRole.REFUND,
            FinanceCashInput(a.id,8_000,2_200),8_000,"line")}.isFailure)
        assertEquals(s,cash.snapshot())
    }
    @Test fun creditShortMonthUsesKnownPostingAndRepaymentIsOnlyCashMovement()=runTest {
        val a=account(FinanceAccountKind.CREDIT)
        val zone=ZoneId.of("Asia/Shanghai")
        fun at(month:Int,day:Int)=YearMonth.of(2026,month).atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val e=cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="",occurredAt=at(2,10))
        cash.workspace.saveCreditCycle(economic.token(),a.id,31,5,zone.id)
        assertEquals(1,FinanceAccountStatusProjection.statement(cash.snapshot(),a.id,YearMonth.of(2026,2)).unknownPostingCount)
        val civil=FinanceCivilDate.capture(java.time.LocalDate.of(2026,2,28),zone)
        cash.workspace.setPostingDate(economic.token(),e.id,e.revision,at(2,28),FinanceTimePrecision.DATE,"确认入账",civil)
        val statement=FinanceAccountStatusProjection.statement(cash.snapshot(),a.id,YearMonth.of(2026,2))
        assertEquals(100L,statement.outflowMinor);assertEquals(at(3,5),statement.dueAt)
        assertEquals(at(3,1),statement.range.endExclusive)
        assertEquals(at(2,1),statement.range.startInclusive)
        assertEquals(listOf(e.id),statement.entries.map {it.id})
        assertEquals(0,statement.unknownPostingCount)
        assertTrue(FinanceAccountStatusProjection.statement(cash.snapshot(),a.id,YearMonth.of(2026,3)).entries.isEmpty())
        val bank=account()
        val payment=cash.saveTransfer(sourceAccountId=bank.id,targetAccountId=a.id,amountMinor=100,
            description="实际还款",occurredAt=at(3,2))
        val s=cash.snapshot()
        assertEquals(2,s.entries.count {it.id in setOf(payment.outflowEntryId,payment.inflowEntryId)})
        assertEquals(1,s.transfers.size)
        assertEquals(100L,FinanceCostProjection.total(FinanceCostProjection.rows(s)).confirmedMinor)
        assertEquals(1,FinanceAccountStatusProjection.statement(s,a.id,YearMonth.of(2026,2)).unknownPostingCount)
        assertEquals(100L,FinanceAccountStatusProjection.statement(s,a.id,YearMonth.of(2026,2)).outflowMinor)
    }
    @Test fun explicitQueryNeverBroadensUnknownIdsAndLocalLanguageIsFullyConsumed()=runTest {
        val a=account();owned(a)
        val s=cash.snapshot()
        assertTrue(runCatching {FinanceQueryEngine.execute(s,FinanceQuerySpec(categoryIds=setOf("missing")))}.isFailure)
        assertTrue(runCatching {FinanceQueryEngine.execute(s,FinanceQuerySpec(tagIds=setOf("missing")))}.isFailure)
        assertTrue(runCatching {FinanceLocalQueryParser.parse("全部 删除所有记录 个人成本",s)}.isFailure)
        assertEquals(FinanceQueryMode.CASH_MOVEMENT,FinanceLocalQueryParser.parse("全部 真实流水",s).mode)
    }
    @Test fun ordinaryRelinkCannotBypassTypedRefundAndEditingKeepsConfirmedAllocationIdentity()=runTest {
        val a=account();val event=owned(a)
        val income=cash.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=100,description="到账",occurredAt=2_100,event=FinanceEventInput(role=FinanceFlowRole.OTHER))
        val before=cash.snapshot()
        assertTrue(runCatching {cash.allocateEntry(income.id,listOf(FinanceAllocation(event,FinanceFlowRole.REFUND,100)),income.revision)}.isFailure)
        assertEquals(before,cash.snapshot())
        val refund=economic.recordAdjustment(economic.token(),event,before.v16!!.economicRows.single().revision,
            FinanceFlowRole.REFUND,FinanceCashInput(a.id,100,2_200),100)
        val saved=cash.snapshot();val row=saved.entries.single {it.id==refund};val link=saved.v4!!.activeLinks.single {it.entryId==refund}
        cash.saveEntry(row.id,a.id,row.direction,row.amountMinor,"更新备注",row.occurredAt,event=FinanceEventInput(existingEventId=event,role=FinanceFlowRole.REFUND),expectedRevision=row.revision)
        val after=cash.snapshot()
        assertEquals(link.id,after.v4!!.activeLinks.single {it.entryId==refund}.id)
        assertEquals(9_900L,FinanceCostProjection.eventTotal(after,event).confirmedMinor)
    }
    @Test fun assigningAlreadyReceivedRefundChangesCostWithoutAddingOrRewritingCash()=runTest {
        val a=account();val event=owned(a)
        val receipt=cash.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=3_000,description="原到账备注",occurredAt=2_100,event=FinanceEventInput(role=FinanceFlowRole.OTHER))
        val before=cash.snapshot();val balance=FinanceProjection.balance(a,before.entries)
        economic.adjustExistingCash(economic.token(),event,before.v16!!.economicRows.single().revision,receipt.id,receipt.revision,
            FinanceFlowRole.REFUND,3_000)
        val after=cash.snapshot();val updated=after.entries.single {it.id==receipt.id}
        assertEquals(before.entries.size,after.entries.size);assertEquals(balance,FinanceProjection.balance(a,after.entries))
        assertEquals(receipt.amountMinor,updated.amountMinor);assertEquals(receipt.occurredAt,updated.occurredAt)
        assertEquals(receipt.recordedAt,updated.recordedAt);assertEquals(receipt.description,updated.description)
        assertEquals(7_000L,FinanceCostProjection.eventTotal(after,event).confirmedMinor)
    }
}
