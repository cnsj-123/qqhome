package com.qq.closie.life.finance.acquisition

import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceProjectionHostTest:FinanceRoomTest() {
    private val serviceController=Robolectric.buildService(FinanceFixtureService::class.java)
    private lateinit var service:FinanceFixtureService
    private lateinit var reader:FinanceHostWindows
    private var reads=0
    private var host:FinanceProjectionHost?=null
    private val edits=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    @Before fun setup() {
        service=serviceController.create().get();ShadowSettings.setCanDrawOverlays(true);FinanceScreenshotPreference.set(service,true)
        reader=FinanceHostWindows(service){node,adapter,occluded->reads++;VisibleDetailReader.inspect(node,adapter,occluded)}
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,FinanceHostFixtures.ALIPAY))
    }
    @After fun cleanup(){host?.close();edits.cancel();FinanceReceiptOverlay.endCapture();FinanceProjectionHosts.current?.close();FinanceScreenshotPreference.set(service,false);serviceController.destroy()}
    private fun origin():FinanceProjectionHost {
        val lease=requireNotNull(reader.bindActive());assertNotNull(lease.host.identity)
        val container=LifeContainer.createForTesting(service,db)
        return FinanceProjectionHost(service,reader,lease){closed->FinanceReceiptOverlay(service,edits,{container},edits,closed)}
            .also {host=it;FinanceProjectionHosts.offer(it)}
    }
    @Test fun consentUsesOnlyMetadataAndReturnToDifferentTransactionCannotResume()=runTest {
        val origin=origin();val before=reads
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(3,service.packageName))
        origin.observe(FinanceHostFixtures.event(3,service.packageName))
        assertEquals(before,reads);assertTrue(origin.live)
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,FinanceHostFixtures.ALIPAY,order="ORDER-999999"))
        assertTrue(runCatching {origin.awaitReturn()}.isFailure)
        assertFalse(origin.captureCurrent());assertTrue(db.financeIntakeDao().proposals().isEmpty())
    }
    @Test fun boundProjectionCanMountExistingEditablePaperAndSurvivesCaptureServiceDestruction()=runBlocking {
        val origin=origin();origin.awaitReturn()
        val token=finance.economics.token()
        val id=finance.media.createScreenshotCandidate(token,FinanceLocalOcrParser.parse("支付成功\n人民币12.34")){true}
        val reservation=Any();assertTrue(FinanceReceiptOverlay.beginCapture(reservation))
        assertTrue(origin.present(id,token.restoreEpoch){true})
        val engine=Robolectric.buildService(FinanceProjectionService::class.java).create()
        val request=FinanceProjectionRequest("editor",token,RestoreStartupGate.state.value.restoreGeneration,
            service.resources.configuration.orientation,origin,reservation)
        ReflectionHelpers.setField(engine.get(),"owned",request);ReflectionHelpers.setField(engine.get(),"handedToEditor",true)
        engine.destroy()
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(1,FinanceHostFixtures.ALIPAY,false),
            FinanceHostFixtures.window(3,service.packageName,type=AccessibilityWindowInfo.TYPE_SYSTEM))
        origin.observe(FinanceHostFixtures.event(3,service.packageName))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertTrue(FinanceReceiptOverlay.occupied);assertTrue(origin.editorCurrent());assertTrue(finance.snapshot().entries.isEmpty())
        FinanceHostFixtures.windows(service,FinanceHostFixtures.window(8,"other.app"))
        origin.observe(FinanceHostFixtures.event(8,"other.app"))
        assertFalse(FinanceReceiptOverlay.occupied);assertFalse(origin.live);assertNull(FinanceProjectionHosts.current)
    }
    @Test fun restoreOrPreferenceRevocationMakesOriginalProjectionHostUnusable()=runBlocking {
        val origin=origin();origin.awaitReturn();assertTrue(origin.captureCurrent())
        FinanceScreenshotPreference.set(service,false);assertFalse(origin.captureCurrent())
        FinanceScreenshotPreference.set(service,true);assertFalse(origin.captureCurrent())
        RestoreStartupGate.beginRestore();RestoreStartupGate.markReady();assertFalse(origin.live)
    }
    @Config(sdk=[26])
    @Test fun api26BubbleTransfersExactHostToProjectionAndReleasesAccessibilityCaptureOwner() {
        val controller=FinanceScreenshotController(service,edits,{})
        try {
            controller.onConnected()
            ReflectionHelpers.callInstanceMethod<Void>(controller,"request")
            assertFalse(controller.hasHostSession)
            val origin=requireNotNull(FinanceProjectionHosts.current);host=origin
            val app=service.application as android.app.Application
            val intent=requireNotNull(shadowOf(app).nextStartedActivity)
            assertEquals(FinanceProjectionActivity::class.java.name,intent.component?.className)
            assertEquals(origin.id,intent.getStringExtra("originId"));assertTrue(origin.live)
            assertFalse(FinanceReceiptOverlay.occupied)
        } finally {controller.close()}
    }
}
