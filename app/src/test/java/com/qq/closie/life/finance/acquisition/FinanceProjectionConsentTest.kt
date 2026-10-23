package com.qq.closie.life.finance.acquisition

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.FinanceCommandToken
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceProjectionConsentTest {
    private lateinit var context:Context
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        FinanceScreenshotPreference.set(context,true)
        FinanceProjectionRequests.pending=null;FinanceProjectionRequests.inFlight=true
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        assertTrue(FinanceReceiptOverlay.beginCapture())
    }
    @After fun cleanup() {
        FinanceReceiptOverlay.endCapture();FinanceProjectionRequests.pending=null;FinanceProjectionRequests.inFlight=false
        FinanceScreenshotPreference.set(context,false);RestoreStartupGate.resetForTesting()
    }
    private fun assertRejected(intent:Intent) {
        val controller=Robolectric.buildService(FinanceProjectionService::class.java).create()
        try {
            val service=controller.get()
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(intent,0,1))
            assertNull(shadowOf(service).lastForegroundNotification)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(FinanceProjectionRequests.inFlight);assertNull(FinanceProjectionRequests.pending)
            assertFalse(FinanceReceiptOverlay.occupied)
        } finally {controller.destroy()}
    }
    @Test fun missingConsentCannotStartProjectionForegroundService() {
        assertRejected(Intent(context,FinanceProjectionService::class.java))
    }
    @Test fun revokedRequestCannotReuseReturnedConsent() {
        val request=FinanceProjectionRequest("original",FinanceCommandToken("synthetic",0),
            RestoreStartupGate.state.value.restoreGeneration,context.resources.configuration.orientation)
        request.cancel();FinanceProjectionRequests.pending=request
        assertRejected(Intent(context,FinanceProjectionService::class.java).putExtra("consent",Intent("synthetic-only"))
            .putExtra("request",request.id))
    }
}
