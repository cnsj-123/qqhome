package com.qq.closie.life.finance.acquisition

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.FinanceCommandToken
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceProjectionRequestTest {
    private lateinit var context:Context
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        FinanceScreenshotPreference.set(context,true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }
    @After fun cleanup() {
        FinanceScreenshotPreference.set(context,false);RestoreStartupGate.resetForTesting()
        FinanceProjectionRequests.pending=null;FinanceProjectionRequests.inFlight=false
    }
    private fun request()=FinanceProjectionRequest("synthetic",FinanceCommandToken("original",7),
        RestoreStartupGate.state.value.restoreGeneration,context.resources.configuration.orientation)
    @Test fun permissionOffThenOnCannotResurrectOldConsent() {
        val old=request();assertTrue(old.current(context))
        FinanceScreenshotPreference.set(context,false);FinanceScreenshotPreference.set(context,true)
        assertFalse(old.current(context));assertTrue(request().current(context))
    }
    @Test fun lockAndCancellationInvalidateCapture() {
        val old=request()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertFalse(old.current(context));old.cancel()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        assertFalse(old.current(context))
    }
    @Test fun fastRestoreAndRotationCannotAcceptOldRequest() {
        val old=request();assertTrue(RestoreStartupGate.beginRestore());RestoreStartupGate.endRestoreReady()
        assertFalse(old.current(context))
        val rotated=request().copy(orientation=-1);assertFalse(rotated.current(context))
    }
}
