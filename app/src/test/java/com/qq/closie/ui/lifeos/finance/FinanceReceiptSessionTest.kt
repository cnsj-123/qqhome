package com.qq.closie.ui.lifeos.finance

import android.content.Context
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.ClosieApplication
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.finance.acquisition.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** Real WindowManager attachment and native editor tree under shadows; not a device/IME recording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceReceiptSessionTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val edits=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var overlay:FinanceReceiptOverlay
    private lateinit var container:LifeContainer
    private val host=FinanceHostWindow(1,"synthetic.host",android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,true,true,"original-transaction")
    private var snapshot=FinanceHostSnapshot(1,listOf(host))
    private lateinit var lease:FinanceHostSession
    @Before fun setup() {
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady();ShadowSettings.setCanDrawOverlays(true)
        FinanceReviewRequests.foreground=false;FinanceScreenshotPreference.set(context,true)
        container=LifeContainer.createForTesting(context,androidx.room.Room.inMemoryDatabaseBuilder(context,
            com.qq.closie.life.data.database.LifeDatabase::class.java).allowMainThreadQueries().build())
        overlay=FinanceReceiptOverlay(context,scope,containerProvider={container},editOwner=edits)
        lease=FinanceHostSession(host,1,context.packageName)
    }
    @After fun cleanup() {
        overlay.close(false);FinanceReceiptOverlay.endCapture();scope.cancel()
        edits.cancel()
        FinanceScreenshotPreference.set(context,false);container.lifeDatabase.close();RestoreStartupGate.resetForTesting()
    }
    private suspend fun attach() {
        val id=container.financeRepository.media.createScreenshotCandidate(container.financeRepository.economics.token(),
            FinanceOcrFields(1234,null,FinanceProposalKind.EXPENSE,null,"合成",currencyCode="CNY")){true}
        assertTrue(FinanceReceiptOverlay.beginCapture())
        assertTrue(overlay.show(id,true,canContinue={lease.editorCurrent(snapshot)},canPresent={lease.captureCurrent(snapshot)}))
    }
    private fun views():List<View> {
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList())
        return org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views.flatMap(::all)
    }
    @Test fun attachedEditorSurvivesFocusTransferAndNativeEditsButClosesOnActualPageChange()=runBlocking {
        attach()
        snapshot=snapshot.copy(windows=listOf(host.copy(active=false,focused=false),
            FinanceHostWindow(2,context.packageName,android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM,true,true,null)))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertTrue(FinanceReceiptOverlay.occupied)
        val inputs=views().filterIsInstance<EditText>();inputs.first().setText("23.45")
        val flattened=views();val label=flattened.indexOfFirst {it is TextView && it.text.toString()=="备注（可留空）"}
        flattened.drop(label+1).filterIsInstance<EditText>().first().setText("合成编辑")
        for(label in listOf("收付/转出账户","分类","实际发生时间")) {
            val button=if(label=="实际发生时间")views().filterIsInstance<EditText>().firstOrNull {!it.isFocusable}
                else views().filterIsInstance<Button>().firstOrNull {it.text.toString().startsWith(label)}
            assertNotNull(label,button);button!!.performClick()
            val back=views().filterIsInstance<Button>().firstOrNull {it.text.toString().startsWith("返回小票")}
            assertNotNull("same-window subpanel must open",back);back!!.performClick()
            assertTrue(FinanceReceiptOverlay.occupied)
        }
        assertFalse(FinanceReceiptOverlay.beginCapture()) // a second request cannot evict an editor
        snapshot=snapshot.copy(windows=listOf(host.copy(identity="different-transaction")))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
        assertFalse(FinanceReceiptOverlay.occupied)
    }
    @Test fun occupancySignalSpansCaptureToEditorAndNotifiesClose()=runBlocking {
        attach();assertTrue(FinanceReceiptOverlay.occupancyChanges.value)
        FinanceReceiptOverlay.endCapture();assertTrue(FinanceReceiptOverlay.occupancyChanges.value)
        overlay.close(false);assertFalse(FinanceReceiptOverlay.occupancyChanges.value)
    }
}
