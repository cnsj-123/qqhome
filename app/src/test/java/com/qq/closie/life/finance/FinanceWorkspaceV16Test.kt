package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceWorkspaceV16Test {
    private lateinit var db:LifeDatabase
    private lateinit var cash:FinanceRepository
    private lateinit var workspace:FinanceWorkspaceRepository
    @Before fun setup() {
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LifeDatabase::class.java)
            .allowMainThreadQueries().build()
        cash=FinanceRepository(db) { 1_000 };workspace=FinanceWorkspaceRepository(db,cash) { 2_000 }
    }
    @After fun close() { db.close();RestoreStartupGate.resetForTesting() }
    private fun token(epoch:Long=0)=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
    private suspend fun account()=cash.saveAccount(name="合成现金",kind=FinanceAccountKind.CASH,openingBalanceMinor=10_000)
    private fun draft(account:String,continuous:Boolean=false)=FinanceDraftEntity(id="draft",accountId=account,
        amountExpression="18+12.5",description="",occurredAt=1_100,occurredText="未完成时间输入",
        categoryName="餐饮",subcategoryName="早餐",currencyCode="CNY",continuous=continuous,epoch=0,createdAt=0,updatedAt=0)

    @Test fun partialDraftDoesNotAffectCashAndConfirmIsAtomicIdempotent()=runTest {
        val a=account();val input=draft(a.id)
        workspace.saveDraft(token(),input,setOf("合成标签"),emptyList(),null)
        assertTrue(cash.snapshot().entries.isEmpty())
        assertEquals(10_000L,FinanceProjection.balance(a,cash.snapshot().entries))
        val saved=cash.snapshot().v16!!.draftRows.single()
        assertEquals(input.occurredText,saved.occurredText)
        val operation=token()
        val result=workspace.commitDraft(operation,saved.id,saved.revision,1_100)
        assertEquals(result,workspace.commitDraft(operation,saved.id,saved.revision,1_100))
        val after=cash.snapshot()
        assertEquals(1,after.entries.size);assertEquals(3_050L,after.entries.single().amountMinor)
        assertEquals("餐饮 › 早餐",FinanceProjection.categoryPath(after.categories,after.entries.single().categoryId))
        assertEquals(setOf("合成标签"),FinanceProjection.ledger(after).single().tags.toSet())
        assertNotNull(after.v16!!.draftRows.single().voidedAt)
        assertTrue(runCatching { workspace.commitDraft(token(),saved.id,saved.revision,1_100) }.isFailure)
        assertEquals(after,cash.snapshot())
    }
    @Test fun invalidConfirmRollsBackAllCanonicalAndKeepsRecoverableDraft()=runTest {
        val a=account();val input=draft(a.id).copy(amountExpression="1/3")
        workspace.saveDraft(token(),input,emptySet(),emptyList(),null)
        val before=cash.snapshot()
        assertTrue(runCatching { workspace.commitDraft(token(),input.id,1,1_100) }.isFailure)
        assertEquals(before,cash.snapshot())
        assertTrue(before.categories.isEmpty())
    }
    @Test fun continuousIncomeResetsRelationshipButPreservesLockedFieldsAndRejectsStaleEditor()=runTest {
        val a=account();val input=draft(a.id,true).copy(kind=FinanceDraftKind.INCOME,role=FinanceFlowRole.OTHER,
            categoryName="",subcategoryName="",description="第一笔")
        workspace.saveDraft(token(),input,setOf("第一笔标签"),emptyList(),null)
        workspace.commitDraft(token(),input.id,1,1_100)
        val row=cash.snapshot().v16!!.draftRows.single()
        assertEquals(a.id,row.accountId);assertEquals(input.occurredAt,row.occurredAt)
        assertEquals("",row.amountExpression);assertEquals("",row.description)
        assertEquals(FinanceFlowRole.OTHER,row.role);assertNull(row.eventId);assertNull(row.voidedAt)
        assertTrue(cash.snapshot().v16!!.draftTagRows.isEmpty())
        assertEquals(1L,row.editGeneration)
        assertTrue(runCatching { workspace.saveDraft(token(),input,emptySet(),emptyList(),1) }.isFailure)
        // Even a VM that already received the newest revision cannot replay pre-commit form content.
        assertTrue(runCatching {workspace.saveDraft(token(),input,emptySet(),emptyList(),row.revision)}.exceptionOrNull() is FinanceDraftGenerationChanged)
        assertEquals("",cash.snapshot().v16!!.draftRows.single().amountExpression)
        workspace.saveDraft(token(),row.copy(amountExpression="2"),emptySet(),emptyList(),row.revision)
        workspace.commitDraft(token(),row.id,row.revision+1,1_100)
        assertEquals(2,cash.snapshot().entries.size)
        db.financeV16Dao().putControl(listOf(FinanceControlEntity(1,1)))
        val before=cash.snapshot()
        assertTrue(runCatching { workspace.saveDraft(token(),row,emptySet(),emptyList(),row.revision) }.isFailure)
        assertEquals(before,cash.snapshot())
    }
    @Test fun transferDraftFailureCannotLeaveOneLegAndOldEpochCannotReplayReceipt()=runTest {
        val a=account();val input=draft(a.id).copy(kind=FinanceDraftKind.TRANSFER,targetAccountId=a.id)
        val saveToken=token();workspace.saveDraft(saveToken,input,emptySet(),emptyList(),null)
        val before=cash.snapshot()
        assertTrue(runCatching { workspace.commitDraft(token(),input.id,1,1_100) }.isFailure)
        assertEquals(before,cash.snapshot())
        db.financeV16Dao().putControl(listOf(FinanceControlEntity(1,1)))
        assertTrue(runCatching { workspace.saveDraft(saveToken,input,emptySet(),emptyList(),null) }.isFailure)
        assertTrue(cash.snapshot().entries.isEmpty())
    }
    @Test fun anchorPreviewBindsProposedContentAndDoesNotRewriteHistory()=runTest {
        val a=account()
        val entry=cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="",occurredAt=900)
        val p=workspace.previewAnchor(a.id,5_000,800,false)
        assertTrue(runCatching { workspace.confirmAnchor(token(),p.copy(openingMinor=9_000)) }.isFailure)
        assertTrue(runCatching { workspace.previewAnchor(a.id,0,1_000,true) }.isFailure)
        workspace.confirmAnchor(token(),p)
        val after=cash.snapshot()
        assertEquals(entry,after.entries.single());assertEquals(a.id,after.accounts.single().id)
        assertEquals(4_900L,FinanceProjection.balance(after.accounts.single(),after.entries))
    }
    @Test fun postedBasisNeverFallsBackToCashAndHistoricalInsertInvalidatesConclusion()=runTest {
        val a=account()
        val e=cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="",occurredAt=1_100)
        val observation=workspace.saveObservation(token(),a.id,9_900,1_500,FinanceBalanceBasis.KNOWN_POSTED,FinanceTimePrecision.INSTANT,"")
        fun result(s:FinanceSnapshot)=FinanceAccountProjection.reconcile(s,s.v16!!.observationRows.single())
        assertNull(result(cash.snapshot()).bookMinor)
        workspace.setAccountPolicy(token(),a.id,true,FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.KNOWN_POSTED,null)
        assertNull(result(cash.snapshot()).bookMinor)
        workspace.setPostingDate(token(),e.id,e.revision,1_300,FinanceTimePrecision.INSTANT,"明确入账时间")
        val projection=result(cash.snapshot());assertEquals(9_900L,projection.bookMinor)
        workspace.confirmReconciliation(token(),observation,projection.fingerprint,"")
        val confirmed=cash.snapshot()
        val saved=confirmed.v16!!.reconciliationRows.single()
        assertFalse(FinanceAccountProjection.reconcile(confirmed,confirmed.v16!!.observationRows.single(),saved).invalidated)
        cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=20,description="补录",occurredAt=1_200)
        val after=cash.snapshot()
        val changed=FinanceAccountProjection.reconcile(after,after.v16!!.observationRows.single(),saved)
        assertTrue(changed.invalidated);assertNull(changed.bookMinor)
    }
    @Test fun reconfirmationUsesMonotonicOrderEvenWithIdenticalWallClock()=runTest {
        val a=account()
        val observation=workspace.saveObservation(token(),a.id,10_000,1_500,FinanceBalanceBasis.LEGACY_CASH,FinanceTimePrecision.INSTANT,"")
        var s=cash.snapshot();var o=s.v16!!.observationRows.single()
        workspace.confirmReconciliation(token(),observation,FinanceAccountProjection.fingerprint(s,o),"")
        cash.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="",occurredAt=1_100)
        assertEquals(FinanceTrust.INVALIDATED,FinanceAccountStatusProjection.status(cash.snapshot(),a.id).trust)
        s=cash.snapshot();o=s.v16!!.observationRows.single()
        workspace.confirmReconciliation(token(),observation,FinanceAccountProjection.fingerprint(s,o),"")
        val after=cash.snapshot()
        assertEquals(1,after.v16!!.reconciliationRows.map {it.createdAt}.distinct().size)
        assertEquals(FinanceTrust.DIFFERENT,FinanceAccountStatusProjection.status(after,a.id).trust)
        assertEquals(listOf(1L,2L),after.v16!!.reconciliationRows.map {it.confirmationOrder}.sorted())
    }
    @Test fun cancellationAfterCommitCannotStrandAutosaveAtOldExpectedRevision()=runTest {
        val a=account();val input=draft(a.id)
        val versions=com.qq.closie.ui.lifeos.finance.FinanceEditorVersions()
        lateinit var saving:kotlinx.coroutines.Job
        saving=launch(start=CoroutineStart.LAZY) {
            versions.mutex.withLock {versions.persistLocked("editor",null) {revision->
                workspace.saveDraft(token(),input,emptySet(),emptyList(),revision)
                saving.cancel() // Cancellation precisely after the actual Room commit, before version handoff.
                yield()
            }}
        }
        saving.start();saving.join()
        assertEquals(1L,cash.snapshot().v16!!.draftRows.single().revision)
        versions.mutex.withLock {versions.persistLocked("editor",null) {revision->
            assertEquals(1L,revision)
            workspace.saveDraft(token(),input.copy(description="取消后继续录入"),emptySet(),emptyList(),revision)
        }}
        assertEquals(2L,cash.snapshot().v16!!.draftRows.single().revision)
    }
}
