package com.qq.closie.ui.lifeos.home

import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.backup.LifeBackupApplier
import com.qq.closie.life.finance.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled clock/notifications, real Room snapshots and the real production cost calculation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceHomeProjectionV16Test:FinanceRoomTest() {
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    @Test fun ticksAndJournalNotificationsDoNotRecomputeWhileFinancialEditsAndRevocationDo()=runTest {
        val a=account();val entry=payment(a.id,100);val event=eventId(entry.id)
        val grant=finance.workspace.grant(token(),FinanceReadPurpose.HOME,"home-memory",setOf(event),emptySet(),true,true,true,5_000)
        var now=1_000L;var calculations=0
        val provider=FinanceHomeStateProvider(finance,{now}) {s,time->
            calculations++;FinancePurposeRead.prepareFacts(s,FinanceReadPurpose.HOME,"home-memory",time)
        }
        val snapshots=MutableStateFlow(finance.portableSnapshot());val ticks=MutableStateFlow(now)
        val date=Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val seen=mutableListOf<HomeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.states(date,snapshots,ticks,UnconfinedTestDispatcher(testScheduler)).collect {seen+=it}
        }
        runCurrent();assertEquals(1,calculations);assertEquals(1,seen.last().todayFacts.size)
        repeat(100) {now++;ticks.value=now;runCurrent()}
        assertEquals(1,calculations)
        val actual=snapshots.value
        assertTrue(actual.v4!!.changes.isNotEmpty())
        snapshots.value=actual.copy(v4=actual.v4!!.copy(changes=emptyList()))
        runCurrent();assertEquals(1,calculations) // A journal-only notification has no Home dependency.
        finance.saveEntry(entry.id,entry.accountId,entry.direction,200,entry.description,entry.occurredAt,
            event=FinanceEventInput(existingEventId=event,expectedEventUpdatedAt=actual.v4!!.events.single().updatedAt),expectedRevision=entry.revision)
        snapshots.value=finance.portableSnapshot();runCurrent()
        assertEquals(2,calculations);assertTrue(seen.last().todayFacts.single().title.contains("2.00"))
        now=5_000;ticks.value=now;runCurrent()
        assertTrue(seen.last().todayFacts.isEmpty());assertEquals(2,calculations)
        finance.workspace.revoke(token(),grant);snapshots.value=finance.portableSnapshot();runCurrent()
        assertEquals(3,calculations);assertTrue(seen.last().todayFacts.isEmpty())
        repeat(100) {now++;ticks.value=now;runCurrent()};assertEquals(3,calculations)
    }

    @Test fun restoreGateClearsHomeAndNewCollectorReadsRevokedRestoredGrants()=runTest {
        val a=account();val entry=payment(a.id,100);val event=eventId(entry.id)
        finance.workspace.grant(token(),FinanceReadPurpose.HOME,"home-memory",setOf(event),emptySet(),true,true,true,10_000)
        val provider=FinanceHomeStateProvider(finance,{1_000})
        val snapshots=MutableStateFlow(finance.snapshot());val ticks=MutableStateFlow(1_000L)
        val date=Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val seen=mutableListOf<HomeUiState>()
        val collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.states(date,snapshots,ticks,UnconfinedTestDispatcher(testScheduler)).collect {seen+=it}
        }
        runCurrent();assertEquals(1,seen.last().todayFacts.size)
        RestoreStartupGate.markRestoreUnfinished(IllegalStateException("synthetic interrupted restore"))
        runCurrent();assertTrue(seen.last().todayFacts.isEmpty())
        collector.cancel();runCurrent()
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        LifeBackupApplier.restoreSnapshot(db,LifeBackupApplier.snapshot(db))
        snapshots.value=finance.snapshot()
        val restarted=mutableListOf<HomeUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            FinanceHomeStateProvider(finance,{1_000}).states(date,snapshots,ticks,UnconfinedTestDispatcher(testScheduler)).collect {restarted+=it}
        }
        runCurrent()
        assertTrue(restarted.isNotEmpty());assertTrue(restarted.all {it.todayFacts.isEmpty() && it.historyItems.isEmpty()})
        assertTrue(snapshots.value.v16!!.purposeGrantRows.all {it.voidedAt!=null})
    }
}
