package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@OptIn(ExperimentalCoroutinesApi::class)
class BackupTaskOwnerRestoreTest:FinanceRoomTest() {
    @get:Rule val files=TemporaryFolder()
    @Test fun legacyRestoreSurvivesSettingsCancellationAtRealSwapBoundary()=runTest {verify(false)}
    @Test fun encryptedRestoreSurvivesSettingsCancellationAtRealSwapBoundary()=runTest {verify(true)}
    private suspend fun TestScope.verify(encrypted:Boolean) {
        val context:Context=ApplicationProvider.getApplicationContext()
        val wardrobe=LocalWardrobeRepository(context)
        val backup=files.newFile(if(encrypted) "synthetic.lifeos" else "synthetic.zip")
        fun password()="synthetic-only-password".toCharArray()
        val export=if(encrypted) EncryptedBackupManager.export(context,wardrobe,Uri.fromFile(backup),db,password())
            else BackupManager.export(context,wardrobe,Uri.fromFile(backup),db)
        export.getOrThrow()
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val hooks=object:RestoreHooks {override fun beforeClosetSwap() {
            entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))
        }}
        val application=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val page=CoroutineScope(Job()+StandardTestDispatcher(testScheduler))
        val owner=BackupTaskOwner(application)
        try {
            page.launch {owner.start("restored") {
                if(encrypted) EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(backup),db,password(),hooks)
                else BackupManager.restore(context,wardrobe,Uri.fromFile(backup),db,hooks)
            }}
            runCurrent();entered.await()
            assertEquals(RestoreStartupGate.Status.RESTORING,RestoreStartupGate.state.value.status)
            val generation=RestoreStartupGate.state.value.restoreGeneration
            page.cancel();runCurrent()
            assertTrue(owner.state.value.running);assertTrue(generation>0)
            release.countDown()
            owner.state.first {!it.running}
            assertFalse(owner.state.value.failed);assertEquals("restored",owner.state.value.result)
            assertEquals(RestoreStartupGate.Status.READY,RestoreStartupGate.state.value.status)
            assertEquals(generation,RestoreStartupGate.state.value.restoreGeneration)
            assertEquals(1L,finance.snapshot().v16!!.controlRows.single().restoreEpoch)
        } finally {release.countDown();application.cancel();page.cancel()}
    }
}
