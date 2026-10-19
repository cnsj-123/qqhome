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
class FinanceShareV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    @Test fun shareGrantDoesNotInheritFieldsOrScopeFromHomeOrOtherShareGrants()=runTest {
        val a=account();val first=payment(a.id,1_234);val second=payment(a.id,9_999)
        val one=eventId(first.id);val two=eventId(second.id)
        finance.workspace.grant(token(),FinanceReadPurpose.HOME,"home-memory",setOf(one,two),emptySet(),true,true,true,10_000)
        finance.workspace.grant(token(),FinanceReadPurpose.SHARE,"manual-share",setOf(two),emptySet(),true,true,true,10_000)
        val dateOnly=finance.workspace.grant(token(),FinanceReadPurpose.SHARE,"manual-share",setOf(one),emptySet(),false,true,false,10_000)
        val preview=FinanceShareProjection.preview(finance.snapshot(),dateOnly,clock)
        assertTrue(preview.text.contains(FinanceTime.input(first.occurredAt)))
        assertFalse(preview.text.contains("12.34"));assertFalse(preview.text.contains("99.99"))
        assertFalse(preview.text.contains(first.description));assertFalse(preview.text.contains(first.id));assertFalse(preview.text.contains(a.name))
        assertEquals(preview.text,finance.confirmedShare(preview))
    }
    @Test fun changedFactRevocationExpiryAndRestoreAllRejectStaleSharePreview()=runTest {
        val a=account();val entry=payment(a.id,1_234);val event=eventId(entry.id)
        suspend fun grant()=finance.workspace.grant(token(),FinanceReadPurpose.SHARE,"manual-share",setOf(event),emptySet(),true,true,true,10_000)
        val id=grant();val preview=FinanceShareProjection.preview(finance.snapshot(),id,clock)
        assertTrue(runCatching {FinanceShareProjection.validate(finance.snapshot(),preview,10_000)}.isFailure)
        finance.saveEntry(entry.id,a.id,entry.direction,2_000,entry.description,entry.occurredAt,
            event=FinanceEventInput(existingEventId=event,expectedEventUpdatedAt=finance.snapshot().v4!!.events.single {it.id==event}.updatedAt),expectedRevision=entry.revision)
        assertTrue(runCatching {finance.confirmedShare(preview)}.isFailure)
        val updated=FinanceShareProjection.preview(finance.snapshot(),id,clock)
        finance.workspace.revoke(token(),id)
        assertTrue(runCatching {finance.confirmedShare(updated)}.isFailure)
        val next=grant();val beforeRestore=FinanceShareProjection.preview(finance.snapshot(),next,clock)
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        assertTrue(runCatching {finance.confirmedShare(beforeRestore)}.isFailure)
        assertEquals(1,finance.snapshot().entries.size)
    }
}
