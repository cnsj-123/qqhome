package com.qq.closie.life.finance.acquisition

import android.graphics.Bitmap
import android.graphics.Color
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceCaptureTerminationTest:FinanceRoomTest() {
    private fun terminal(reason:String)=runTest {
        val serviceController=Robolectric.buildService(FinanceFixtureService::class.java).create()
        val service=serviceController.get()
        val dispatcher=StandardTestDispatcher(testScheduler)
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val container=LifeContainer.createForTesting(service,db)
        var reads=0;var shots=0
        val windows=FinanceHostWindows(service){node,adapter,occluded->reads++;VisibleDetailReader.inspect(node,adapter,occluded)}
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,FinanceHostFixtures.ALIPAY))
        ShadowSettings.setCanDrawOverlays(true);FinanceScreenshotPreference.set(service,true)
        FinanceAutomationSettings(service).save(FinanceDiscoverySettings(visibleDetailEnabled=false))
        val controller=FinanceScreenshotController(service,scope,{},
            captureFrame={shots++;if(reason=="capture")error("synthetic platform failure")
                Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}},
            recognizeFrame={frame->frame.recycle();if(reason in setOf("timeout","cancel"))awaitCancellation()
                FinanceLocalOcrParser.parse(if(reason=="success")"支付成功\n人民币12.34" else "支付成功\nUSD 12.34")},
            containerProvider={container},io=dispatcher,editOwner=scope,hostWindows=windows)
        try {
            controller.onConnected();runCurrent()
            fun start():Job {
                ReflectionHelpers.callInstanceMethod<Void>(controller,"request")
                return requireNotNull(ReflectionHelpers.getField<Job?>(controller,"capture"))
            }
            val task=start();advanceTimeBy(181);runCurrent()
            if(reason=="cancel")task.cancel()
            task.join();runCurrent()
            if(reason=="success") {
                assertTrue(controller.hasHostSession);assertTrue(FinanceReceiptOverlay.occupied)
                controller.hostEvent(FinanceHostFixtures.event());assertTrue(controller.hasHostSession)
                controller.close();assertFalse(controller.hasHostSession);assertFalse(FinanceReceiptOverlay.occupied)
            } else {
            assertFalse(controller.hasHostSession);assertFalse(FinanceReceiptOverlay.occupied)
            val endedReads=reads
            repeat(3){controller.hostEvent(FinanceHostFixtures.event())}
            assertEquals(endedReads,reads)
            assertTrue(db.financeIntakeDao().proposals().isEmpty());assertTrue(finance.snapshot().entries.isEmpty())
            val previousShots=shots;val retry=start();advanceTimeBy(181);runCurrent()
            if(reason=="cancel")retry.cancel()
            retry.join();assertTrue(shots>previousShots);assertFalse(controller.hasHostSession)
            }
        } finally {controller.close();scope.cancel();FinanceReceiptOverlay.endCapture();FinanceScreenshotPreference.set(service,false);serviceController.destroy()}
    }
    @Test fun screenshotFailureReleasesLeaseAndSubsequentRefreshCannotReadFacts()=terminal("capture")
    @Test fun ocrTimeoutReleasesLeaseAndSubsequentRefreshCannotReadFacts()=terminal("timeout")
    @Test fun currencyRefusalReleasesLeaseAndSubsequentRefreshCannotReadFacts()=terminal("currency")
    @Test fun cancellationReleasesLeaseAndSubsequentRefreshCannotReadFacts()=terminal("cancel")
    @Test fun successfulMountedEditorRetainsLeaseUntilEditorCloses()=terminal("success")
    @Test fun staleCaptureOwnerCannotReleaseNewReservation() {
        val old=Any();val next=Any()
        assertTrue(FinanceReceiptOverlay.beginCapture(old));FinanceReceiptOverlay.endCapture(old)
        assertTrue(FinanceReceiptOverlay.beginCapture(next));FinanceReceiptOverlay.endCapture(old)
        assertTrue(FinanceReceiptOverlay.occupied);FinanceReceiptOverlay.endCapture(next);assertFalse(FinanceReceiptOverlay.occupied)
        FinanceProjectionRequests.start(old);FinanceProjectionRequests.finish(old);FinanceProjectionRequests.start(next)
        FinanceProjectionRequests.finish(old);assertTrue(FinanceProjectionRequests.inFlight)
        FinanceProjectionRequests.finish(next);assertFalse(FinanceProjectionRequests.inFlight)
    }
}
