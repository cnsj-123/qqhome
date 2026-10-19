package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceProvenanceV16Test:FinanceRoomTest() {
    private val automation get()=FinanceAutomationRepository(db,finance)
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    private fun evidence(amount:Long=100,kind:FinanceReferenceKind=FinanceReferenceKind.PAYMENT)=NormalizedTransactionEvidence(
        "wechat",amount,"CNY",FinanceProposalKind.EXPENSE,1_700_000_000_000,1_700_000_001_000,"a".repeat(64),"synthetic-v1",referenceKind=kind)
    @Test fun sameOrderDoesNotCollapseDistinctPaymentsAndDiscoveryNeverCreatesCash()=runTest {
        automation.discoverVisible(evidence(100,FinanceReferenceKind.ORDER)){true}
        automation.discoverVisible(evidence(200,FinanceReferenceKind.ORDER)){true}
        val s=finance.snapshot()
        assertTrue(s.entries.isEmpty());assertEquals(2,s.v16!!.extractionRows.size)
        assertEquals(2,s.v16!!.evidenceRows.size)
        assertTrue(FinanceEvidenceCorrelation.blocking(s.v4!!.proposals.first(),s.v4!!.proposals,s.v16).isEmpty())
        assertTrue(s.v16!!.evidenceRows.all {it.identityId==null && it.referenceKind==FinanceReferenceKind.ORDER})
    }
    @Test fun explicitDistinctIdentitiesResolvePotentialReferenceCollisionAndBindingIsDated()=runTest {
        val a=account("合成A");val b=account("合成B")
        val first=automation.discoverVisible(evidence()){true}.proposal!!
        val second=automation.discoverVisible(evidence(200)){true}.proposal!!
        var s=finance.snapshot()
        assertEquals(1,FinanceEvidenceCorrelation.blocking(first,s.v4!!.proposals,s.v16).size)
        val p=finance.provenance
        val ia=p.createIdentity(token(),"wechat","合成身份A")
        val ib=p.createIdentity(token(),"wechat","合成身份B")
        val instrument=p.createInstrument(token(),ia,"合成零钱",PaymentChannelHint.WECHAT_BALANCE.name)
        val binding=p.bindInstrument(token(),instrument,a.id,1_000,null)
        fun source(proposal:String)=s.v16!!.evidenceRows.single {row->s.v16!!.evidenceProposalRows.any {it.proposalId==proposal && it.evidenceId==row.id}}
        var e=source(first.id);p.classify(token(),first.id,first.revision,e.id,e.revision,ia,instrument)
        s=finance.snapshot();e=source(second.id);p.classify(token(),second.id,second.revision,e.id,e.revision,ib,null)
        s=finance.snapshot()
        assertTrue(FinanceEvidenceCorrelation.blocking(s.v4!!.proposals.first(),s.v4!!.proposals,s.v16).isEmpty())
        assertEquals(a.id,s.v4!!.proposals.single {it.id==first.id}.accountId)
        val captured=s.v16!!.evidenceRows.single {it.identityId==ia}
        assertEquals(binding,captured.bindingId);assertEquals(1L,captured.bindingRevision)
        p.bindInstrument(token(),instrument,b.id,1_800_000_000_000,binding)
        val after=finance.snapshot()
        assertEquals(a.id,after.v16!!.accountBindingRows.single {it.id==binding}.accountId)
        assertEquals(1_800_000_000_000L,after.v16!!.accountBindingRows.single {it.id==binding}.validUntil)
        assertEquals(captured,after.v16!!.evidenceRows.single {it.id==captured.id})
        FinanceIntegrity.validate(after)
    }
    @Test fun restoredIdentitiesCannotDisambiguateUntilExplicitlyApproved()=runTest {
        val p=finance.provenance
        val a=p.createIdentity(token(),"wechat","合成A");val b=p.createIdentity(token(),"wechat","合成B")
        val first=automation.discoverVisible(evidence()){true}.proposal!!
        val second=automation.discoverVisible(evidence(200)){true}.proposal!!
        for((proposal,identity) in listOf(first to a,second to b)) {
            val s=finance.snapshot();val e=s.v16!!.evidenceRows.single {row->s.v16!!.evidenceProposalRows.any {it.evidenceId==row.id && it.proposalId==proposal.id}}
            p.classify(token(),proposal.id,proposal.revision,e.id,e.revision,identity,null)
        }
        com.qq.closie.data.backup.LifeBackupApplier.restoreSnapshot(db,com.qq.closie.data.backup.LifeBackupApplier.snapshot(db))
        val restored=finance.snapshot()
        assertTrue(restored.v16!!.externalIdentityRows.all {!it.matchUsable})
        assertEquals(1,FinanceEvidenceCorrelation.blocking(restored.v4!!.proposals.first(),restored.v4!!.proposals,restored.v16).size)
        assertNull(FinanceEvidenceCorrelation.reference(restored.v4!!.proposals.first(),restored.v16))
    }
    @Test fun olderRestoreRetiresEvidenceWithNewerIdentityAndBindingProofVersions()=runTest {
        val a=account();val b=account("合成B");val p=finance.provenance
        val identity=p.createIdentity(token(),"wechat","合成身份")
        val instrument=p.createInstrument(token(),identity,"合成工具",PaymentChannelHint.BANK_CARD.name)
        val binding=p.bindInstrument(token(),instrument,a.id,1_000,null)
        val saved=com.qq.closie.data.backup.LifeBackupApplier.snapshot(db)
        p.approveRestoredIdentity(token(),identity,1)
        p.bindInstrument(token(),instrument,b.id,1_800_000_000_000,binding)
        val proposal=automation.discoverVisible(evidence()){true}.proposal!!
        val e=finance.snapshot().v16!!.evidenceRows.single()
        p.classify(token(),proposal.id,proposal.revision,e.id,e.revision,identity,instrument)
        assertEquals(2L,finance.snapshot().v16!!.evidenceRows.single().bindingRevision)
        com.qq.closie.data.backup.LifeBackupApplier.restoreSnapshot(db,saved)
        val after=finance.snapshot();FinanceIntegrity.validate(after)
        assertNotNull(after.v16!!.evidenceRows.single().voidedAt)
        assertEquals(1L,after.v16!!.externalIdentityRows.single().revision)
    }
    @Test fun finalEditedDateMustBelongToCapturedBindingAndExplicitAdoptionUpdatesSession()=runTest {
        val a=account();val p=finance.provenance
        val identity=p.createIdentity(token(),"wechat","合成身份")
        val instrument=p.createInstrument(token(),identity,"合成工具",PaymentChannelHint.BANK_CARD.name)
        val binding=p.bindInstrument(token(),instrument,a.id,1_000,null)
        val proposal=automation.discoverVisible(evidence()){true}.proposal!!
        finance.reviews.save(token(),FinanceReviewSessionEntity(proposal.id,proposal.revision,0,"1.00",null,"","","",FinanceReviewState.EDITING,
            occurredAt=proposal.occurredAt,occurredText=FinanceTime.input(proposal.occurredAt),createdAt=0,updatedAt=0),null,emptyList())
        val e=finance.snapshot().v16!!.evidenceRows.single()
        p.classify(token(),proposal.id,proposal.revision,e.id,e.revision,identity,instrument,true)
        assertEquals(a.id,finance.snapshot().v16!!.reviewSessionRows.single().accountId)
        p.unbind(token(),binding,1,1_800_000_000_000)
        // Reclassify with the new closed interval's revision, then edit outside that interval.
        val s=finance.snapshot();val current=s.v4!!.proposals.single();val proof=s.v16!!.evidenceRows.single()
        p.classify(token(),current.id,current.revision,proof.id,proof.revision,identity,instrument,true)
        assertTrue(runCatching {p.validateAccountSuggestion(proposal.id,a.id,1_800_000_000_001)}.isFailure)
        p.validateAccountSuggestion(proposal.id,a.id,proposal.occurredAt)
    }
}
