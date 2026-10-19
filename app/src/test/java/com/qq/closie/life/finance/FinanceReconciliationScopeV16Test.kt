package com.qq.closie.life.finance

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
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
    private suspend fun post(entry:FinanceEntryEntity,at:Long,precision:FinanceTimePrecision=FinanceTimePrecision.INSTANT)=
        finance.workspace.setPostingDate(token(),entry.id,entry.revision,at,precision,"合成明确入账凭据",
            if(precision==FinanceTimePrecision.DATE) FinanceCivilDate.capture(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(),ZoneId.systemDefault()) else null)
    private fun moment(day:Int,hour:Int=0)=LocalDate.of(2026,10,day).atTime(hour,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private suspend fun precisionAccount(anchor:Long):FinanceAccountEntity {
        clock=moment(20)
        val a=finance.saveAccount(name="合成精度账户",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=10_000,balanceAnchorAt=anchor)
        finance.workspace.setAccountPolicy(token(),a.id,true,FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.KNOWN_POSTED,null)
        return a
    }
    private suspend fun precisionObservation(account:String,at:Long,precision:FinanceTimePrecision):FinanceObservationEntity {
        val id=finance.workspace.saveObservation(token(),account,10_000,at,FinanceBalanceBasis.KNOWN_POSTED,precision,"",
            if(precision==FinanceTimePrecision.DATE) FinanceCivilDate.capture(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(),ZoneId.systemDefault()) else null)
        return finance.snapshot().v16!!.observationRows.single {it.id==id}
    }

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
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved.copy(algorithmVersion=2)).invalidated)
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved.copy(algorithmVersion=3)).invalidated)
        assertNotEquals(initial.fingerprint,FinanceAccountProjection.fingerprint(finance.snapshot(),o.copy(amountMinor=9_899)))
        val preview=finance.workspace.previewAnchor(a.id,10_000,999,false)
        finance.workspace.confirmAnchor(token(),preview)
        assertTrue(FinanceAccountProjection.reconcile(finance.snapshot(),o,saved).invalidated)
    }

    @Test fun postingPrecisionAloneCanMakeSameTimestampIncompleteAndInvalidateReconfirmation()=runTest {
        val a=precisionAccount(moment(9))
        val o=precisionObservation(a.id,moment(10,12),FinanceTimePrecision.INSTANT)
        val e=out(a.id,100,moment(18));post(e,moment(10),FinanceTimePrecision.INSTANT)
        var s=finance.snapshot();val exact=FinanceAccountProjection.reconcile(s,o)
        assertEquals(9_900L,exact.bookMinor)
        finance.workspace.confirmReconciliation(token(),o.id,exact.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        post(finance.snapshot().entries.single(),moment(10),FinanceTimePrecision.DATE)
        s=finance.snapshot();val ambiguous=FinanceAccountProjection.reconcile(s,o,saved)
        assertNull(ambiguous.bookMinor);assertNull(ambiguous.differenceMinor);assertTrue(ambiguous.invalidated)
        assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(s,a.id).trust)
        assertNotEquals(exact.fingerprint,ambiguous.fingerprint)
        finance.workspace.confirmReconciliation(token(),o.id,ambiguous.fingerprint,"仅保留资料不足")
        val incompleteSaved=finance.snapshot().v16!!.reconciliationRows.maxBy {it.confirmationOrder}
        post(finance.snapshot().entries.single(),moment(10),FinanceTimePrecision.INSTANT)
        val resolved=FinanceAccountProjection.reconcile(finance.snapshot(),o,incompleteSaved)
        assertEquals(9_900L,resolved.bookMinor);assertTrue(resolved.invalidated)
        finance.workspace.confirmReconciliation(token(),o.id,resolved.fingerprint,"重新明确时点")
        s=finance.snapshot();val latest=s.v16!!.reconciliationRows.maxBy {it.confirmationOrder}
        assertEquals(FinanceAccountProjection.RECONCILIATION_ALGORITHM,latest.algorithmVersion)
        assertFalse(FinanceAccountProjection.reconcile(s,o,latest).invalidated)
    }

    @Test fun dateObservationCannotOrderSameDayPostingButADisjointPriorDayIsCertain()=runTest {
        val a=precisionAccount(moment(8))
        val date=precisionObservation(a.id,moment(10,12),FinanceTimePrecision.DATE)
        val exact=precisionObservation(a.id,moment(10,12),FinanceTimePrecision.INSTANT)
        val e=out(a.id,100,moment(18));post(e,moment(10,1))
        var s=finance.snapshot()
        assertNull(FinanceAccountProjection.reconcile(s,date).bookMinor)
        assertEquals(9_900L,FinanceAccountProjection.reconcile(s,exact).bookMinor)
        assertNotEquals(FinanceAccountProjection.fingerprint(s,date),FinanceAccountProjection.fingerprint(s,exact))
        post(s.entries.single(),moment(10),FinanceTimePrecision.DATE)
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),date).bookMinor)
        post(finance.snapshot().entries.single(),moment(9),FinanceTimePrecision.DATE)
        s=finance.snapshot()
        assertEquals(9_900L,FinanceAccountProjection.reconcile(s,date).bookMinor)
        assertEquals(9_900L,FinanceAccountProjection.reconcile(s,exact).bookMinor)
    }

    @Test fun datePostingOverlappingAnchorIsUnknownAndOnlyWholeOutsideDaysCanBeExcluded()=runTest {
        val a=precisionAccount(moment(10,12))
        val o=precisionObservation(a.id,moment(12,12),FinanceTimePrecision.INSTANT)
        val e=out(a.id,100,moment(18));post(e,moment(10),FinanceTimePrecision.DATE)
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        post(finance.snapshot().entries.single(),moment(9),FinanceTimePrecision.DATE)
        val before=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        assertEquals(10_000L,before.bookMinor)
        post(finance.snapshot().entries.single(),moment(13),FinanceTimePrecision.DATE)
        val after=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        assertEquals(10_000L,after.bookMinor);assertEquals(before.fingerprint,after.fingerprint)
        post(finance.snapshot().entries.single(),moment(11),FinanceTimePrecision.DATE)
        val included=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        assertEquals(9_900L,included.bookMinor)
        finance.workspace.confirmReconciliation(token(),o.id,included.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        post(finance.snapshot().entries.single(),moment(11),FinanceTimePrecision.INSTANT)
        val changed=FinanceAccountProjection.reconcile(finance.snapshot(),o,saved)
        assertEquals(9_900L,changed.bookMinor);assertTrue(changed.invalidated)
    }

    @Test fun dateObservationOverlappingAnchorRemainsIncompleteEvenWithoutEntries()=runTest {
        val a=precisionAccount(moment(10,12))
        val date=precisionObservation(a.id,moment(10,15),FinanceTimePrecision.DATE)
        val exact=precisionObservation(a.id,moment(10,15),FinanceTimePrecision.INSTANT)
        val later=precisionObservation(a.id,moment(11),FinanceTimePrecision.DATE)
        val s=finance.snapshot()
        assertNull(FinanceAccountProjection.reconcile(s,date).bookMinor)
        assertEquals(10_000L,FinanceAccountProjection.reconcile(s,exact).bookMinor)
        assertEquals(10_000L,FinanceAccountProjection.reconcile(s,later).bookMinor)
    }
}
