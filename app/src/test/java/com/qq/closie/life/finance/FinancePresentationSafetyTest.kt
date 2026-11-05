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
class FinancePresentationSafetyTest:FinanceRoomTest() {
    private fun token(epoch:Long=0)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    @Test fun changingChildIconPreservesInheritedFixedPolicyAndBackup()=runTest {
        val a=account()
        val entry=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,
            description="",occurredAt=2000,category="住房",subcategory="租金")
        val s=finance.snapshot();val child=s.categories.single {it.id==entry.categoryId}
        finance.workspace.setCategoryPolicy(token(),requireNotNull(child.parentId),FinanceBurden.FIXED,"home")
        val before=finance.snapshot()
        finance.workspace.setCategoryIcon(token(),child.id,"gift")
        val after=finance.snapshot()
        assertEquals(before.v16!!.categoryPolicyRows,after.v16!!.categoryPolicyRows)
        assertEquals(PersonalCostTotal(100,0),FinanceInsightsProjection.burden(after,FinanceCostProjection.rows(after))[FinanceBurden.FIXED to "CNY"])
        assertEquals(before.entries,after.entries)
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        val restored=finance.snapshot()
        assertEquals(after.v16!!.categoryPolicyRows,restored.v16!!.categoryPolicyRows)
        assertEquals(after.v16!!.categoryIconRows,restored.v16!!.categoryIconRows)
        assertEquals(PersonalCostTotal(100,0),FinanceInsightsProjection.burden(restored,FinanceCostProjection.rows(restored))[FinanceBurden.FIXED to "CNY"])
    }
    @Test fun unsetPolicyStaysUnsetAndStaleIconRevisionOrEpochCannotWrite()=runTest {
        val a=account();val entry=payment(a.id,100)
        val category=requireNotNull(entry.categoryId)
        finance.workspace.setCategoryIcon(token(),category,"food")
        val before=finance.snapshot()
        assertTrue(before.v16!!.categoryPolicyRows.isEmpty())
        assertEquals(PersonalCostTotal(100,0),FinanceInsightsProjection.burden(before,FinanceCostProjection.rows(before))[null to "CNY"])
        assertTrue(runCatching {finance.workspace.setCategoryIcon(token(),category,"home",null)}.isFailure)
        assertEquals(before,finance.snapshot())
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        val restored=finance.snapshot()
        assertTrue(runCatching {finance.workspace.setCategoryIcon(token(),category,"home",1)}.isFailure)
        assertEquals(restored,finance.snapshot())
    }
    @Test fun foreignAndUnknownOcrCannotCreateCnyCandidatesOrExtraction()=runTest {
        for(text in listOf("支付成功\n支付金额:12.34","支付成功\n¥12.34","支付成功\n￥12.34","人民币12.34 CHF","人民币12.34 USD")) {
            val fields=FinanceLocalOcrParser.parse(text)
            assertNull(fields.currencyCode);assertNull(fields.amountMinor)
            val screenshot = finance.media.createScreenshotCandidate(token(), fields) { true }
            val document=FinanceSelectedDocument("content://synthetic/receipt","image/png","a".repeat(64),123)
            val selected = finance.media.createOcrCandidate(token(), fields, FinanceProposalKind.EXPENSE, document, false)
            val snapshot = finance.snapshot()
            assertTrue(snapshot.entries.isEmpty())
            val drafts = snapshot.v16!!.reviewSessionRows.filter { it.proposalId in setOf(screenshot, selected) }
            assertEquals(2, drafts.size)
            assertTrue(drafts.all { it.amountText == "12.34" && it.accountId == null })
        }
    }
    @Test fun originalOcrTokenIsRejectedAfterActualRestore()=runTest {
        val original=finance.economics.token()
        val fields=FinanceLocalOcrParser.parse("支付成功\n人民币\n￥12.34")
        assertEquals("CNY",fields.currencyCode);assertEquals(1234L,fields.amountMinor)
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        val before=finance.snapshot()
        assertTrue(runCatching {finance.media.createScreenshotCandidate(original,fields){true}}.isFailure)
        assertEquals(before,finance.snapshot())
    }
    @Test fun explicitCnyRangeAndAmbiguousAmountsRemainConservative() {
        for(text in listOf("退款成功\n退款金额:人民币12.34","支付成功\n支付金额:CNY12.34","支付成功\n支付金额:12.34元","支付成功\n人民币\n￥12.34")) {
            val fields=FinanceLocalOcrParser.parse(text)
            assertEquals(text,"CNY",fields.currencyCode);assertEquals(text,1234L,fields.amountMinor)
        }
        assertNull(FinanceLocalOcrParser.parse("人民币\n支付成功\n￥12.34\n￥56.78").amountMinor)
    }
}
