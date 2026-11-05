package com.qq.closie.life.finance.acquisition

import android.content.Context
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A queued host-bound intent cannot become an unbound capture after its host expires. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceProjectionOriginTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        FinanceProjectionHosts.current?.close()
        FinanceProjectionRequests.pending = null
        FinanceProjectionRequests.inFlight = false
        FinanceReceiptOverlay.endCapture()
        FinanceScreenshotPreference.set(context, true)
    }

    @After fun cleanup() {
        FinanceProjectionHosts.current?.close()
        FinanceProjectionRequests.pending = null
        FinanceProjectionRequests.inFlight = false
        FinanceReceiptOverlay.endCapture()
        FinanceScreenshotPreference.set(context, false)
        RestoreStartupGate.resetForTesting()
    }

    private fun assertRejected(originId: String) {
        val intent = FinanceProjectionRequests.intent(context).putExtra("originId", originId)
        val controller = Robolectric.buildActivity(FinanceProjectionActivity::class.java, intent).create()
        try {
            val activity = controller.get()
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertNull(FinanceProjectionRequests.pending)
            assertFalse(FinanceProjectionRequests.inFlight)
            assertFalse(FinanceReceiptOverlay.occupied)
        } finally {
            controller.destroy()
        }
    }

    @Test fun unknownOriginIdFinishesBeforeAcquiringCaptureOrRequestingConsent() {
        assertRejected("expired-host")
    }

    @Test fun closedOriginCannotFallBackOrReplaceANewerLiveHost() {
        val serviceController = Robolectric.buildService(FinanceFixtureService::class.java).create()
        try {
            val service = serviceController.get()
            fun host(windowId: Int): FinanceProjectionHost {
                val window = FinanceHostWindow(windowId, "synthetic.host", AccessibilityWindowInfo.TYPE_APPLICATION,
                    active = true, focused = true, identity = null)
                val session = FinanceHostSession(window, service.resources.configuration.orientation)
                return FinanceProjectionHost(service, FinanceHostWindows(service), session)
            }
            val expired = host(1)
            FinanceProjectionHosts.offer(expired)
            expired.close()
            val current = host(2)
            FinanceProjectionHosts.offer(current)
            assertTrue(current.live)

            assertRejected(expired.id)

            assertSame(current, FinanceProjectionHosts.current)
            assertTrue(current.live)
        } finally {
            FinanceProjectionHosts.current?.close()
            serviceController.destroy()
        }
    }
}
