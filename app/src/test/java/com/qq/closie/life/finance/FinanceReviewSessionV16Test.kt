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
class FinanceReviewSessionV16Test:FinanceRoomTest() {
    private fun token(epoch:Long=0)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    private suspend fun proposal(key:String="synthetic")=FinanceAutomationRepository(db,finance).discover(
        FinanceNotificationParser.parse("com.tencent.mm",key,"微信支付","支付成功 ￥12.34",System.currentTimeMillis())!!)!!
    private fun input(p:FinanceProposalEntity,account:String)=FinanceReviewSessionEntity(p.id,p.revision,0,"12.34",account,"","","未完成的中文备注",
        FinanceReviewState.EDITING,occurredAt=p.occurredAt,occurredText="未完成日期",createdAt=0,updatedAt=0)

    @Test fun durablePartialEditsResumeLaterWithoutMoneyAndSecondProposalDoesNotReplaceThem()=runTest {
        val a=account();val p=proposal();val row=input(p,a.id)
        finance.reviews.save(token(),row,null,emptyList())
        proposal("synthetic-next")
        val recreated=FinanceRepository(db){clock}
        assertEquals(row.description,recreated.snapshot().v16!!.reviewSessionRows.single().description)
        assertTrue(recreated.snapshot().entries.isEmpty())
        recreated.reviews.save(token(),row,1,emptyList(),later=true)
        val saved=recreated.snapshot().v16!!.reviewSessionRows.single()
        assertEquals(FinanceReviewState.LATER,saved.state)
        assertEquals(db.financeIntakeDao().proposal(p.id)!!.revision,saved.baseRevision)
        assertEquals(FinanceProposalStatus.SNOOZED,db.financeIntakeDao().proposal(p.id)!!.status)
        assertTrue(recreated.snapshot().entries.isEmpty())
        FinanceIntegrity.validate(recreated.snapshot())
    }
    @Test fun explicitConfirmationIsAtomicAndReplayDoesNotProduceSecondEntry()=runTest {
        val a=account();val p=proposal();val row=input(p,a.id).copy(description="")
        finance.reviews.save(token(),row,null,emptyList())
        val confirm=token()
        val id=finance.reviews.confirm(confirm,p.id,1,p.occurredAt)
        assertEquals(id,finance.reviews.confirm(confirm,p.id,1,p.occurredAt))
        val s=finance.snapshot()
        assertEquals(1,s.entries.size);assertEquals("",s.entries.single().description)
        assertEquals(FinanceReviewState.COMPLETED,s.v16!!.reviewSessionRows.single().state)
        assertEquals(FinanceProposalStatus.CONFIRMED,db.financeIntakeDao().proposal(p.id)!!.status)
        assertTrue(runCatching {finance.reviews.confirm(token(),p.id,1,p.occurredAt)}.isFailure)
        // Existing manual commands must continue to validate typed session references.
        payment(a.id,amount=500)
        FinanceIntegrity.validate(finance.snapshot())
    }
    @Test fun staleEditorAndRestoreCannotConfirmOldCandidateOrReceipt()=runTest {
        val a=account();val p=proposal();val row=input(p,a.id)
        val save=token();finance.reviews.save(save,row,null,emptyList())
        assertTrue(runCatching {finance.reviews.save(token(),row.copy(description="竞争编辑"),null,emptyList())}.isFailure)
        val saved=LifeBackupApplier.snapshot(db)
        LifeBackupApplier.restoreSnapshot(db,saved)
        val before=finance.snapshot()
        assertTrue(runCatching {finance.reviews.confirm(token(),p.id,1,p.occurredAt)}.isFailure)
        assertTrue(runCatching {finance.reviews.save(save,row,null,emptyList())}.isFailure)
        assertEquals(before,finance.snapshot());assertTrue(before.entries.isEmpty())
    }
    @Test fun competingSurfaceConfirmationCannotPretendSavedEditsWerePosted()=runTest {
        val a=account();val p=proposal();val row=input(p,a.id)
        finance.reviews.save(token(),row,null,emptyList())
        FinanceAutomationRepository(db,finance).confirm(p.id,p.revision,FinanceProposalConfirmation(FinanceProposalKind.EXPENSE,
            a.id,null,500,"另一表面确认",p.occurredAt,"","",emptyList(),FinanceEventInput()))
        val before=finance.snapshot()
        assertTrue(runCatching {finance.reviews.confirm(token(),p.id,1,p.occurredAt)}.isFailure)
        assertEquals(before,finance.snapshot())
        assertEquals(500L,before.entries.single().amountMinor)
        assertEquals(FinanceReviewState.EDITING,before.v16!!.reviewSessionRows.single().state)
    }
    @Test fun typedRefundRequiresExplicitReductionAndCreatesCashAndCostTogether()=runTest {
        val a=account();val paid=payment(a.id,100)
        val event=eventId(paid.id);val link=finance.snapshot().v4!!.activeLinks.single {it.entryId==paid.id}
        finance.economics.confirmEconomic(token(),FinanceEconomicInput(eventId=event,description="合成购买",currencyCode="CNY",occurredAt=2_000,
            valueMinor=100,valueBasis="确认",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput("fund","self",100,link.id,"实际付款")),
            shares=listOf(FinanceShareInput("share",100,"self","self","fund",FinanceShareRelation.PERSONAL_CONFIRMED))))
        val p=proposal("synthetic-refund")
        val draft=input(p,a.id).copy(kind=FinanceDraftKind.INCOME,amountText="0.20",eventId=event,role=FinanceFlowRole.REFUND,
            eventUpdatedAt=finance.snapshot().v4!!.events.single {it.id==event}.updatedAt)
        finance.reviews.save(token(),draft,null,emptyList())
        val before=finance.snapshot()
        assertTrue(runCatching {finance.reviews.confirm(token(),p.id,1,p.occurredAt)}.isFailure)
        assertEquals(before,finance.snapshot())
        finance.reviews.save(token(),draft.copy(costReductionText="0.20"),1,emptyList())
        finance.reviews.confirm(token(),p.id,2,p.occurredAt)
        val after=finance.snapshot()
        assertEquals(80L,FinanceCostProjection.eventTotal(after,event).confirmedMinor)
        assertEquals(20L,after.v16!!.costAdjustmentRows.single().personalReductionMinor)
        assertEquals(2,after.entries.size)
    }
    @Test fun linkingAnAlreadyRecordedEntryOnlyAddsProvenanceAndNeverChangesCanonicalCash()=runTest {
        val a=account();val original=payment(a.id,1_234)
        val p=proposal()
        finance.reviews.save(token(),input(p,a.id).copy(description="来源小票中的新备注"),null,emptyList())
        val operation=token()
        assertEquals(original.id,finance.reviews.linkExistingEntry(operation,p.id,1,original.id,original.revision))
        assertEquals(original.id,finance.reviews.linkExistingEntry(operation,p.id,1,original.id,original.revision))
        val after=finance.snapshot()
        assertEquals(listOf(original),after.entries)
        assertEquals(original.id,db.financeIntakeDao().proposal(p.id)!!.canonicalId)
    }
    @Test fun visibleRefundCannotBeLinkedAsTransferOrAsUnrelatedOrdinaryIncome()=runTest {
        val a=account();val b=account("合成B")
        val incoming=finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=1_234,description="普通收入",occurredAt=2_000,
            event=FinanceEventInput(role=FinanceFlowRole.OTHER))
        val transfer=finance.saveTransfer(sourceAccountId=a.id,targetAccountId=b.id,amountMinor=1_234,description="合成转账",occurredAt=2_000)
        val p=FinanceAutomationRepository(db,finance).discoverVisible(NormalizedTransactionEvidence("wechat",1_234,"CNY",FinanceProposalKind.INCOME,
            1_700_000_000_000,1_700_000_001_000,"a".repeat(64),"synthetic",role=FinanceFlowRole.REFUND,referenceKind=FinanceReferenceKind.NONE)){true}.proposal!!
        val row=input(p,a.id).copy(kind=FinanceDraftKind.TRANSFER,targetAccountId=b.id,role=FinanceFlowRole.REFUND)
        finance.reviews.save(token(),row,null,emptyList())
        assertTrue(runCatching {finance.reviews.linkExistingTransfer(token(),p.id,1,transfer.id,transfer.updatedAt)}.isFailure)
        finance.reviews.save(token(),row.copy(kind=FinanceDraftKind.INCOME),1,emptyList())
        val before=finance.snapshot()
        assertTrue(runCatching {finance.reviews.linkExistingEntry(token(),p.id,2,incoming.id,incoming.revision)}.isFailure)
        assertEquals(before,finance.snapshot())
    }
    @Test fun cnyCandidateCannotBeLinkedToAnotherCurrency()=runTest {
        val usd=finance.saveAccount(name="合成美元",kind=FinanceAccountKind.CASH,currencyCode="USD")
        val original=payment(usd.id,1_234);val p=proposal()
        finance.reviews.save(token(),input(p,usd.id),null,emptyList())
        assertTrue(runCatching {finance.reviews.linkExistingEntry(token(),p.id,1,original.id,original.revision)}.isFailure)
        assertEquals(FinanceProposalStatus.ACTIVE,db.financeIntakeDao().proposal(p.id)!!.status)
    }
}
