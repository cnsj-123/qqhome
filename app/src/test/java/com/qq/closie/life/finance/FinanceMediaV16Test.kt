package com.qq.closie.life.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.LifeBackupApplier
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceMediaV16Test:FinanceRoomTest() {
    private fun token(epoch:Long=0)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    private fun document(uri:String="content://synthetic/receipt")=FinanceSelectedDocument(uri,"image/png","a".repeat(64),123)
    @Test fun oneSharedOriginalCanServeTwoFinancialTargetsAndUnlinkDoesNotDeleteIt()=runTest {
        val a=account();val one=payment(a.id,100);val two=payment(a.id,200)
        val first=finance.media.attach(token(),FinanceMediaTarget(eventId=eventId(one.id)),document(),"合成票据")
        finance.media.attach(token(),FinanceMediaTarget(eventId=eventId(two.id)),document(),"同一原件")
        val before=LifeBackupApplier.snapshot(db)
        assertEquals(1,before.mediaAssets.size);assertEquals(1,before.mediaResources.size)
        assertNull(before.mediaResources.single().managedPath)
        assertEquals(1,finance.snapshot().v16!!.mediaLinkRows.map {it.mediaAssetId}.distinct().size)
        finance.media.unlink(token(),first,1)
        assertEquals(before.mediaAssets,LifeBackupApplier.snapshot(db).mediaAssets)
        assertEquals(1,finance.snapshot().v16!!.mediaLinkRows.count {it.voidedAt==null})
        assertEquals(listOf(one,two),finance.snapshot().entries)
    }
    @Test fun olderRestoreCanRemoveSharedAssetWhileRetiringNewReferenceWithoutErasingCash()=runTest {
        val a=account();val entry=payment(a.id)
        val saved=LifeBackupApplier.snapshot(db)
        finance.media.attach(token(),FinanceMediaTarget(eventId=eventId(entry.id)),document(),"合成附件")
        LifeBackupApplier.restoreSnapshot(db,saved)
        val s=finance.snapshot();val link=s.v16!!.mediaLinkRows.single()
        assertNotNull(link.voidedAt);assertEquals(entry,s.entries.single())
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertEquals(FinanceMediaAvailability.MISSING_ASSET,finance.media.source(context,link).availability)
        FinanceIntegrity.validate(s)
    }
    @Test fun reconnectionRejectsDifferentFileAndPreservesStableMediaIdentity()=runTest {
        val a=account();val entry=payment(a.id)
        finance.media.attach(token(),FinanceMediaTarget(eventId=eventId(entry.id)),document(),"合成附件")
        val before=finance.snapshot();val row=before.v16!!.mediaLinkRows.single()
        assertTrue(runCatching {finance.media.reconnect(token(),row.id,row.revision,document().copy(sha256="b".repeat(64)))}.isFailure)
        assertEquals(before,finance.snapshot())
        finance.media.reconnect(token(),row.id,row.revision,document("content://synthetic/reselected"))
        assertEquals(row.mediaAssetId,finance.snapshot().v16!!.mediaLinkRows.single().mediaAssetId)
        assertEquals(1,LifeBackupApplier.snapshot(db).mediaAssets.size)
        assertEquals(2,LifeBackupApplier.snapshot(db).mediaResources.size)
    }
    @Test fun ocrCandidateIsDurableAndIncompleteWithoutKeepingImageOrWritingMoney()=runTest {
        val fields=FinanceLocalOcrParser.parse("订单合成\n余额 ￥99.00\n支付成功\n￥12.34\n另一个金额 ￥5.00")
        val operation=token()
        val id=finance.media.createOcrCandidate(operation,fields,FinanceProposalKind.EXPENSE,document(),false)
        assertEquals(id,finance.media.createOcrCandidate(operation,fields,FinanceProposalKind.EXPENSE,document(),false))
        val s=finance.snapshot()
        assertTrue(s.entries.isEmpty());assertTrue(s.v16!!.mediaLinkRows.isEmpty())
        assertTrue(LifeBackupApplier.snapshot(db).mediaAssets.isEmpty())
        val session=s.v16!!.reviewSessionRows.single()
        assertEquals("",session.amountText);assertEquals("",session.occurredText);assertNull(session.accountId)
        assertEquals("",s.v4!!.proposals.single().evidenceText)
        assertTrue(s.v16!!.evidenceRows.none {it.summary.contains("99.00") || it.summary.contains("订单合成")})
        assertTrue(runCatching {finance.reviews.confirm(token(),id,1,session.occurredAt)}.isFailure)
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        assertEquals(FinanceReviewState.LATER,finance.snapshot().v16!!.reviewSessionRows.single().state)
        assertTrue(finance.snapshot().entries.isEmpty())
    }
    @Test fun explicitlyRetainedOcrReceiptUsesSameMediaAndRefundCannotBecomeExpense()=runTest {
        val fields=FinanceLocalOcrParser.parse("退款成功\n退款金额：12.34\n2026-10-09 17:20")
        assertEquals(1_234L,fields.amountMinor);assertNotNull(fields.occurredAt)
        assertEquals(FinanceProposalKind.INCOME,fields.suggestedKind)
        assertTrue(runCatching {finance.media.createOcrCandidate(token(),fields,FinanceProposalKind.EXPENSE,document(),true)}.isFailure)
        val id=finance.media.createOcrCandidate(token(),fields,FinanceProposalKind.INCOME,document(),true)
        assertEquals(id,finance.snapshot().v16!!.mediaLinkRows.single().proposalId)
        assertEquals(1,LifeBackupApplier.snapshot(db).mediaAssets.size)
        assertTrue(finance.snapshot().entries.isEmpty())
    }
}
