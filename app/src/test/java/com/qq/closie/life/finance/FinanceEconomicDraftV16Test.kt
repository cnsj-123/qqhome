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
class FinanceEconomicDraftV16Test:FinanceRoomTest() {
    private fun token(epoch:Long=0)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    private fun draft(id:String="draft")=FinanceEconomicDraftEntity(id=id,epoch=0,description="未写完中文",currencyCode="CNY",occurredText="日期未填完",
        valueText="12.",valueBasis="",allocationsComplete=false,statExcluded=false,budgetExcluded=false,createdAt=0,updatedAt=0)
    @Test fun incompleteDraftSurvivesRecreationAndBackupWithoutCreatingFinancialFacts()=runTest {
        val input=draft()
        val fund=FinanceEconomicDraftFundingEntity(input.id,"fund",0,"尚未选择",".",null,"")
        val share=FinanceEconomicDraftShareEntity(input.id,"share",0,"","self","","fund",FinanceShareRelation.UNRESOLVED)
        finance.economicDrafts.save(token(),input,listOf(fund),listOf(share),null)
        val before=finance.snapshot()
        assertTrue(before.entries.isEmpty());assertTrue(before.v4!!.events.isEmpty());assertTrue(before.v16!!.shareRows.isEmpty())
        val recreated=FinanceRepository(db){clock}.snapshot()
        assertEquals("12.",recreated.v16!!.economicDraftRows.single().valueText)
        assertEquals(listOf(fund),recreated.v16!!.economicDraftFundingRows)
        assertTrue(runCatching {finance.economicDrafts.commit(token(),input.id,1)}.isFailure)
        assertEquals(before,finance.snapshot())
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        val restored=finance.snapshot();assertEquals(listOf(share),restored.v16!!.economicDraftShareRows)
        assertTrue(runCatching {finance.economicDrafts.commit(token(),input.id,1)}.isFailure)
        finance.economicDrafts.save(token(1),input.copy(epoch=1),listOf(fund),listOf(share),1)
        assertTrue(finance.snapshot().entries.isEmpty())
    }
    @Test fun explicitCommitConsumesExactDraftVersionAndReplayCreatesOnlyOneNonCashEvent()=runTest {
        val input=draft().copy(occurredText=FinanceTime.input(2_000),preservedOccurredAt=2_000,valueText="",valueBasis="合成依据")
        finance.economicDrafts.save(token(),input,emptyList(),emptyList(),null)
        val confirm=token()
        val event=finance.economicDrafts.commit(confirm,input.id,1)
        assertEquals(event,finance.economicDrafts.commit(confirm,input.id,1))
        val s=finance.snapshot()
        assertEquals(1,s.v4!!.events.size);assertTrue(s.entries.isEmpty())
        assertNotNull(s.v16!!.economicDraftRows.single().voidedAt)
        assertFalse(FinanceCostProjection.eventTotal(s,event).complete)
        assertTrue(runCatching {finance.economicDrafts.commit(token(),input.id,1)}.isFailure)
    }
    @Test fun legacyEventChangingAfterDraftStartsRejectsConversionAndKeepsDraft()=runTest {
        val a=account();val entry=payment(a.id,100)
        val event=finance.snapshot().v4!!.events.single()
        val input=draft().copy(eventId=event.id,eventVersion=event.updatedAt,occurredText=FinanceTime.input(event.occurredAt),valueText="",valueBasis="合成确认")
        finance.economicDrafts.save(token(),input,emptyList(),emptyList(),null)
        finance.saveEntry(entry.id,a.id,entry.direction,entry.amountMinor,"事项已变化",entry.occurredAt,
            event=FinanceEventInput(existingEventId=event.id,expectedEventUpdatedAt=event.updatedAt),expectedRevision=entry.revision)
        val before=finance.snapshot()
        assertTrue(runCatching {finance.economicDrafts.commit(token(),input.id,1)}.isFailure)
        assertEquals(before,finance.snapshot());assertNull(before.v16!!.economicDraftRows.single().voidedAt)
    }
    @Test fun explicitReimbursementTemplateKeeps500Cash50CostAnd450ReceivableThroughTwoReceipts()=runTest {
        val a=account();val paid=payment(a.id,50_000,FinanceNature.REIMBURSABLE,5_000)
        val s=finance.snapshot();val event=s.v4!!.events.single();val link=s.v4!!.activeLinks.single()
        val company=finance.economics.saveParty(token(),"合成报销方")
        val input=draft().copy(eventId=event.id,eventVersion=event.updatedAt,occurredText=FinanceTime.input(event.occurredAt),
            preservedOccurredAt=event.occurredAt,valueText="500",valueBasis="合成报销确认",allocationsComplete=true)
        val funding=listOf(FinanceEconomicDraftFundingEntity(input.id,"fund",0,"self","500",link.id,"已有本人实际垫付"))
        val shares=FinanceEconomicDraftTemplates.reimbursement(input.id,funding,5_000,company,"CNY")
        finance.economicDrafts.save(token(),input,funding,shares,null)
        finance.economicDrafts.commit(token(),input.id,1)
        assertEquals(5_000L,FinanceCostProjection.eventTotal(finance.snapshot(),event.id).confirmedMinor)
        var debt=FinanceObligationProjection.rows(finance.snapshot()).single()
        assertEquals(45_000L,debt.openMinor)
        finance.economics.recordSettlement(token(),debt.obligation.id,debt.obligation.revision,FinanceCashInput(a.id,30_000,3_000))
        debt=FinanceObligationProjection.rows(finance.snapshot()).single()
        assertEquals(15_000L,debt.openMinor)
        finance.economics.recordSettlement(token(),debt.obligation.id,debt.obligation.revision,FinanceCashInput(a.id,15_000,4_000))
        val after=finance.snapshot()
        assertEquals(0L,FinanceObligationProjection.rows(after).single().openMinor)
        assertEquals(5_000L,FinanceCostProjection.eventTotal(after,event.id).confirmedMinor)
        assertEquals(-5_000L,FinanceProjection.balance(a,after.entries))
        assertEquals(paid.amountMinor,after.entries.single {it.id==paid.id}.amountMinor)
        assertEquals(3,after.entries.size)
    }
    @Test fun reimbursementSplitsAcrossFundingAndRejectsTooManySharesBeforeReplacingDraft() {
        fun fund(index:Int,amount:String)=FinanceEconomicDraftFundingEntity("draft","fund$index",index,"self",amount,"link$index","")
        val split=FinanceEconomicDraftTemplates.reimbursement("draft",listOf(fund(0,"100"),fund(1,"400")),15_000,"company","CNY")
        assertEquals(3,split.size)
        assertEquals(15_000L,exactSum(split.filter {it.relation==FinanceShareRelation.PERSONAL_CONFIRMED}.map {FinanceMoney.parse(it.amountText)}))
        assertEquals(35_000L,exactSum(split.filter {it.relation==FinanceShareRelation.REPAYABLE_CONFIRMED}.map {FinanceMoney.parse(it.amountText)}))
        val many=(0 until 100).map {fund(it,"1")}
        assertTrue(runCatching {FinanceEconomicDraftTemplates.reimbursement("draft",many,50,"company","CNY")}.isFailure)
        assertEquals(100,FinanceEconomicDraftTemplates.reimbursement("draft",many,100,"company","CNY").size)
    }
}
