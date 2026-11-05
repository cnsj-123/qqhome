package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual manager/URI/archive boundary, beyond the standalone AEAD envelope tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class EncryptedBackupManagerV16Test:FinanceRoomTest() {
    @get:Rule val files=TemporaryFolder()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun password()="synthetic-only-password".toCharArray()
    private fun assertScratchClean() {
        assertTrue(context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("secure_backup_") || it.name.startsWith("secure_restore_")})
    }
    private suspend fun seedUnknown():String {
        val economics=finance.economics
        val friend=economics.saveParty(economics.token(),"合成外部付款方")
        return economics.confirmEconomic(economics.token(),FinanceEconomicInput(description="合成未知关系",currencyCode="CNY",
            occurredAt=clock,valueMinor=12_345,valueBasis="合成确认",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput("fund",friend,12_345,basis="合成外部付款")),
            shares=listOf(FinanceShareInput("share",12_345,"self",null,"fund",FinanceShareRelation.UNRESOLVED))))
    }

    @Test fun wrongPasswordAndLateCorruptionCannotReachCanonicalRestoreAndScratchIsRemoved()=runTest {
        seedUnknown();val wardrobe=LocalWardrobeRepository(context)
        val encrypted=files.newFile("synthetic.lifeos");val key=password()
        assertTrue(EncryptedBackupManager.export(context,wardrobe,Uri.fromFile(encrypted),db,key).isSuccess)
        assertTrue(key.all {it=='\u0000'});assertScratchClean()
        val original=encrypted.readBytes();val before=finance.portableSnapshot()
        val wrong="synthetic-wrong-password".toCharArray()
        assertTrue(EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(encrypted),db,wrong).isFailure)
        assertTrue(wrong.all {it=='\u0000'});assertEquals(before,finance.portableSnapshot());assertScratchClean()
        val damaged=original.copyOf();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte()
        encrypted.writeBytes(damaged)
        val second=password()
        assertTrue(EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(encrypted),db,second).isFailure)
        assertTrue(second.all {it=='\u0000'});assertEquals(before,finance.portableSnapshot());assertScratchClean()
    }

    @Test fun verifiedManagerArchiveRestoresTypedUnknownToAnotherDatabaseAndRevokesGrant()=runTest {
        val event=seedUnknown();val wardrobe=LocalWardrobeRepository(context)
        finance.workspace.grant(finance.economics.token(),FinanceReadPurpose.HOME,"home-memory",setOf(event),emptySet(),
            true,true,true,Long.MAX_VALUE)
        val encrypted=files.newFile("portable.lifeos")
        assertTrue(EncryptedBackupManager.export(context,wardrobe,Uri.fromFile(encrypted),db,password()).isSuccess)
        val target=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val key=password()
            assertTrue(EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(encrypted),target,key).isSuccess)
            val restored=FinanceRepository(target).portableSnapshot()
            assertTrue(key.all {it=='\u0000'});assertScratchClean()
            assertEquals(event,restored.v4!!.events.single().id)
            assertTrue(restored.entries.isEmpty())
            assertEquals(PersonalCostTotal(0,1),FinanceCostProjection.eventTotal(restored,event))
            assertTrue(restored.v16!!.purposeGrantRows.all {it.voidedAt!=null})
            assertEquals(1L,restored.v16!!.controlRows.single().restoreEpoch)
            FinanceIntegrity.validate(restored)
        } finally {target.close()}
    }

    @Test fun inaccessibleOutputReportsFailureWithoutChangingFactsAndErasesPassword()=runTest {
        seedUnknown();val before=finance.portableSnapshot();val key=password()
        val unreachable=java.io.File(files.root,"missing-parent/output.lifeos")
        assertTrue(EncryptedBackupManager.export(context,LocalWardrobeRepository(context),Uri.fromFile(unreachable),db,key).isFailure)
        assertTrue(key.all {it=='\u0000'});assertEquals(before,finance.portableSnapshot());assertScratchClean()
    }

    @Test fun inspectedRestoreRejectsAReplacedSourceAndAcceptsOnlyTheVerifiedBytes()=runTest {
        val event=seedUnknown();val wardrobe=LocalWardrobeRepository(context)
        val file=files.newFile("preview.lifeos");val uri=Uri.fromFile(file)
        assertTrue(EncryptedBackupManager.export(context,wardrobe,uri,db,password()).isSuccess)
        val firstKey=password()
        val first=EncryptedBackupManager.inspect(context,uri,firstKey).getOrThrow()
        assertTrue(first.includesLifeOs);assertEquals(0,first.entries);assertEquals(32,first.sourceDigest.size)
        assertTrue(firstKey.all {it=='\u0000'});assertScratchClean()
        val before=finance.portableSnapshot()
        // A second valid encryption has a different salt/nonce. Even valid substituted bytes
        // cannot silently replace the file whose scope the user just reviewed.
        assertTrue(EncryptedBackupManager.export(context,wardrobe,uri,db,password()).isSuccess)
        val staleKey=password()
        val refused=EncryptedBackupManager.restoreInspected(context,wardrobe,uri,db,staleKey,first)
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()?.message.orEmpty().contains("备份文件已改变"))
        assertEquals(before,finance.portableSnapshot());assertTrue(staleKey.all {it=='\u0000'});assertScratchClean()
        val verified=EncryptedBackupManager.inspect(context,uri,password()).getOrThrow()
        val target=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertTrue(EncryptedBackupManager.restoreInspected(context,wardrobe,uri,target,password(),verified).isSuccess)
            val restored=FinanceRepository(target).portableSnapshot()
            assertEquals(event,restored.v4!!.events.single().id)
            assertEquals(PersonalCostTotal(0,1),FinanceCostProjection.eventTotal(restored,event))
            assertEquals(1L,restored.v16!!.controlRows.single().restoreEpoch)
            assertScratchClean()
        } finally {target.close()}
    }
}
