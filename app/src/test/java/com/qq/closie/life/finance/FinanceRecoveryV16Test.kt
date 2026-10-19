package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceRecoveryV16Test {
    private lateinit var db: LifeDatabase
    private lateinit var cash: FinanceRepository
    private lateinit var recovery: FinanceRecoveryRepository
    private lateinit var economic: FinanceEconomicRepository
    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LifeDatabase::class.java).allowMainThreadQueries().build()
        cash=FinanceRepository(db) { 1_000 }; recovery=FinanceRecoveryRepository(db,cash) { 2_000 }
        economic=FinanceEconomicRepository(db,cash) { 1_000 }
    }
    @After fun close() { db.close(); RestoreStartupGate.resetForTesting() }
    private suspend fun account(name: String) = cash.saveAccount(name=name,kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=100_000)
    private suspend fun payment(id:String) = cash.saveEntry(accountId=id,direction=FinanceDirection.OUTFLOW,
        amountMinor=1_000,description="",occurredAt=1_100,event=FinanceEventInput())

    @Test fun transferVoidAndRecoveryRestoreBothLegsWithMonotonicRevisions() = runTest {
        val a=account("合成A"); val b=account("合成B")
        val transfer=cash.saveTransfer(sourceAccountId=a.id,targetAccountId=b.id,amountMinor=2_000,description="",occurredAt=1_100)
        val before=cash.snapshot()
        val preview=recovery.previewVoid(entryId=transfer.outflowEntryId)
        assertEquals(setOf(transfer.outflowEntryId,transfer.inflowEntryId),preview.entryIds)
        val group=recovery.voidConfirmed(preview)
        assertEquals(group,recovery.voidConfirmed(preview))
        assertTrue(cash.snapshot().entries.all { it.voidedAt!=null })
        recovery.recover(group,economic.token())
        val after=cash.snapshot()
        assertTrue(after.entries.all { it.voidedAt==null })
        assertEquals(FinanceProjection.balance(a,before.entries),FinanceProjection.balance(a,after.entries))
        assertEquals(FinanceProjection.balance(b,before.entries),FinanceProjection.balance(b,after.entries))
        assertTrue(after.entries.all { row -> row.revision>before.entries.single { it.id==row.id }.revision })
    }
    @Test fun batchPreviewBindsPayloadAndConflictHasNoPartialCommit() = runTest {
        val a=account("合成A"); val b=account("合成B")
        val first=payment(a.id); val second=payment(a.id)
        val edit=FinanceBatchCorrection(accountId=b.id)
        val preview=recovery.previewBatch(setOf(first.id,second.id),edit)
        assertTrue(runCatching { recovery.correctBatch(preview,edit.copy(accountId=a.id)) }.isFailure)
        cash.saveEntry(first.id,a.id,first.direction,first.amountMinor,"改后",first.occurredAt,expectedRevision=first.revision)
        val current=cash.snapshot()
        assertTrue(runCatching { recovery.correctBatch(preview,edit) }.isFailure)
        assertEquals(current,cash.snapshot())
    }
    @Test fun successfulBatchCanRecoverBeforeAnUnrelatedNewEntry() = runTest {
        val a=account("合成A"); val b=account("合成B")
        val entry=payment(a.id)
        val group=recovery.correctBatch(recovery.previewBatch(setOf(entry.id),FinanceBatchCorrection(accountId=b.id)),
            FinanceBatchCorrection(accountId=b.id))
        payment(a.id)
        recovery.recover(group,economic.token())
        val after=cash.snapshot()
        assertEquals(a.id,after.entries.single { it.id==entry.id }.accountId)
        assertEquals(98_000L,FinanceProjection.balance(a,after.entries))
        assertEquals(100_000L,FinanceProjection.balance(b,after.entries))
    }
}
