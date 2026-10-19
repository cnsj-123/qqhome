package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceReconciliationScopeV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    private suspend fun opening()=finance.saveAccount(name="合成核对账户",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=10_000)
    private suspend fun out(account:String,amount:Long=100,at:Long=2_000)=finance.saveEntry(accountId=account,
        direction=FinanceDirection.OUTFLOW,amountMinor=amount,description="合成范围",occurredAt=at)
    private suspend fun observed(account:String,basis:FinanceBalanceBasis):FinanceObservationEntity {
        if(basis==FinanceBalanceBasis.KNOWN_POSTED) finance.workspace.setAccountPolicy(token(),account,true,
            FinanceLiabilityConvention.SIGNED,basis,null)
        val id=finance.workspace.saveObservation(token(),account,10_000,5_000,basis,FinanceTimePrecision.INSTANT,"")
        return finance.snapshot().v16!!.observationRows.single {it.id==id}
    }
    private suspend fun post(entry:FinanceEntryEntity,at:Long)=finance.workspace.setPostingDate(token(),entry.id,entry.revision,
        at,FinanceTimePrecision.INSTANT,"合成明确入账凭据")

    @Test fun unknownPostingCannotBeExcludedUsingFutureOrPreAnchorCashDate()=runTest {
        val a=opening();val o=observed(a.id,FinanceBalanceBasis.KNOWN_POSTED)
        val future=out(a.id,300,9_000)
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        assertNull(finance.snapshot().v16!!.entryTimingRows.singleOrNull {it.entryId==future.id})
        post(future,1_500) // Posting before the cash date is supported; no fallback is permitted.
        assertEquals(9_700L,FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        val preAnchor=out(a.id,200,900)
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        post(preAnchor,999) // Explicit posting evidence, not the earlier cash timestamp, excludes it.
        assertEquals(9_700L,FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
    }

    @Test fun explicitOutsidePostingAndOtherAccountUnknownDoNotInvalidateButBackfillIntoRangeDoes()=runTest {
        val a=opening();val o=observed(a.id,FinanceBalanceBasis.KNOWN_POSTED)
        val e=out(a.id,100,2_000);post(e,9_000)
        var s=finance.snapshot();val initial=FinanceAccountProjection.reconcile(s,o)
        assertEquals(10_000L,initial.bookMinor)
        finance.workspace.confirmReconciliation(token(),o.id,initial.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        val other=account("合成无关账户");out(other.id,1,1_500) // Unknown posted date on another account.
        var row=finance.snapshot().entries.single {it.id==e.id}
        finance.saveEntry(row.id,row.accountId,row.direction,200,"范围外更正",3_000,expectedRevision=row.revision)
        s=finance.snapshot()
        assertEquals(initial.fingerprint,FinanceAccountProjection.fingerprint(s,o))
        assertFalse(FinanceAccountProjection.reconcile(s,o,saved).invalidated)
        row=s.entries.single {it.id==e.id};post(row,5_000) // Inclusive observation boundary.
        s=finance.snapshot();val changed=FinanceAccountProjection.reconcile(s,o,saved)
        assertEquals(9_800L,changed.bookMinor);assertTrue(changed.invalidated)
        finance.workspace.confirmReconciliation(token(),o.id,changed.fingerprint,"合成重新核对")
        val reconfirmed=finance.snapshot().v16!!.reconciliationRows.maxBy {it.confirmationOrder}
        finance.voidEntry(e.id)
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,reconfirmed).invalidated)
    }

    @Test fun missingDateIntroducedAfterConfirmationIsIncompleteUntilExplicitOutsideEvidenceArrives()=runTest {
        val a=opening();val o=observed(a.id,FinanceBalanceBasis.KNOWN_POSTED)
        val first=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        finance.workspace.confirmReconciliation(token(),o.id,first.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        val unknown=out(a.id,100,99_000)
        var s=finance.snapshot();val unclear=FinanceAccountProjection.reconcile(s,o,saved)
        assertNull(unclear.bookMinor);assertTrue(unclear.invalidated)
        assertNull(unclear.differenceMinor)
        assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(s,a.id).trust)
        post(unknown,6_000)
        s=finance.snapshot()
        assertEquals(10_000L,FinanceAccountProjection.reconcile(s,o).bookMinor)
        assertEquals(first.fingerprint,FinanceAccountProjection.fingerprint(s,o))
        val edge=out(a.id,50,900);post(edge,a.balanceAnchorAt)
        assertEquals(9_950L,FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
    }

    @Test fun legacyScopeKeepsInclusiveAnchorAndOnlyRelevantEditsInvalidate()=runTest {
        val a=opening();val o=observed(a.id,FinanceBalanceBasis.LEGACY_CASH)
        val edge=out(a.id,100,a.balanceAnchorAt)
        val initial=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        assertEquals(9_900L,initial.bookMinor)
        finance.workspace.confirmReconciliation(token(),o.id,initial.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        out(a.id,500,999);out(a.id,600,5_001)
        assertEquals(initial.fingerprint,FinanceAccountProjection.fingerprint(finance.snapshot(),o))
        assertFalse(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved).invalidated)
        post(edge,8_000) // Posting changes are irrelevant to the legacy cash conclusion.
        assertFalse(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved).invalidated)
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved.copy(algorithmVersion=1)).invalidated)
        assertNotEquals(initial.fingerprint,FinanceAccountProjection.fingerprint(finance.snapshot(),o.copy(amountMinor=9_899)))
        val preview=finance.workspace.previewAnchor(a.id,10_000,999,false)
        finance.workspace.confirmAnchor(token(),preview)
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved).invalidated)
    }
}
