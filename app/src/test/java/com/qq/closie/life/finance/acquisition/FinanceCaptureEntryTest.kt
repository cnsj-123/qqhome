package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.finance.FinanceAutomationSettings
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
class FinanceCaptureEntryTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    @Before fun setup(){RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady();ShadowSettings.setCanDrawOverlays(true);FinanceScreenshotPreference.set(context,false)}
    @After fun cleanup(){FinanceScreenshotPreference.set(context,false);FinanceCaptureEntryState.nativeVisible.value=false;RestoreStartupGate.resetForTesting()}
    @Test fun enablingScreenshotAloneReconfiguresConnectedServiceWithoutReconnectAndOffRemovesButton() {
        val controller=Robolectric.buildService(FinanceAccessibilityService::class.java).create()
        try {
            val service=controller.get()
            FinanceAutomationSettings(context).save(FinanceAutomationSettings(context).read().copy(visibleDetailEnabled=false))
            ReflectionHelpers.callInstanceMethod<Void>(service,"onServiceConnected")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(FinanceCaptureEntryState.nativeVisible.value)
            FinanceScreenshotPreference.set(context,true);shadowOf(Looper.getMainLooper()).idle()
            assertTrue(FinanceCaptureEntryState.nativeVisible.value)
            val info=ReflectionHelpers.getField<AccessibilityServiceInfo>(service,"mInfo")
            assertTrue(info.eventTypes and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED!=0)
            assertNull(info.packageNames)
            FinanceScreenshotPreference.set(context,false);shadowOf(Looper.getMainLooper()).idle()
            assertFalse(FinanceCaptureEntryState.nativeVisible.value)
        } finally {controller.destroy()}
    }
    @Test fun externalUserEntryIsPersistentNotificationPendingIntentAndDoesNotStartBackgroundService() {
        FinanceScreenshotPreference.set(context,true);FinanceProjectionBubbleService.ensure(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        val notification=requireNotNull(shadowOf(manager).getNotification(606))
        assertNotNull(notification.contentIntent);assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT!=0)
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService)
        FinanceCaptureEntryState.nativeVisible.value=true;FinanceProjectionBubbleService.ensure(context)
        assertNull(shadowOf(manager).getNotification(606))
        FinanceCaptureEntryState.nativeVisible.value=false;FinanceProjectionBubbleService.ensure(context)
        assertNotNull(shadowOf(manager).getNotification(606))
    }
    @Test fun nativeEntryOnOpaquePageRequiresExplicitSystemConsentInsteadOfPretendingToBindATransaction() {
        val controller=Robolectric.buildService(FinanceAccessibilityService::class.java).create()
        try {
            val service=controller.get()
            val root=android.view.accessibility.AccessibilityNodeInfo.obtain().apply {packageName="opaque.host";isVisibleToUser=true}
            val window=android.view.accessibility.AccessibilityWindowInfo.obtain()
            shadowOf(window).apply {setId(9);setType(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION);setRoot(root);setActive(true);setFocused(true)}
            shadowOf(service).setWindows(listOf(window))
            ReflectionHelpers.callInstanceMethod<Void>(service,"onServiceConnected")
            FinanceScreenshotPreference.set(context,true);shadowOf(Looper.getMainLooper()).idle()
            val wm=org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
            val button=wm.views.filterIsInstance<TextView>().single {it.contentDescription?.toString()?.startsWith("单次截图识别账单")==true}
            assertTrue(button.performClick())
            val launched=shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
            assertEquals(FinanceProjectionActivity::class.java.name,launched.component?.className)
            assertFalse(com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.occupied)
        } finally {controller.destroy()}
    }

}
